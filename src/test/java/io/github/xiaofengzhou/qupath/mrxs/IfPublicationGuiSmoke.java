package io.github.xiaofengzhou.qupath.mrxs;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.*;
import javafx.util.Duration;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Source-pixel publication export lifecycle. No screen/canvas capture. */
public class IfPublicationGuiSmoke {
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("slide isolated-user-dir new-output-parent");
        var output=Path.of(args[2]);Files.createDirectory(output);
        System.setProperty("java.util.prefs.PreferencesFactory",IfPanelSmoke.MemoryPreferencesFactory.class.getName());
        var done=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        Platform.startup(()->{
            try {
                Files.createDirectories(Path.of(args[1]));PathPrefs.userPathProperty().set(args[1]);
                var qupath=QuPathGUI.createHiddenInstance();var viewer=qupath.getViewer();
                var server=new MrxsImageServer(Path.of(args[0]).toUri());
                var data=new ImageData<>(server,ImageData.ImageType.FLUORESCENCE);viewer.setImageData(data);
                var roi=PathObjects.createAnnotationObject(ROIs.createRectangleROI(32768,81920,2048,2048,ImagePlane.getDefaultPlane()));
                data.getHierarchy().addObject(roi);viewer.setSelectedObject(roi);
                var original=IfDisplaySettings.capture(viewer);
                new IfDisplayPanel(qupath,owner->output).show();
                var panel=Window.getWindows().stream().filter(w->w instanceof Stage s&&s.getTitle().startsWith("IF display &"))
                        .map(Stage.class::cast).findFirst().orElseThrow();
                var root=panel.getScene().getRoot();root.applyCss();root.layout();
                var tabs=(TabPane)root.lookup(".tab-pane");tabs.getSelectionModel().select(1);root.applyCss();root.layout();
                var run=(Button)root.lookup("#if-publication-run");var cancel=(Button)root.lookup("#if-publication-cancel");
                var status=(Label)root.lookup("#if-publication-status");
                int[] phase={0};Path[] interrupted={null};long start=System.nanoTime();
                var timer=new PauseTransition(Duration.millis(50));
                timer.setOnFinished(e->{
                    boolean finished=false;
                    try {
                        if((System.nanoTime()-start)/1e9>40)throw new IllegalStateException("Export timeout: "+status.getText());
                        if(status.getText().startsWith("Failed"))throw new IllegalStateException(status.getText());
                        if(phase[0]==0) {
                            try(var paths=Files.walk(output)){if(paths.noneMatch(p->p.toString().endsWith(".png"))){timer.playFromStart();return;}}
                            try(var dirs=Files.list(output)){interrupted[0]=dirs.filter(Files::isDirectory).findFirst().orElseThrow();}
                            if(status.getText().startsWith("Complete:"))throw new IllegalStateException("Missed cancellation window");
                            cancel.fire();if(!run.isDisabled())throw new IllegalStateException("Run re-enabled before worker cleanup");
                            phase[0]=1;
                        } else if(phase[0]==1 && !run.isDisabled()) {
                            if(!status.getText().startsWith("Cancelled")||!Files.exists(interrupted[0].resolve("INCOMPLETE"))
                                    ||Files.exists(interrupted[0].resolve("manifest.json")))throw new IllegalStateException("Interrupted output incorrectly complete");
                            var retry=PathObjects.createAnnotationObject(ROIs.createRectangleROI(32768,81920,1024,1024,ImagePlane.getDefaultPlane()));
                            data.getHierarchy().addObject(retry);viewer.setSelectedObject(retry);phase[0]=2;run.fire();
                        } else if(phase[0]==2 && status.getText().startsWith("Complete:") && !run.isDisabled()) {
                            Path complete;try(var dirs=Files.list(output)){complete=dirs.filter(Files::isDirectory).filter(p->!p.equals(interrupted[0])).findFirst().orElseThrow();}
                            var manifest=com.google.gson.JsonParser.parseString(Files.readString(complete.resolve("manifest.json"))).getAsJsonObject();
                            if(Files.exists(complete.resolve("INCOMPLETE"))||manifest.get("rasterWidth").getAsInt()!=1024
                                    ||manifest.get("rasterHeight").getAsInt()!=1024)throw new IllegalStateException("Wrong completion or native dimensions");
                            try(var files=Files.list(complete)){if(files.filter(p->p.toString().endsWith(".png")).count()!=30)throw new IllegalStateException("Expected 15 clean/figure pairs");}
                            if(!Files.exists(interrupted[0].resolve("INCOMPLETE")))throw new IllegalStateException("Retry changed cancelled output");
                            if(!original.equals(IfDisplaySettings.capture(viewer)))throw new IllegalStateException("Export altered display settings");
                            panel.fireEvent(new WindowEvent(panel,WindowEvent.WINDOW_CLOSE_REQUEST));panel.close();viewer.resetImageData();server.close();
                            System.out.println("IF_PUBLICATION_GUI_OK cancelAfterPng=true retry=true native1024=true pngCount=30 displayUnchanged=true screenshots=false");finished=true;
                        }
                        if(!finished)timer.playFromStart();
                    }catch(Throwable t){failure.set(t);finished=true;}
                    finally{if(finished){done.countDown();Platform.exit();}}
                });
                run.fire();timer.play();
            }catch(Throwable t){failure.set(t);done.countDown();Platform.exit();}
        });
        if(!done.await(55,TimeUnit.SECONDS)){System.err.println("Publication GUI watchdog");System.exit(2);}
        if(failure.get()!=null){failure.get().printStackTrace();System.exit(1);}System.exit(0);
    }
}
