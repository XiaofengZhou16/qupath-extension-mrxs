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

public class IfBatchPanelSmoke {
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
  Platform.startup(()->{try {
   PathPrefs.userPathProperty().set(Files.createDirectory(output.resolve("prefs")).toString());
   var q=QuPathGUI.createHiddenInstance();q.setProject(project);q.getViewer().setImageData(data);q.getStage().show();
   var channels=server.getMetadata().getChannels().stream().map(c->c.getName()).toList();
   var profile=new IfAnalysisProfile(1,0,List.of(new IfAnalysisProfile.Marker(1,channels.get(1),30,30,30)));
   IfBatchPanel.show(q,q.getStage(),profile,new IfInstanSeg.Config(Path.of(args[1]),"mps",1,512,32,List.of(0,1,2)),channels,null,false,IfBatchPanelSmoke.class.getClassLoader());
   var stage=Window.getWindows().stream().filter(w->w instanceof Stage s&&s.getTitle().startsWith("IF project batch")).map(Stage.class::cast).findFirst().orElseThrow();
   var root=stage.getScene().getRoot();root.applyCss();root.layout();
   var scan=(Button)root.lookup("#if-batch-scan");scan.fire();
   if(!scan.isDisabled())throw new AssertionError("Scan must disable duplicate actions");
   var timer=new Timeline();timer.getKeyFrames().add(new KeyFrame(Duration.millis(100),e->{try {
    if(scan.isDisabled())return;
    var list=(ListView<?>)root.lookup("#if-batch-rois");
    if(list.getItems().size()!=2)throw new AssertionError("Expected two saved named ROIs: "+list.getItems());
    root.applyCss();root.layout();
    var screenshot=root.snapshot(null,null);
    javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(screenshot,null),"PNG",output.resolve("batch-panel.png").toFile());
    ((Button)root.lookup("#if-batch-start")).fire();
    if(!((Label)root.lookup("#if-batch-status")).getText().contains("请先选择"))throw new AssertionError("Empty selection not rejected");
    list.getSelectionModel().selectAll();if(list.getSelectionModel().getSelectedItems().size()!=2)throw new AssertionError("Multiple selection failed");
    var report=Map.of("savedNamedRois",2,"unnamedAndReviewCompartmentsExcluded",true,"emptySelectionRejected",true,"multipleSelection",true,"screenshotWritten",true,
      "software",IfSoftwareProvenance.snapshot(),"limitations","Programmatic GUI selection scan only; no native file chooser, AI execution or cancellation. Screenshot written for separate visual review, not automatically visually accepted.");
    Files.writeString(output.resolve("verification.json"),GsonTools.getInstance(true).toJson(report));
    timer.stop();stage.close();q.getViewer().resetImageData();server.close();q.getStage().hide();done.countDown();Platform.exit();
   }catch(Throwable ex){failure.set(ex);timer.stop();done.countDown();Platform.exit();}}));timer.setCycleCount(Timeline.INDEFINITE);timer.play();
  }catch(Throwable ex){failure.set(ex);done.countDown();Platform.exit();}});
  if(!done.await(45,TimeUnit.SECONDS))System.exit(2);if(failure.get()!=null){failure.get().printStackTrace();System.exit(1);}System.exit(0);
 }
}
