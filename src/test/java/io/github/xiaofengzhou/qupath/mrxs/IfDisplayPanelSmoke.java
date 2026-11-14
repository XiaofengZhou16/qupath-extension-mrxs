package io.github.xiaofengzhou.qupath.mrxs;

import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObjects;
import qupath.lib.roi.ROIs;
import qupath.lib.regions.ImagePlane;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Live control and gamma-isolation checks, with no screenshot or persisted preferences. */
public class IfDisplayPanelSmoke {
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("slide.mrxs isolated-user-directory");
        System.setProperty("java.util.prefs.PreferencesFactory",IfPanelSmoke.MemoryPreferencesFactory.class.getName());
        var failure=new AtomicReference<Throwable>();var done=new CountDownLatch(1);
        Platform.startup(()->{
            try {
                Files.createDirectories(Path.of(args[1]));PathPrefs.userPathProperty().set(args[1]);
                var qupath=QuPathGUI.createHiddenInstance();var viewer=qupath.getViewer();
                var server=new MrxsImageServer(Path.of(args[0]).toUri());var data=new ImageData<>(server,ImageData.ImageType.FLUORESCENCE);
                viewer.setImageData(data);double originalGamma=viewer.getGamma();boolean originalBinding=viewer.gammaProperty().isBound();
                var annotation=PathObjects.createAnnotationObject(ROIs.createRectangleROI(32768,81920,1024,1024,ImagePlane.getDefaultPlane()));
                data.getHierarchy().addObject(annotation);viewer.setSelectedObject(annotation);
                var originalColors=server.getMetadata().getChannels();
                new IfDisplayPanel(qupath).show();
                var panel=Window.getWindows().stream().filter(w->w instanceof Stage s&&s.getTitle().startsWith("IF display &"))
                        .map(Stage.class::cast).findFirst().orElseThrow();
                var root=panel.getScene().getRoot();root.applyCss();root.layout();
                ((TextField)root.lookup("#if-display-gamma")).setText("1.7");
                root.lookupAll(".color-picker").stream().map(n->(javafx.scene.control.ColorPicker)n).findFirst().orElseThrow()
                        .setValue(javafx.scene.paint.Color.rgb(110,180,210));
                var refresh=root.lookupAll(".button").stream().filter(n->n instanceof Button b&&b.getText().startsWith("Preview selected"))
                        .map(Button.class::cast).findFirst().orElseThrow();
                for(int i=0;i<100;i++)refresh.fire();
                var wait=new PauseTransition(Duration.seconds(2));wait.setOnFinished(e->{
                    try {
                        if(Math.abs(viewer.getGamma()-1.7)>1e-9)throw new IllegalStateException("Live gamma control did not update viewer");
                        var profile=IfDisplaySettings.capture(viewer);
                        IfDisplaySettings.apply(viewer,profile);
                        if(!profile.equals(IfDisplaySettings.capture(viewer)))throw new IllegalStateException("Display snapshot/apply roundtrip mismatch");
                        if(!originalColors.equals(server.getMetadata().getChannels()))throw new IllegalStateException("Display changed source metadata colors");
                        root.applyCss();root.layout();
                        int previews=root.lookupAll(".image-view").size();
                        if(previews<2)throw new IllegalStateException("Synchronized source previews not populated");
                        var qc=((javafx.scene.control.Label)root.lookup("#if-display-preview-qc")).getText();
                        if(!qc.contains("bounding-box pixels:")||!qc.contains("below min")||!qc.contains("above max")
                                ||!qc.contains("Export has separate ROI-masked QC"))
                            throw new IllegalStateException("Preview range QC missing or incorrectly scoped: "+qc);
                        var other=new WrappedBufferedImageServer("non-MRXS",new BufferedImage(32,32,BufferedImage.TYPE_BYTE_GRAY));
                        viewer.setImageData(new ImageData<>(other,ImageData.ImageType.FLUORESCENCE));
                        if(Math.abs(viewer.getGamma()-originalGamma)>1e-9||viewer.gammaProperty().isBound()!=originalBinding)
                            throw new IllegalStateException("Gamma leaked into the non-MRXS image");
                        panel.fireEvent(new javafx.stage.WindowEvent(panel,javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));panel.close();
                        viewer.resetImageData();server.close();other.close();
                        System.out.println("IF_DISPLAY_PANEL_SMOKE_OK rapidRefreshRequests=100 liveGamma=true profiles=true previewQC=true previews="+previews+" rawMetadataUnchanged=true nonMrxsGammaRestored=true screenshots=false");
                    }catch(Throwable t){failure.set(t);}finally{done.countDown();Platform.exit();}
                });wait.play();
            }catch(Throwable t){failure.set(t);done.countDown();Platform.exit();}
        });
        if(!done.await(45,TimeUnit.SECONDS)){System.err.println("Display GUI test timeout");System.exit(2);}
        if(failure.get()!=null){failure.get().printStackTrace();System.exit(1);}System.exit(0);
    }
}
