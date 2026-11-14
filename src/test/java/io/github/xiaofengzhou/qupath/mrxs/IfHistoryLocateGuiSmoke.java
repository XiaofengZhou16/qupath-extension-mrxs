package io.github.xiaofengzhou.qupath.mrxs;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javafx.application.Platform;
import javafx.animation.*;
import javafx.util.Duration;
import javafx.scene.control.*;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
public class IfHistoryLocateGuiSmoke {
    public static void main(String[] args)throws Exception {
        System.setProperty("java.util.prefs.PreferencesFactory",IfPanelSmoke.MemoryPreferencesFactory.class.getName());
        var done=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        Platform.startup(()->{try{
            Files.createDirectory(Path.of(args[2]));PathPrefs.userPathProperty().set(args[2]);var q=QuPathGUI.createHiddenInstance();
            var s=new MrxsImageServer(Path.of(args[0]).toUri());var data=new ImageData<>(s,ImageData.ImageType.FLUORESCENCE);var viewer=q.getViewer();viewer.setImageData(data);
            var stage=IfHistoryPanel.show(q,owner->Path.of(args[1]));var root=stage.getScene().getRoot();
            var outlineOpacity=(Slider)root.lookup("#if-history-outline-opacity");
            var maskOpacity=(Slider)root.lookup("#if-history-mask-opacity");
            if(outlineOpacity==null||maskOpacity==null||outlineOpacity.getValue()!=1||maskOpacity.getValue()!=0.4)
                throw new AssertionError("Missing opacity controls or changed defaults");
            // Set before load to verify that subsequently attached overlays inherit control state.
            outlineOpacity.setValue(0.25);maskOpacity.setValue(0.75);
            var originals=new java.util.LinkedHashMap<Path,byte[]>();
            try(var paths=Files.list(Path.of(args[1]))) {
                for(var path:paths.filter(p->p.toString().endsWith(".csv")||p.toString().endsWith(".json")).toList())
                    originals.put(path,Files.readAllBytes(path));
            }
            var choose=(Button)root.lookup("#if-history-choose");var locate=(Button)root.lookup("#if-history-locate");var table=(TableView<?>)root.lookup("#if-history-table");var status=(Label)root.lookup("#if-history-status");
            boolean all=args.length>3&&args[3].equals("all");
            boolean withNuclei=args.length>3&&args[3].equals("nuclei");
            if(withNuclei) {
                var cells=(CheckBox)root.lookup("#if-history-load-cells");var nuclei=(CheckBox)root.lookup("#if-history-load-nuclei");
                cells.setSelected(true);nuclei.setSelected(true);if(cells.isSelected())throw new AssertionError("Nuclear and cell PN choices must be exclusive");
                cells.setSelected(true);if(nuclei.isSelected())throw new AssertionError("Cell and nuclear choices must be exclusive");nuclei.setSelected(true);
            }
            boolean withCells=all||(args.length>3&&args[3].equals("cells"));if(withCells)((CheckBox)root.lookup("#if-history-load-cells")).setSelected(true);
            boolean withMasks=all||(args.length>3&&args[3].equals("masks"));if(withMasks)((CheckBox)root.lookup("#if-history-load-masks")).setSelected(true);
            boolean withExcluded=all||(args.length>3&&args[3].equals("excluded"));if(withExcluded)((CheckBox)root.lookup("#if-history-load-excluded")).setSelected(true);
            int overlays=viewer.getCustomOverlayLayers().size();double zoom=viewer.getDownsampleFactor();var phase=new AtomicInteger();var timer=new Timeline();long start=System.nanoTime();choose.fire();
            timer.getKeyFrames().add(new KeyFrame(Duration.millis(100),event->{try{
                if(System.nanoTime()-start>TimeUnit.SECONDS.toNanos(25))throw new AssertionError("Timeout: "+status.getText());if(choose.isDisabled())return;
                if(phase.get()==0){if(table.getItems().size()!=1)throw new AssertionError("Expected one run");table.getSelectionModel().selectFirst();locate.fire();phase.set(1);return;}
                if(!status.getText().startsWith("Historical ROI shown"))throw new AssertionError(status.getText());
                var entry=(IfAnalysisHistory.Entry)table.getItems().getFirst();
                if(Math.abs(viewer.getCenterPixelX()-entry.roi().getCentroidX())>1e-6||Math.abs(viewer.getCenterPixelY()-entry.roi().getCentroidY())>1e-6)throw new AssertionError("Wrong center");
                if(viewer.getDownsampleFactor()!=zoom||viewer.getCustomOverlayLayers().size()!=overlays+1+(withCells||withNuclei?1:0)+(withMasks?1:0)+(withExcluded?1:0)||!data.getHierarchy().getAnnotationObjects().isEmpty()||!data.getHierarchy().getDetectionObjects().isEmpty())throw new AssertionError("Zoom/overlay/hierarchy");
                if(withCells||withMasks||withNuclei){var filter=(ComboBox<?>)root.lookup("#if-history-phenotype");if(filter.getItems().size()!=(1<<entry.profile().markers().size()))throw new AssertionError("Filter mapping");filter.getSelectionModel().selectLast();((CheckBox)root.lookup("#if-history-exact")).setSelected(true);}
                if(withNuclei&&!status.getText().contains("NPN_NUCLEUS_ONLY filters"))throw new AssertionError("Nuclear population status missing");
                for(var layer:viewer.getCustomOverlayLayers()) {
                    if(layer instanceof IfCellOverlay cell && cell.opacity()!=0.25f)throw new AssertionError("Initial outline opacity not applied");
                    if(layer instanceof IfAreaMaskOverlay mask && mask.opacity()!=0.75f)throw new AssertionError("Initial mask opacity not applied");
                }
                for(double alpha:new double[]{0,0.5,1}) {
                    outlineOpacity.setValue(alpha);maskOpacity.setValue(1-alpha);
                    for(var layer:viewer.getCustomOverlayLayers()) {
                        if(layer instanceof IfCellOverlay cell && cell.opacity()!=(float)alpha)throw new AssertionError("Outline slider not connected");
                        if(layer instanceof IfAreaMaskOverlay mask && mask.opacity()!=(float)(1-alpha))throw new AssertionError("Mask slider not connected");
                    }
                }
                for(var original:originals.entrySet())if(!java.util.Arrays.equals(original.getValue(),Files.readAllBytes(original.getKey())))
                    throw new AssertionError("Opacity changed historical data: "+original.getKey());
                if(viewer.getDownsampleFactor()!=zoom||!data.getHierarchy().getAnnotationObjects().isEmpty()||!data.getHierarchy().getDetectionObjects().isEmpty())
                    throw new AssertionError("Opacity changed zoom or project objects");
                if(withMasks&&!status.getText().contains("PA masks restored"))throw new AssertionError("Mask load status");
                if(withExcluded){var reason=(ComboBox<?>)root.lookup("#if-history-exclusion-reason");if(reason.getItems().size()!=3)throw new AssertionError("Reason count");reason.getSelectionModel().select(1);((Button)root.lookup("#if-history-exclusion-next")).fire();
                    var info=(Label)root.lookup("#if-history-exclusion-status");if(!info.getText().contains("nucleus outline only")||Math.abs(viewer.getCenterPixelX()-32815)>1e-6||Math.abs(viewer.getCenterPixelY()-82015)>1e-6)throw new AssertionError(info.getText());
                    if(viewer.getDownsampleFactor()!=zoom)throw new AssertionError("Review navigation changed zoom");}
                boolean closeCleanup=args.length>4&&args[4].equals("close");
                if(closeCleanup)stage.close();else viewer.resetImageData();
                if(viewer.getCustomOverlayLayers().size()!=overlays)throw new AssertionError("Overlay not cleared on "+(closeCleanup?"window close":"image switch"));
                Files.writeString(Path.of(args[2]).resolve("verification.json"),qupath.lib.io.GsonTools.getInstance(true).toJson(java.util.Map.of(
                        "software",IfSoftwareProvenance.snapshot(),"source",args[0],"history",args[1],"nucleusOnly",withNuclei,
                        "sourceMatched",true,"zoomAndHierarchyPreserved",true,"filterMappingChecked",withCells||withMasks||withNuclei,
                        "cleanup",closeCleanup?"window-close":"image-switch","screenshots",false,"opacityControlsAndSavedDataChecked",true)));
                stage.close();s.close();timer.stop();System.out.println("IF_HISTORY_LOCATE_GUI_OK sourceMatch=true correctCenter=true zoomPreserved=true projectUnchanged=true cleanup="+(closeCleanup?"window-close":"image-switch")+" screenshots=false");done.countDown();Platform.exit();
            }catch(Throwable t){failure.set(t);timer.stop();stage.close();done.countDown();Platform.exit();}}));timer.setCycleCount(Timeline.INDEFINITE);timer.play();
        }catch(Throwable t){failure.set(t);done.countDown();Platform.exit();}});
        if(!done.await(40,TimeUnit.SECONDS))System.exit(2);if(failure.get()!=null){failure.get().printStackTrace();System.exit(1);}System.exit(0);
    }
}
