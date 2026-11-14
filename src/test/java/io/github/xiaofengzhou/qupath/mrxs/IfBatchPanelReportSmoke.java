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

public class IfBatchPanelReportSmoke {
 public static void main(String[] args)throws Exception {
  Path output=Path.of(args[2]);Files.createDirectory(output);
  var server=new MrxsImageServer(Path.of(args[0]).toUri());
  var data=new ImageData<>(server,ImageData.ImageType.FLUORESCENCE);
  for(int i=0;i<4;i++) {
   var a=PathObjects.createAnnotationObject(ROIs.createRectangleROI(32768+i*512,81920,256,256,ImagePlane.getDefaultPlane()));
   if(i!=2)a.setName("ROI-"+i);if(i==3)a.getMetadata().put(IfCellReview.PAIR,"review-cell");data.getHierarchy().addObject(a);
  }
  Path projectDir=Files.createDirectory(output.resolve("project"));
  var project=qupath.lib.projects.Projects.createProject(projectDir.toFile(),java.awt.image.BufferedImage.class);
  var entry=project.addImage(server.getBuilder());entry.setImageName("batch selector fixture");entry.saveImageData(data);project.syncChanges();
  var done=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
  System.setProperty("java.util.prefs.PreferencesFactory",IfPanelSmoke.MemoryPreferencesFactory.class.getName());
  var phase=new AtomicInteger();
  Platform.startup(()->{try {
   PathPrefs.userPathProperty().set(Files.createDirectory(output.resolve("prefs")).toString());
   var q=QuPathGUI.createHiddenInstance();q.setProject(project);q.getViewer().setImageData(data);q.getStage().show();
   var channels=server.getMetadata().getChannels().stream().map(c->c.getName()).toList();
   var profile=new IfAnalysisProfile(1,0,List.of(new IfAnalysisProfile.Marker(1,channels.get(1),30,30,30)));
   IfBatchPanel.show(q,q.getStage(),profile,new IfInstanSeg.Config(Path.of(args[1]),"mps",1,512,32,List.of(0,1,2)),channels,null,false,IfBatchPanelReportSmoke.class.getClassLoader(),(window,resuming)->Path.of(args[3]));
   var stage=Window.getWindows().stream().filter(w->w instanceof Stage s&&s.getTitle().startsWith("IF project batch")).map(Stage.class::cast).findFirst().orElseThrow();
   var root=stage.getScene().getRoot();root.applyCss();root.layout();
   var scan=(Button)root.lookup("#if-batch-scan");scan.fire();
   if(!scan.isDisabled())throw new AssertionError("Scan must disable duplicate actions");
   var timer=new Timeline();timer.getKeyFrames().add(new KeyFrame(Duration.millis(100),e->{try {
    if(scan.isDisabled())return;
    if(phase.getAndIncrement()==0){((Button)root.lookup("#if-batch-report")).fire();return;}
    var reportPath=Path.of(((TextArea)root.lookup("#if-batch-detail")).getText());
    var summary=GsonTools.getInstance().fromJson(Files.readString(reportPath.resolve("batch-status.json")),IfBatchResults.Summary.class);
    if(summary.planned()!=Integer.getInteger("if.batch.reportExpected",2)||summary.included()!=Integer.getInteger("if.batch.reportExpected",2))throw new AssertionError("Wrong report accounting");
    if(!Files.isRegularFile(reportPath.resolve("office/results.xlsx"))||Files.exists(reportPath.resolve("INCOMPLETE")))throw new AssertionError("Report not complete");
    var report=Map.of("guiReportExport",true,"plannedRois",summary.planned(),"includedRois",summary.included(),"reportPath",reportPath.toString(),
      "software",IfSoftwareProvenance.snapshot(),"limitations","Programmatic GUI report button with native directory chooser substituted. Uses prior real batch results. No Word pagination or native pointer acceptance.");
    Files.writeString(output.resolve("verification.json"),GsonTools.getInstance(true).toJson(report));
    timer.stop();stage.close();q.getViewer().resetImageData();server.close();q.getStage().hide();done.countDown();Platform.exit();
   }catch(Throwable ex){failure.set(ex);timer.stop();done.countDown();Platform.exit();}}));timer.setCycleCount(Timeline.INDEFINITE);timer.play();
  }catch(Throwable ex){failure.set(ex);done.countDown();Platform.exit();}});
  if(!done.await(45,TimeUnit.SECONDS))System.exit(2);if(failure.get()!=null){failure.get().printStackTrace();System.exit(1);}System.exit(0);
 }
}
