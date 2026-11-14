package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
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

/** Actual history copy button and confirmation, isolated fixture/project, no screenshots. */
public class IfHistoryReviewGuiSmoke {
    public static void main(String[] args)throws Exception {
        Path run=Path.of(args[1]);byte[] before=Files.readAllBytes(run.resolve("manifest.json"));
        System.setProperty("java.util.prefs.PreferencesFactory",IfPanelSmoke.MemoryPreferencesFactory.class.getName());
        var done=new CountDownLatch(1);var error=new AtomicReference<Throwable>();
        Platform.startup(()->{try{
            Files.createDirectory(Path.of(args[2]));PathPrefs.userPathProperty().set(args[2]);var q=QuPathGUI.createHiddenInstance();
            var server=new MrxsImageServer(Path.of(args[0]).toUri());var data=new ImageData<>(server,ImageData.ImageType.FLUORESCENCE);q.getViewer().setImageData(data);
            var sentinel=qupath.lib.objects.PathObjects.createAnnotationObject(qupath.lib.roi.ROIs.createRectangleROI(32800,81960,8,8,qupath.lib.regions.ImagePlane.getDefaultPlane()));
            data.getHierarchy().addObject(sentinel);var originalParent=sentinel.getParent();
            var history=IfHistoryPanel.show(q,w->run);var root=history.getScene().getRoot();
            var choose=(Button)root.lookup("#if-history-choose");var edit=(Button)root.lookup("#if-history-edit-copy");var table=(TableView<?>)root.lookup("#if-history-table");
            var phase=new AtomicInteger();var timer=new Timeline();long start=System.nanoTime();choose.fire();
            timer.getKeyFrames().add(new KeyFrame(Duration.millis(100),e->{try{
                if(System.nanoTime()-start>TimeUnit.SECONDS.toNanos(25))throw new AssertionError("History review timeout phase="+phase);
                if(phase.get()==0){if(choose.isDisabled())return;if(table.getItems().size()!=1)throw new AssertionError("Expected one fixture");table.getSelectionModel().selectFirst();edit.fire();phase.set(1);return;}
                if(phase.get()==1||phase.get()==3){
                    for(var window:java.util.List.copyOf(Window.getWindows())){
                        if(window.getScene()==null)continue;var node=window.getScene().getRoot().lookup(".dialog-pane");
                        if(node instanceof DialogPane dialog&&dialog.getButtonTypes().contains(ButtonType.CANCEL)&&dialog.getButtonTypes().contains(ButtonType.OK)){
                            int state=phase.get();phase.set(state+1);((Button)dialog.lookupButton(state==1?ButtonType.CANCEL:ButtonType.OK)).fire();return;
                        }
                    }return;
                }
                if(phase.get()==2){if(choose.isDisabled())return;if(data.getHierarchy().getAnnotationObjects().size()!=1||sentinel.getParent()!=originalParent)throw new AssertionError("Cancel changed objects");edit.fire();phase.set(3);return;}
                var group=q.getViewer().getSelectedObject();if(group==null||!group.getMetadata().containsKey(IfHistoricalReview.LINEAGE))return;
                var snapshot=IfCellReview.capture(group);if(snapshot.cells().size()!=5||group.getChildObjects().size()!=8)throw new AssertionError("Candidates/compartments not preserved");
                if(sentinel.getParent()!=originalParent||sentinel.getROI().getArea()!=64)throw new AssertionError("Existing annotation modified/reparented");
                if(((com.google.gson.JsonObject)snapshot.provenance().get("historicalReview")).getAsJsonArray("geometrylessCandidateIds").size()!=1)throw new AssertionError("Missing geometryless provenance");
                if(!data.getHierarchy().getDetectionObjects().isEmpty())throw new AssertionError("Added detections");
                Stage panel=null;for(var w:Window.getWindows())if(w instanceof Stage s&&s.getTitle().startsWith("Multiplex IF analysis"))panel=s;
                if(panel==null)throw new AssertionError("Analysis panel not opened");
                if(((ComboBox<?>)panel.getScene().getRoot().lookup("#if-analysis-mode")).getSelectionModel().getSelectedIndex()!=3)throw new AssertionError("Not review mode");
                var entry=(IfAnalysisHistory.Entry)table.getItems().getFirst();
                for(var marker:entry.profile().markers()){
                    var n=panel.getScene().getRoot();int c=marker.channel();
                    if(!((CheckBox)n.lookup("#if-analysis-use-"+c)).isSelected()
                            ||Double.parseDouble(((TextField)n.lookup("#if-analysis-pixel-"+c)).getText())!=marker.pixelThreshold()
                            ||Double.parseDouble(((TextField)n.lookup("#if-analysis-nucleus-"+c)).getText())!=marker.nucleusThreshold()
                            ||Double.parseDouble(((TextField)n.lookup("#if-analysis-cytoplasm-"+c)).getText())!=marker.cytoplasmThreshold())throw new AssertionError("Historical thresholds not restored");
                }
                if(((ComboBox<?>)panel.getScene().getRoot().lookup("#if-analysis-resolution")).getSelectionModel().getSelectedIndex()!=entry.profile().resolutionLevel())throw new AssertionError("Historical resolution not restored");
                if(!java.util.Arrays.equals(before,Files.readAllBytes(run.resolve("manifest.json"))))throw new AssertionError("Original history changed");
                panel.close();history.close();server.close();timer.stop();System.out.println("IF_HISTORY_REVIEW_GUI_OK confirmationCancelNoMutation=true newCopy=true candidates=5 geometrylessProvenance=true reviewMode=true sourceManifestUnchanged=true screenshots=false");done.countDown();Platform.exit();
            }catch(Throwable t){error.set(t);timer.stop();done.countDown();Platform.exit();}}));timer.setCycleCount(Timeline.INDEFINITE);timer.play();
        }catch(Throwable t){error.set(t);done.countDown();Platform.exit();}});
        if(!done.await(40,TimeUnit.SECONDS))System.exit(2);if(error.get()!=null){error.get().printStackTrace();System.exit(1);}System.exit(0);
    }
}
