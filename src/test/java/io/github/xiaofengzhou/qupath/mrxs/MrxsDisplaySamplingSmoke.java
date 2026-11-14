package io.github.xiaofengzhou.qupath.mrxs;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.io.GsonTools;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Window/cache dimensions only. Never reads screen, canvas pixels or image snapshots. */
public class MrxsDisplaySamplingSmoke {
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("slide.mrxs isolated-user-directory new-output-directory");
        Path output=Path.of(args[2]);Files.createDirectory(output);
        System.setProperty("java.util.prefs.PreferencesFactory",IfPanelSmoke.MemoryPreferencesFactory.class.getName());
        var done=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        Platform.startup(()->{
            try {
                Files.createDirectories(Path.of(args[1]));PathPrefs.userPathProperty().set(args[1]);
                var qupath=QuPathGUI.createHiddenInstance();var viewer=qupath.getViewer();
                var server=new MrxsImageServer(Path.of(args[0]).toUri());
                viewer.setImageData(new ImageData<>(server,ImageData.ImageType.FLUORESCENCE));
                qupath.getStage().setWidth(1000);qupath.getStage().setHeight(760);qupath.getStage().show();
                viewer.setCenterPixelLocation(33280,82432);viewer.setDownsampleFactor(3.2,-1,-1);
                boolean[] configured={false};
                var wait=new PauseTransition(Duration.seconds(1));wait.setOnFinished(e->{
                    if(!configured[0]){configured[0]=true;viewer.setDownsampleFactor(4,-1,-1);viewer.setCenterPixelLocation(33280,82432);
                        wait.setDuration(Duration.millis(500));wait.playFromStart();return;}
                    try {
                        var report=MrxsDisplaySampling.capture(viewer);var diagnostic=new LinkedHashMap<String,Object>();
                        diagnostic.put("sampling",report);diagnostic.put("qupathSourceCommit","04ccfa4fb7d43e9b566393e08e83690b72248d44");
                        var buffer=bufferSize(viewer,"imgBuffer");var cache=bufferSize(viewer,"imgCache");
                        diagnostic.put("actualImageBufferDimensions",buffer);diagnostic.put("actualImageCacheDimensions",cache);
                        if(buffer.get("width")!=(int)Math.ceil(report.logicalWidth())||buffer.get("height")!=(int)Math.ceil(report.logicalHeight()))
                            throw new IllegalStateException("Unexpected main viewer buffer geometry");
                        var profile=IfDisplaySettings.capture(viewer);
                        PathPrefs.viewerInterpolateBilinearProperty().set(true);
                        for(double rotation:new double[]{0,0.37}) {
                            viewer.setRotation(rotation);viewer.setDownsampleFactor(3.2,-1,-1);viewer.setCenterPixelLocation(33280,82432);
                            MrxsClarityControls.snapToNativeZoom(viewer);checkCenter(viewer);
                            viewer.setDownsampleFactor(3.2,-1,-1);viewer.setCenterPixelLocation(33280,82432);
                            MrxsClarityControls.applyHighClarity(viewer);checkCenter(viewer);
                            if(PathPrefs.viewerInterpolateBilinearProperty().get())throw new IllegalStateException("High clarity did not disable interpolation");
                            MrxsClarityControls.restoreInterpolation();
                            if(!PathPrefs.viewerInterpolateBilinearProperty().get())throw new IllegalStateException("Interpolation did not restore");
                        }
                        if(!profile.equals(IfDisplaySettings.capture(viewer)))throw new IllegalStateException("Clarity changed fluorescence display settings");
                        MrxsClarityControls.applyHighClarity(viewer);
                        var other=new WrappedBufferedImageServer("non-MRXS",new BufferedImage(32,32,BufferedImage.TYPE_BYTE_GRAY));
                        viewer.setImageData(new ImageData<>(other,ImageData.ImageType.FLUORESCENCE));
                        MrxsClarityControls.restoreIfNotMrxs(viewer);
                        if(!PathPrefs.viewerInterpolateBilinearProperty().get())throw new IllegalStateException("Non-MRXS interpolation not restored");
                        diagnostic.put("centerPreservedUnrotatedAndRotated",true);diagnostic.put("displayProfileUnchanged",true);
                        diagnostic.put("nonMrxsInterpolationRestored",true);diagnostic.put("screenshots",false);
                        Files.writeString(output.resolve("sampling-runtime.json"),GsonTools.getInstance(true).toJson(diagnostic));
                        System.out.println("MRXS_DISPLAY_SAMPLING_GUI_OK "+GsonTools.getInstance().toJson(diagnostic));
                        viewer.resetImageData();server.close();other.close();qupath.getStage().hide();
                    }catch(Throwable t){failure.set(t);}finally{done.countDown();Platform.exit();}
                });wait.play();
            }catch(Throwable t){failure.set(t);done.countDown();Platform.exit();}
        });
        if(!done.await(50,TimeUnit.SECONDS)){System.err.println("Display sampling GUI timeout");System.exit(2);}
        if(failure.get()!=null){failure.get().printStackTrace();System.exit(1);}System.exit(0);
    }
    private static void checkCenter(QuPathViewer viewer) {
        if(Math.abs(viewer.getCenterPixelX()-33280)>1e-6||Math.abs(viewer.getCenterPixelY()-82432)>1e-6)
            throw new IllegalStateException("Native zoom shifted image center");
    }
    private static Map<String,Integer> bufferSize(QuPathViewer viewer,String name)throws Exception {
        // Diagnostic-only reflection reads dimensions, never pixel content. Production code does not use private fields.
        var field=QuPathViewer.class.getDeclaredField(name);field.setAccessible(true);
        var buffer=(BufferedImage)field.get(viewer);
        if(buffer==null)throw new IllegalStateException("Viewer buffer not initialized: "+name);
        return Map.of("width",buffer.getWidth(),"height",buffer.getHeight());
    }
}
