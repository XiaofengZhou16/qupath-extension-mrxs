package io.github.xiaofengzhou.qupath.mrxs;

import qupath.lib.common.ColorTools;
import qupath.lib.display.ChannelDisplayMode;
import qupath.lib.display.DirectServerChannelInfo;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;

import java.awt.image.BufferedImage;
import java.awt.image.ByteLookupTable;
import java.awt.image.LookupOp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

final class IfDisplaySettings {
    private static final Map<QuPathViewer, GammaState> GAMMA = new WeakHashMap<>();
    private record GammaState(double value, boolean bound) {}

    static IfDisplayProfile capture(QuPathViewer viewer) {
        if (viewer==null || viewer.getServer()==null || viewer.getServer().isRGB())
            throw new IllegalArgumentException("Open an image with separate fluorescence channels");
        var display=viewer.getImageDisplay();
        List<IfDisplayProfile.Channel> channels=new ArrayList<>();
        for(var channel:display.availableChannels()) {
            if(!(channel instanceof DirectServerChannelInfo direct))
                throw new IllegalArgumentException("Display profile requires direct fluorescence channels");
            channels.add(new IfDisplayProfile.Channel(direct.getChannel(),direct.getOriginalChannelName(),
                    direct.getColor()==null?0xffffff:direct.getColor(),direct.getMinDisplay(),direct.getMaxDisplay(),
                    display.selectedChannels().contains(channel)));
        }
        return new IfDisplayProfile(1,viewer.getGamma(),display.useGrayscaleLuts(),display.useInvertedBackground(),channels);
    }

    static void apply(QuPathViewer viewer, IfDisplayProfile profile) {
        profile.validateFor(viewer.getServer());
        var display=viewer.getImageDisplay();
        var targets=display.availableChannels().stream().filter(DirectServerChannelInfo.class::isInstance)
                .map(DirectServerChannelInfo.class::cast).toList();
        if(targets.size()!=profile.channels().size()) throw new IllegalArgumentException("Unexpected display channel layout");
        if(!GAMMA.containsKey(viewer)) {
            GAMMA.put(viewer,new GammaState(viewer.getGamma(),viewer.gammaProperty().isBound()));
            viewer.imageDataProperty().addListener(new javafx.beans.value.ChangeListener<>() {
                public void changed(javafx.beans.value.ObservableValue<? extends ImageData<BufferedImage>> o,
                                    ImageData<BufferedImage> before, ImageData<BufferedImage> after) {
                    var previous=GAMMA.remove(viewer);
                    if(previous!=null) {
                        viewer.gammaProperty().unbind(); viewer.setGamma(previous.value());
                        if(previous.bound()) viewer.gammaProperty().bind(PathPrefs.viewerGammaProperty());
                    }
                    viewer.imageDataProperty().removeListener(this);
                }
            });
        }
        viewer.gammaProperty().unbind(); viewer.setGamma(profile.gamma());
        display.setUseGrayscaleLuts(profile.grayscale()); display.setUseInvertedBackground(profile.inverted());
        for(var channel:profile.channels()) {
            var target=targets.stream().filter(c->c.getChannel()==channel.index()).findFirst().orElseThrow();
            int rgb=channel.color(); target.setLUTColor((rgb>>16)&255,(rgb>>8)&255,rgb&255);
            display.setMinMaxDisplay(target,(float)channel.minimum(),(float)channel.maximum());
            display.setChannelSelected(target,channel.selected());
        }
        display.saveChannelColorProperties(); viewer.repaintEntireImage();
    }

    /** Uses independent QuPath LUT objects: safe to render off-thread without reading mutable viewer state. */
    static BufferedImage render(ImageServer<BufferedImage> server, BufferedImage raw,
                                IfDisplayProfile profile, List<Integer> selectedIndices) {
        return prepare(server,profile).render(raw,selectedIndices);
    }

    /** One export-worker-local renderer; never share between threads or with a viewer. */
    static PreparedRenderer prepare(ImageServer<BufferedImage> server, IfDisplayProfile profile) {
        profile.validateFor(server);
        var imageData=new ImageData<>(server,ImageData.ImageType.FLUORESCENCE);
        List<DirectServerChannelInfo> channels=new ArrayList<>();
        for(var state:profile.channels()) {
            var channel=new DirectServerChannelInfo(imageData,state.index());
            int rgb=state.color(); channel.setLUTColor((rgb>>16)&255,(rgb>>8)&255,rgb&255);
            channel.setMinDisplay((float)state.minimum()); channel.setMaxDisplay((float)state.maximum()); channels.add(channel);
        }
        var mode=profile.grayscale() ? (profile.inverted()?ChannelDisplayMode.INVERTED_GRAYSCALE:ChannelDisplayMode.GRAYSCALE)
                : (profile.inverted()?ChannelDisplayMode.INVERTED_COLOR:ChannelDisplayMode.COLOR);
        return new PreparedRenderer(List.copyOf(channels),mode,profile.gamma()==1?null:gamma(profile.gamma()));
    }

    static final class PreparedRenderer {
        private final List<DirectServerChannelInfo> channels;
        private final ChannelDisplayMode mode;
        private final LookupOp gamma;
        private PreparedRenderer(List<DirectServerChannelInfo> channels,ChannelDisplayMode mode,LookupOp gamma) {
            this.channels=channels;this.mode=mode;this.gamma=gamma;
        }
        BufferedImage render(BufferedImage raw,List<Integer> selectedIndices) {
            return renderInto(raw,selectedIndices,null);
        }
        /** The caller must finish consuming the previous output before reusing it. */
        BufferedImage renderInto(BufferedImage raw,List<Integer> selectedIndices,BufferedImage output) {
            if(raw==output)throw new IllegalArgumentException("Output must not alias the raw source raster");
            if(output!=null && raw.getRaster().getDataBuffer()==output.getRaster().getDataBuffer())
                throw new IllegalArgumentException("Output must not share source pixel storage");
            var selected=channels.stream().filter(c->selectedIndices.contains(c.getChannel())).toList();
            var image=ImageDisplay.applyTransforms(raw,output,selected,mode);
            return gamma==null?image:gamma.filter(image,image);
        }
        /** Bounded LUT work for large exports; cancellation never interrupts a shared source reader. */
        BufferedImage renderCancellable(BufferedImage raw,List<Integer> selectedIndices,BufferedImage output,
                                        java.util.function.BooleanSupplier cancelled) {
            checkCancelled(cancelled);
            if(output!=null && raw.getRaster().getDataBuffer()==output.getRaster().getDataBuffer())
                throw new IllegalArgumentException("Output must not share source pixel storage");
            int width=raw.getWidth(),height=raw.getHeight();
            if(output==null || output.getWidth()!=width || output.getHeight()!=height
                    || output.getType()!=BufferedImage.TYPE_INT_RGB)
                output=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
            // At most 64 rows and about 256K pixels per transform (one row minimum).
            int rows=Math.max(1,Math.min(64,262144/width));
            BufferedImage stripe=null;
            for(int y=0;y<height;y+=rows) {
                checkCancelled(cancelled);
                int n=Math.min(rows,height-y);
                if(stripe!=null && stripe.getHeight()!=n)stripe=null;
                stripe=renderInto(raw.getSubimage(0,y,width,n),selectedIndices,stripe);
                checkCancelled(cancelled);
                // Both are packed RGB; avoid per-pixel ColorModel conversions in get/setRGB.
                output.getRaster().setDataElements(0,y,stripe.getRaster());
            }
            checkCancelled(cancelled);
            return output;
        }
        private static void checkCancelled(java.util.function.BooleanSupplier cancelled) {
            if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException("Display rendering cancelled");
        }
    }

    static LookupOp gamma(double gamma) {
        byte[] values=new byte[256];
        for(int i=0;i<256;i++) values[i]=(byte)ColorTools.do8BitRangeCheck(255*Math.pow(i/255.0,gamma));
        return new LookupOp(new ByteLookupTable(0,values),null);
    }
}
