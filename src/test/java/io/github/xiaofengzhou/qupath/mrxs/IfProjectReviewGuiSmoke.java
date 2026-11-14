package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javafx.application.Platform;
import javafx.animation.*;
import javafx.util.Duration;
import javafx.scene.control.*;
import javafx.stage.*;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.io.GsonTools;

/** Isolated project GUI reopen and real Analyze button, native folder chooser substituted. */
public class IfProjectReviewGuiSmoke {
    public static void main(String[] args)throws Exception {
        Path projectFile=Path.of(args[0]),baseline=Path.of(args[1]),output=Path.of(args[2]);Files.createDirectory(output);
        byte[] projectBytes=Files.readAllBytes(projectFile);
        System.setProperty("java.util.prefs.PreferencesFactory",IfPanelSmoke.MemoryPreferencesFactory.class.getName());
        var done=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        Platform.startup(()->{try{
            Path prefs=Files.createDirectory(output.resolve("prefs"));PathPrefs.userPathProperty().set(prefs.toString());
            var q=QuPathGUI.createHiddenInstance();var project=qupath.lib.projects.ProjectIO.loadProject(projectFile.toFile(),java.awt.image.BufferedImage.class);
            Thread.currentThread().setUncaughtExceptionHandler((thread,problem)->failure.compareAndSet(null,problem));
            q.getStage().setWidth(1000);q.getStage().setHeight(760);q.getStage().show();
            q.getStage().getScene().getRoot().applyCss();q.getStage().getScene().getRoot().layout();
            q.setProject(project);if(!q.openImageEntry(project.getImageList().getFirst()))throw new AssertionError("GUI image entry did not open");
            var data=q.getImageData();if(data==null||!(data.getServer() instanceof MrxsImageServer))throw new AssertionError("Wrong restored reader");
            var group=data.getHierarchy().getAnnotationObjects().stream().filter(o->o.getMetadata().containsKey(IfHistoricalReview.LINEAGE)).findFirst().orElseThrow();
            var original=IfCellReview.capture(group);
            var lineage=com.google.gson.JsonParser.parseString(group.getMetadata().get(IfHistoricalReview.LINEAGE)).getAsJsonObject();
            var profile=GsonTools.getInstance().fromJson(lineage.get("originalProfile"),IfAnalysisProfile.class);
            q.getViewer().setSelectedObject(group);new IfAnalysisPanel(q,owner->output).show(profile);
            Stage stage=null;for(var w:Window.getWindows())if(w instanceof Stage s&&s.getTitle().startsWith("Multiplex IF analysis"))stage=s;
            if(stage==null)throw new AssertionError("Review panel missing");var panel=stage;var root=panel.getScene().getRoot();
            var compartment=group.getDescendantObjects(null).stream().filter(o->o.getMetadata().containsKey(IfCellReview.PAIR)).findFirst().orElseThrow();
            q.getViewer().setSelectedObject(compartment);
            ((Button)root.lookup("#if-analysis-select-review-group")).fire();
            if(data.getHierarchy().getSelectionModel().getSelectedObject()!=group)throw new AssertionError("Owning-group navigation failed");
            var run=(Button)root.lookup("#if-analysis-run");var status=(Label)root.lookup("#if-analysis-status");
            run.fire();var timer=new Timeline();long start=System.nanoTime();
            timer.getKeyFrames().add(new KeyFrame(Duration.millis(100),e->{try{
                if(failure.get()!=null)throw new AssertionError("Uncaught FX exception",failure.get());
                if(System.nanoTime()-start>TimeUnit.SECONDS.toNanos(30))throw new AssertionError("Remeasure timeout: "+status.getText());
                if(run.isDisabled())return;
                List<Path> runs;try(var files=Files.list(output)){runs=files.filter(p->p.getFileName().toString().startsWith("if-analysis-")).toList();}
                if(runs.size()!=1)throw new AssertionError("Expected one new analysis: "+status.getText());var result=runs.getFirst();
                if(Files.exists(result.resolve("INCOMPLETE"))||!Files.isRegularFile(result.resolve("manifest.json")))throw new AssertionError("Analysis incomplete: "+status.getText());
                for(String name:List.of("cells.csv","combinations.csv","excluded-cells.csv"))if(!Arrays.equals(Files.readAllBytes(baseline.resolve(name)),Files.readAllBytes(result.resolve(name))))throw new AssertionError("GUI remeasurement changed "+name);
                if(!original.provenance().equals(IfCellReview.capture(group).provenance()))throw new AssertionError("Review lineage changed");
                if(!Arrays.equals(projectBytes,Files.readAllBytes(projectFile)))throw new AssertionError("Project definition changed");
                var report=Map.of("projectGuiReopened",true,"mrxsReaderRestored",true,"reviewModeRemeasured",true,"matchingCsvFiles",3,"projectDefinitionUnchanged",true,"screenshots",false,"limitations","Synthetic contours on real MRXS pixels; programmed UI events and injected output chooser, not manual-pointer usability or AI accuracy.");
                Files.writeString(output.resolve("verification.json"),GsonTools.getInstance(true).toJson(report));
                panel.close();q.getViewer().resetImageData();data.getServer().close();q.getStage().hide();timer.stop();System.out.println("IF_PROJECT_REVIEW_GUI_OK "+GsonTools.getInstance().toJson(report));done.countDown();Platform.exit();
            }catch(Throwable t){failure.set(t);timer.stop();done.countDown();Platform.exit();}}));timer.setCycleCount(Timeline.INDEFINITE);timer.play();
        }catch(Throwable t){failure.set(t);done.countDown();Platform.exit();}});
        if(!done.await(45,TimeUnit.SECONDS))System.exit(2);if(failure.get()!=null){failure.get().printStackTrace();System.exit(1);}System.exit(0);
    }
}
