package io.github.xiaofengzhou.qupath.mrxs;

import qupath.lib.images.servers.ImageServer;
import qupath.lib.io.GsonTools;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;
import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadataNode;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.geom.Rectangle2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/** Source-pixel figure panels. DPI never resizes the image; labels never obscure source pixels. */
final class IfPublicationExport {
    record Options(int level, int dpi, double fontPoints, boolean allCombinations,
                   boolean scaleBar, double scaleBarMicrons) {
        Options {
            if(level<0 || dpi<72 || dpi>1200 || !Double.isFinite(fontPoints) || fontPoints<6 || fontPoints>18
                    || !Double.isFinite(scaleBarMicrons) || scaleBarMicrons<0)
                throw new IllegalArgumentException("Invalid publication export options");
        }
    }
    record Plan(int x,int y,int width,int height,double downsample,int estimatedWidth,int estimatedHeight,
                int combinations,long estimatedWorkingBytes) {}
    record Panel(BufferedImage image,int imageOffsetY,int legendHeight,int footerHeight,
                 double scaleBarMicrons,int scaleBarPixels,double drawnScaleBarMicrons) {}

    static Plan plan(ImageServer<BufferedImage> server, ROI roi, IfDisplayProfile profile, Options options) throws Exception {
        profile.validateFor(server);
        if(roi==null || !roi.isArea() || roi.isEmpty() || options.level()>=server.nResolutions())
            throw new IllegalArgumentException("Select a nonempty ROI and valid native level");
        if(roi.getZ()<0 || roi.getZ()>=server.nZSlices() || roi.getT()<0 || roi.getT()>=server.nTimepoints())
            throw new IllegalArgumentException("ROI plane outside image");
        int k=profile.selectedChannels().size();
        if(k<1 || k>8) throw new IllegalArgumentException("Select 1–8 display channels");
        double ds=server.getDownsampleForResolution(options.level());
        int x=(int)Math.max(0,Math.floor(roi.getBoundsX())), y=(int)Math.max(0,Math.floor(roi.getBoundsY()));
        int x2=(int)Math.min(server.getWidth(),Math.ceil(roi.getBoundsX()+roi.getBoundsWidth()));
        int y2=(int)Math.min(server.getHeight(),Math.ceil(roi.getBoundsY()+roi.getBoundsHeight()));
        int combinations=options.allCombinations() ? (1<<k)-1 : 1;
        var size=MrxsCombinationExport.validateExportSize(x2-x,y2-y,ds,combinations*2);
        if(size.outputWidth()<128 || size.outputHeight()<32)
            throw new IllegalArgumentException("ROI is too small for legible publication labels; use a larger ROI or finer native level");
        int fontPixels=Math.max(8,(int)Math.round(options.fontPoints()*options.dpi()/72.0));
        int usable=(int)size.outputWidth()-2*Math.max(8,fontPixels/2);
        if(usable<fontPixels*4)throw new IllegalArgumentException("DPI/font too large for this ROI width; use a larger ROI or smaller font/DPI");
        if(options.scaleBar() && !server.getPixelCalibration().hasPixelSizeMicrons())
            throw new IllegalArgumentException("A scale bar requires calibrated µm/pixel");
        if(options.scaleBar() && options.scaleBarMicrons()>0) {
            long bar=Math.round(options.scaleBarMicrons()/(server.getPixelCalibration().getPixelWidthMicrons()*ds));
            if(bar<2||bar>usable)throw new IllegalArgumentException("Scale bar does not fit at the chosen native level");
        }
        // Bound peak buffers conservatively (raw raster, LUT intermediates, clean+annotated RGB, mask).
        long bytes=Math.multiplyExact(size.outputPixels(), Math.addExact((long)server.nChannels()*8,40));
        if(bytes>Runtime.getRuntime().maxMemory()/2)
            throw new IllegalArgumentException("Estimated working buffers exceed half of the Java heap; reduce ROI or use a coarser native level");
        return new Plan(x,y,x2-x,y2-y,ds,(int)size.outputWidth(),(int)size.outputHeight(),combinations,bytes);
    }

    static Path export(ImageServer<BufferedImage> server, ROI roi, IfDisplayProfile profile, Options options,
                       Path output, BooleanSupplier cancelled, DoubleConsumer progress) throws Exception {
        var p=plan(server,roi,profile,options); check(cancelled);
        Files.createDirectory(output); // No overwrite, even after a previous failed run.
        Files.writeString(output.resolve("INCOMPLETE"),"Do not use this export until complete and this marker is removed.\n");
        var sourceUris=server.getURIs().stream().map(Object::toString).sorted().toList();
        var sourceFingerprint=IfSourceFingerprint.capture(sourceUris,cancelled);
        var request=RegionRequest.createInstance(server.getPath(),p.downsample(),p.x(),p.y(),p.width(),p.height(),roi.getZ(),roi.getT());
        var raw=server.readRegion(request); check(cancelled);
        if(raw==null) throw new java.io.IOException("Reader returned no pixels");
        // All combinations share precisely the same raster and ROI mask.
        boolean[] inside=new boolean[Math.multiplyExact(raw.getWidth(),raw.getHeight())];
        for(int y=0;y<raw.getHeight();y++) {
            check(cancelled);
            for(int x=0;x<raw.getWidth();x++) inside[y*raw.getWidth()+x]=roi.contains(p.x()+(x+0.5)*p.downsample(),p.y()+(y+0.5)*p.downsample());
        }
        var channels=profile.selectedChannels();
        var quality=IfDisplayQuality.measure(raw,inside,profile,cancelled);
        Files.writeString(output.resolve("display-qc.json"),GsonTools.getInstance(true).newBuilder().serializeNulls().create().toJson(quality));
        Files.writeString(output.resolve("display-qc.txt"),IfDisplayQuality.summary(quality));
        var masks=options.allCombinations()?MrxsCombinationExport.combinationMasks(channels.size()):List.of((1<<channels.size())-1);
        Files.writeString(output.resolve("display-profile.json"),GsonTools.getInstance(true).toJson(profile));
        Files.writeString(output.resolve("roi.json"),GsonTools.getInstance(true).toJson(IfGeometryExport.roi(roi)));
        var outputs=new ArrayList<Object>(); int sequence=0;
        var renderer=IfDisplaySettings.prepare(server,profile);
        BufferedImage renderBuffer=null;
        for(int mask:masks) {
            check(cancelled);
            var subset=new ArrayList<IfDisplayProfile.Channel>();
            for(int i=0;i<channels.size();i++) if((mask&(1<<i))!=0) subset.add(channels.get(i));
            var image=renderer.renderCancellable(raw,subset.stream().map(IfDisplayProfile.Channel::index).toList(),renderBuffer,cancelled);
            renderBuffer=image; // Previous combination has been written before this buffer is reused.
            for(int y=0;y<image.getHeight();y++) {
                check(cancelled);
                for(int x=0;x<image.getWidth();x++) if(!inside[y*image.getWidth()+x]) image.setRGB(x,y,0);
            }
            String prefix=String.format(Locale.ROOT,"%03d-mask-%d",++sequence,mask);
            Path clean=output.resolve(prefix+"-clean.png"), annotated=output.resolve(prefix+"-figure.png");
            writePng(image,clean,options.dpi(),cancelled); check(cancelled);
            var panel=decorate(image,subset,profile,options,server.getPixelCalibration().getPixelWidthMicrons()*p.downsample());
            writePng(panel.image(),annotated,options.dpi(),cancelled);
            var item=new LinkedHashMap<String,Object>();
            item.put("channels",subset.stream().map(IfDisplayProfile.Channel::name).toList());
            item.put("sourceChannelIndices",subset.stream().map(IfDisplayProfile.Channel::index).toList());
            item.put("cleanFile",clean.getFileName().toString()); item.put("figureFile",annotated.getFileName().toString());
            item.put("cleanSha256",digest(clean,cancelled)); item.put("figureSha256",digest(annotated,cancelled));
            item.put("figureWidth",panel.image().getWidth()); item.put("figureHeight",panel.image().getHeight());
            item.put("imageOffsetY",panel.imageOffsetY()); item.put("legendHeight",panel.legendHeight()); item.put("footerHeight",panel.footerHeight());
            item.put("scaleBarRequestedMicrons",panel.scaleBarMicrons()); item.put("scaleBarPixels",panel.scaleBarPixels());
            item.put("scaleBarDrawnMicrons",Double.isFinite(panel.drawnScaleBarMicrons())?panel.drawnScaleBarMicrons():null);
            outputs.add(item); progress.accept((double)sequence/masks.size());
        }
        var manifest=new LinkedHashMap<String,Object>();
        if(!java.util.Objects.equals(sourceFingerprint,IfSourceFingerprint.capture(sourceUris,cancelled)))
            throw new java.io.IOException("Source files changed during publication export; output remains incomplete");
        manifest.put("schemaVersion",1); manifest.put("status","complete"); manifest.put("createdAt",Instant.now().toString());
        manifest.put("software",IfSoftwareProvenance.snapshot());
        manifest.put("sourceServerClass",server.getClass().getName());
        manifest.put("sourceServerType",server.getServerType());
        manifest.put("sourceUris",sourceUris);
        manifest.put("sourceFileFingerprint",sourceFingerprint);
        manifest.put("roiJsonSha256",digest(output.resolve("roi.json"),cancelled));
        manifest.put("displayProfileJsonSha256",digest(output.resolve("display-profile.json"),cancelled));
        manifest.put("imageName",server.getMetadata().getName()); manifest.put("options",options); manifest.put("plan",p);
        manifest.put("profile",profile); manifest.put("sourceZ",roi.getZ()); manifest.put("sourceT",roi.getT());
        manifest.put("displayQuality",quality);
        manifest.put("displayQualityJsonSha256",digest(output.resolve("display-qc.json"),cancelled));
        manifest.put("displayQualityTextSha256",digest(output.resolve("display-qc.txt"),cancelled));
        manifest.put("rasterWidth",raw.getWidth()); manifest.put("rasterHeight",raw.getHeight());
        manifest.put("printImageWidthCm",raw.getWidth()*2.54/options.dpi());
        manifest.put("printImageHeightCm",raw.getHeight()*2.54/options.dpi());
        manifest.put("outputs",outputs);
        manifest.put("labelFont",Font.SANS_SERIF);
        manifest.put("labelFontStyle","plain");
        manifest.put("labelFontPixelSize",Math.max(8,(int)Math.round(options.fontPoints()*options.dpi()/72.0)));
        manifest.put("renderingJavaVersion",System.getProperty("java.version"));
        manifest.put("renderingOs",System.getProperty("os.name"));
        manifest.put("definitions",List.of("PNG pHYs DPI controls print size only; no image upsampling or sharpening",
                "Clean image = pseudocolor display composite, NOT raw quantitative intensities",
                "Source image pixels are copied without resizing into figure panels; labels and scale bar are outside the image",
                "Outside ROI is black; ROI holes preserved; identical selected-channel settings in every combination",
                "Display uses QuPath color transforms and gamma, excludes monitor ICC transforms and all viewer overlays",
                "Optional source fingerprint samples file head/middle/tail and records size/mtime; not full-pixel authentication. ROI/profile hashes identify exported settings files",
                "Compare study groups using controlled, consistent display settings; retain raw data for analysis"));
        check(cancelled);
        var json=GsonTools.getInstance(true).newBuilder().serializeNulls().create().toJson(manifest);
        Path path=output.resolve("manifest.json"); Files.writeString(path,json); Files.delete(output.resolve("INCOMPLETE")); return path;
    }

    static Panel decorate(BufferedImage image,List<IfDisplayProfile.Channel> channels,IfDisplayProfile profile,
                          Options options,double micronsPerPixel) {
        int fontSize=Math.max(8,(int)Math.round(options.fontPoints()*options.dpi()/72.0));
        var font=new Font(Font.SANS_SERIF,Font.PLAIN,fontSize);
        var measure=image.createGraphics(); measure.setFont(font); var fm=measure.getFontMetrics();
        int margin=Math.max(8,fontSize/2), swatch=Math.max(6,fontSize/2), lineHeight=fm.getHeight()+margin/2;
        int usable=image.getWidth()-2*margin;
        if(usable<fontSize*4) { measure.dispose(); throw new IllegalArgumentException("DPI/font too large for this ROI width"); }
        var lines=new ArrayList<LegendLine>();
        for(var channel:channels) {
            String name=channel.name(); StringBuilder line=new StringBuilder();
            for(int cp:name.codePoints().toArray()) {
                String next=line.toString()+new String(Character.toChars(cp));
                if(fm.stringWidth(next)+swatch+margin>usable && !line.isEmpty()) {
                    lines.add(new LegendLine(line.toString(),channel.color())); line.setLength(0);
                }
                line.appendCodePoint(cp);
            }
            lines.add(new LegendLine(line.toString(),channel.color()));
        }
        measure.dispose();
        int[] legendX=new int[lines.size()],legendRows=new int[lines.size()];int cursor=margin,row=0;
        for(int i=0;i<lines.size();i++) {
            int itemWidth=swatch+margin+fm.stringWidth(lines.get(i).text());
            if(cursor>margin && cursor+itemWidth>image.getWidth()-margin){cursor=margin;row++;}
            legendX[i]=cursor;legendRows[i]=row;cursor+=itemWidth+2*margin;
        }
        int header=Math.addExact(2*margin,Math.multiplyExact(lineHeight,row+1));
        int footer=options.scaleBar()?2*margin+2*lineHeight:0;
        long canvasPixels=(long)image.getWidth()*(image.getHeight()+header+footer);
        if(canvasPixels>35_000_000L || canvasPixels*4>Runtime.getRuntime().maxMemory()/4)
            throw new IllegalArgumentException("Annotated figure exceeds memory limit; reduce ROI or font/DPI");
        double scale=0; int scalePixels=0;
        if(options.scaleBar()) {
            if(!(micronsPerPixel>0) || !Double.isFinite(micronsPerPixel)) throw new IllegalArgumentException("Missing pixel calibration");
            scale=options.scaleBarMicrons()>0?options.scaleBarMicrons():niceLength(usable*micronsPerPixel/4);
            scalePixels=(int)Math.round(scale/micronsPerPixel);
            if(scalePixels<2 || scalePixels>usable) throw new IllegalArgumentException("Scale bar does not fit this image at the chosen native level");
        }
        var canvas=new BufferedImage(image.getWidth(),image.getHeight()+header+footer,BufferedImage.TYPE_INT_RGB);
        var g=canvas.createGraphics();
        try {
            g.setColor(Color.BLACK);g.fillRect(0,0,canvas.getWidth(),canvas.getHeight());
            g.drawImage(image,0,header,null); // exact integer translation; never resize/obscure the image
            g.setFont(font);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            for(int i=0;i<lines.size();i++) {
                var line=lines.get(i);int baseline=margin+g.getFontMetrics().getAscent()+legendRows[i]*lineHeight;
                g.setColor(profile.grayscale()?Color.WHITE:new Color(line.color()));
                g.fillRect(legendX[i],baseline-swatch,swatch,swatch);
                g.setColor(Color.WHITE);g.drawString(line.text(),legendX[i]+swatch+margin,baseline);
            }
            if(options.scaleBar()) {
                String label=new java.text.DecimalFormat("0.####",java.text.DecimalFormatSymbols.getInstance(Locale.ROOT)).format(scale)+" µm";
                if(g.getFontMetrics().stringWidth(label)>usable) throw new IllegalArgumentException("Scale label too wide; reduce font or DPI");
                int top=header+image.getHeight()+margin;
                g.setColor(Color.WHITE);g.fill(new Rectangle2D.Double(margin,top,scalePixels,Math.max(2,fontSize/8.0)));
                g.drawString(label,margin,top+lineHeight+g.getFontMetrics().getAscent());
            }
        } finally {g.dispose();}
        return new Panel(canvas,header,header,footer,scale,scalePixels,options.scaleBar()?scalePixels*micronsPerPixel:0);
    }
    private record LegendLine(String text,int color) {}
    private static double niceLength(double value) {
        double base=Math.pow(10,Math.floor(Math.log10(value)));
        double normalized=value/base;
        return (normalized>=5?5:normalized>=2?2:1)*base;
    }
    static void writePng(BufferedImage image,Path path,int dpi) throws Exception {
        writePng(image,path,dpi,()->false);
    }
    static void writePng(BufferedImage image,Path path,int dpi,BooleanSupplier cancelled) throws Exception {
        check(cancelled);
        var writers=ImageIO.getImageWritersByFormatName("PNG");
        if(!writers.hasNext()) throw new java.io.IOException("PNG writer unavailable");
        var writer=writers.next();
        var aborted=new java.util.concurrent.atomic.AtomicBoolean();
        writer.addIIOWriteProgressListener(new javax.imageio.event.IIOWriteProgressListener(){
            private void poll(javax.imageio.ImageWriter source){
                if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())source.abort();
            }
            public void imageStarted(javax.imageio.ImageWriter source,int index){poll(source);}
            public void imageProgress(javax.imageio.ImageWriter source,float percent){poll(source);}
            public void imageComplete(javax.imageio.ImageWriter source){poll(source);}
            public void thumbnailStarted(javax.imageio.ImageWriter source,int imageIndex,int thumbnailIndex){poll(source);}
            public void thumbnailProgress(javax.imageio.ImageWriter source,float percent){poll(source);}
            public void thumbnailComplete(javax.imageio.ImageWriter source){poll(source);}
            public void writeAborted(javax.imageio.ImageWriter source){aborted.set(true);}
        });
        try(var stream=ImageIO.createImageOutputStream(path.toFile())) {
            var params=writer.getDefaultWriteParam();
            var metadata=writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image),params);
            var root=new IIOMetadataNode("javax_imageio_png_1.0"); var phys=new IIOMetadataNode("pHYs");
            String ppm=Long.toString(Math.round(dpi/0.0254));
            phys.setAttribute("pixelsPerUnitXAxis",ppm);phys.setAttribute("pixelsPerUnitYAxis",ppm);phys.setAttribute("unitSpecifier","meter");
            root.appendChild(phys);metadata.mergeTree("javax_imageio_png_1.0",root);
            writer.setOutput(stream);writer.write(null,new IIOImage(image,null,metadata),params);
            check(cancelled);
            if(aborted.get())throw new CancellationException("PNG encoding aborted");
        } finally {writer.dispose();}
    }
    private static String digest(Path path,BooleanSupplier cancelled) throws Exception {
        check(cancelled);
        var sha=MessageDigest.getInstance("SHA-256");
        try(var in=Files.newInputStream(path)) {byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))>=0){check(cancelled);sha.update(buffer,0,n);}}
        check(cancelled);
        return HexFormat.of().formatHex(sha.digest());
    }
    private static void check(BooleanSupplier cancelled) {
        if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())throw new CancellationException();
    }
}
