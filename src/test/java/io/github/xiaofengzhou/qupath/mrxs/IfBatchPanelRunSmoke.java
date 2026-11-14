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
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObjects;
import qupath.lib.roi.ROIs;
import qupath.lib.regions.ImagePlane;
import qupath.lib.io.GsonTools;

public class IfBatchPanelRunSmoke {
 public static void main(String[] args)throws Exception {
  Path output=Path.of(args[2]);Files.createDirectory(output);
  var server=new MrxsImageServer(Path.of(args[0]).toUri());
  var data=new ImageData<>(server,ImageData.ImageType.FLUORESCENCE);
  for(int i=0;i<4;i++) {
   var a=PathObjects.createAnnotationObject(ROIs.createRectangleROI(32768+i*4096,81920,2048,2048,ImagePlane.getDefaultPlane()));
   if(i!=2)a.setName("ROI-"+i);if(i==3)a.getMetadata().put(IfCellReview.PAIR,"review-cell");data.getHierarchy().addObject(a);
  }
  Path projectDir=Files.createDirectory(output.resolve("project"));
  var project=qupath.lib.projects.Projects.createProject(projectDir.toFile(),java.awt.image.BufferedImage.class);
  var entry=project.addImage(server.getBuilder());entry.setImageName("batch selector fixture");entry.saveImageData(data);project.syncChanges();
  var done=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
  System.setProperty("java.util.prefs.PreferencesFactory",IfPanelSmoke.MemoryPreferencesFactory.class.getName());
  var phase=new AtomicInteger();var batchRoot=new AtomicReference<Path>();var cancelNanos=new AtomicLong();var cancelMillis=new AtomicReference<Double>();
  Platform.startup(()->{try {
   PathPrefs.userPathProperty().set(Files.createDirectory(output.resolve("prefs")).toString());
   var q=QuPathGUI.createHiddenInstance();q.setProject(project);q.getViewer().setImageData(data);q.getStage().show();
   var channels=server.getMetadata().getChannels().stream().map(c->c.getName()).toList();
   var profile=new IfAnalysisProfile(1,0,List.of(new IfAnalysisProfile.Marker(1,channels.get(1),30,30,30)));
   IfBatchPanel.show(q,q.getStage(),profile,new IfInstanSeg.Config(Path.of(args[1]),"mps",Integer.getInteger("if.batch.workers",1),512,32,List.of(0,1,2)),channels,null,false,IfBatchPanelRunSmoke.class.getClassLoader(),(window,resuming)->resuming?batchRoot.get():output);
   var stage=Window.getWindows().stream().filter(w->w instanceof Stage s&&s.getTitle().startsWith("IF project batch")).map(Stage.class::cast).findFirst().orElseThrow();
   var root=stage.getScene().getRoot();root.applyCss();root.layout();
   var scan=(Button)root.lookup("#if-batch-scan");scan.fire();
   if(!scan.isDisabled())throw new AssertionError("Scan must disable duplicate actions");
   var timer=new Timeline();timer.getKeyFrames().add(new KeyFrame(Duration.millis(100),e->{try {
    var startButton=(Button)root.lookup("#if-batch-start");
    var cancelButton=(Button)root.lookup("#if-batch-cancel");
    var status=(Label)root.lookup("#if-batch-status");
    if(phase.get()==0) {
     if(scan.isDisabled())return;
     var list=(ListView<?>)root.lookup("#if-batch-rois");
     if(list.getItems().size()!=2)throw new AssertionError("Expected two saved ROI choices");
     list.getSelectionModel().selectAll();startButton.fire();phase.set(1);return;
    }
    if(phase.get()==1) {
     double p=((ProgressBar)root.lookup("#if-batch-progress")).getProgress();
     if(!startButton.isDisabled())throw new AssertionError("Analysis ended before mid-inference cancellation: "+status.getText());
     if(p<=0||p>=1)return;
     cancelNanos.set(System.nanoTime());cancelButton.fire();
     if(!startButton.isDisabled()||!cancelButton.isDisabled())throw new AssertionError("Cancellation prematurely enables controls");
     phase.set(2);return;
    }
    if(phase.get()==2) {
     if(startButton.isDisabled())return;
     cancelMillis.set((System.nanoTime()-cancelNanos.get())/1e6);
     try(var children=Files.list(output)){batchRoot.set(children.filter(p->p.getFileName().toString().startsWith("if-batch-")).findFirst().orElseThrow());}
     var journal=GsonTools.getInstance().fromJson(Files.readString(batchRoot.get().resolve("queue.json")),IfBatchQueue.Journal.class);
     if(journal.attempts().size()!=1||!journal.attempts().getFirst().status().equals("cancelled"))throw new AssertionError("Cancellation did not stop next task: "+journal);
     ((Button)root.lookup("#if-batch-resume")).fire();phase.set(3);return;
    }
    if(startButton.isDisabled())return;
    var journal=GsonTools.getInstance().fromJson(Files.readString(batchRoot.get().resolve("queue.json")),IfBatchQueue.Journal.class);
    if(journal.attempts().size()!=3||!journal.attempts().getFirst().status().equals("cancelled")
      ||journal.attempts().stream().filter(a->a.status().equals("complete")).count()!=2)throw new AssertionError("Resume failed: "+journal+" UI="+status.getText());
    for(var attempt:journal.attempts())if(attempt.status().equals("complete")) {
     var item=journal.items().stream().filter(i->i.id().equals(attempt.task())).findFirst().orElseThrow();
     IfBatchOutput.verify(batchRoot.get().resolve(attempt.directory()),item,()->false);
    }
    var report=new LinkedHashMap<String,Object>();report.put("software",IfSoftwareProvenance.snapshot());
    report.put("runtimeSettings",IfInstanSeg.runtimeSettings());report.put("inferenceWorkers",Integer.getInteger("if.batch.workers",1));report.put("guiStartedRealAi",true);report.put("cancelledDuringTileProgress",true);report.put("cancelToControlsEnabledMillis",cancelMillis.get());
    report.put("nextTaskDidNotStartAfterCancel",true);report.put("cancelledAttemptRetained",true);report.put("completedAfterGuiResume",2);
    report.put("allCompletedOutputsVerified",true);report.put("batchDirectory",batchRoot.get().toString());
    report.put("limitations","Programmatic button events with native folder chooser substituted. Two fixed ROIs on one real slide. Not native pointer usability, 150 ROI load, biological accuracy or worst-case cancellation latency. Cancellation timing includes 100 ms test polling.");
    Files.writeString(output.resolve("verification.json"),GsonTools.getInstance(true).toJson(report));
    timer.stop();stage.close();q.getViewer().resetImageData();server.close();q.getStage().hide();done.countDown();Platform.exit();
   }catch(Throwable ex){failure.set(ex);timer.stop();done.countDown();Platform.exit();}}));timer.setCycleCount(Timeline.INDEFINITE);timer.play();
  }catch(Throwable ex){failure.set(ex);done.countDown();Platform.exit();}});
  if(!done.await(180,TimeUnit.SECONDS))System.exit(2);if(failure.get()!=null){failure.get().printStackTrace();System.exit(1);}System.exit(0);
 }
}
