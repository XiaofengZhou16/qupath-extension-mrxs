package io.github.xiaofengzhou.qupath.mrxs;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Run without the MRXS JAR on the classpath to isolate stock-viewer CSS initialization. */
public class IfStockViewerCssSmoke {
    public static void main(String[] args)throws Exception {
        if(args.length!=2 || !java.util.List.of("image-first","stage-first").contains(args[1]))
            throw new IllegalArgumentException("isolated-prefs image-first|stage-first");
        System.setProperty("java.util.prefs.PreferencesFactory",IfPanelSmoke.MemoryPreferencesFactory.class.getName());
        var done=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        Platform.startup(()->{
            try {
                Files.createDirectories(Path.of(args[0]));PathPrefs.userPathProperty().set(args[0]);
                var qupath=QuPathGUI.createHiddenInstance();var viewer=qupath.getViewer();
                var stage=qupath.getStage();stage.setWidth(1000);stage.setHeight(760);
                if(args[1].equals("stage-first")){stage.show();stage.getScene().getRoot().applyCss();stage.getScene().getRoot().layout();}
                var server=new WrappedBufferedImageServer("stock CSS synthetic fixture",new BufferedImage(256,256,BufferedImage.TYPE_BYTE_GRAY));
                var data=new ImageData<>(server,ImageData.ImageType.FLUORESCENCE);
                viewer.setImageData(data);
                if(args[1].equals("image-first"))stage.show();
                var annotation=PathObjects.createAnnotationObject(ROIs.createRectangleROI(20,20,100,100,ImagePlane.getDefaultPlane()));
                data.getHierarchy().addObject(annotation);viewer.setSelectedObject(annotation);
                var settle=new PauseTransition(Duration.seconds(2));
                settle.setOnFinished(e->{
                    try {
                        if(data.getHierarchy().getAnnotationObjects().size()!=1)throw new AssertionError("Annotation missing");
                        System.out.println("STOCK_CSS_PROBE_OK order="+args[1]+" screenshots=false");
                        viewer.resetImageData();server.close();stage.hide();
                    }catch(Throwable t){failure.set(t);}
                    finally{done.countDown();Platform.exit();}
                });settle.play();
            }catch(Throwable t){failure.set(t);done.countDown();Platform.exit();}
        });
        if(!done.await(45,TimeUnit.SECONDS)){System.err.println("Stock CSS probe timeout");System.exit(2);}
        if(failure.get()!=null){failure.get().printStackTrace();System.exit(1);}System.exit(0);
    }
}
