package io.github.xiaofengzhou.qupath.mrxs;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.io.PathIO;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Real panel button->worker->export->overlay flow, isolated preferences and no screenshots. */
public class IfAnalysisRunSmoke {
    public static void main(String[] args)throws Exception {
        if(args.length!=3 && args.length!=4 && args.length!=5)throw new IllegalArgumentException("slide.mrxs isolated-user-directory new-output-parent [cancel | ai model-folder]");
        boolean testCancel=args.length==4 && args[3].equals("cancel");
        boolean testRepair=args.length==4 && args[3].equals("repair");
        boolean testWorkload=args.length==4 && args[3].equals("workload");
        boolean testManual=args.length==4 && args[3].equals("manual");
        boolean testAiResponsiveness=args.length==5 && args[3].equals("ai-responsiveness");
        boolean testResponsiveness=(args.length==4 && args[3].equals("responsiveness"))||testAiResponsiveness;
        boolean multiRoi=Boolean.getBoolean("if.smoke.multiRoi");
        if(multiRoi&&!testAiResponsiveness)throw new IllegalArgumentException("multiRoi requires ai-responsiveness");
        int cycles=Integer.getInteger("if.smoke.roiCycles",1);
        if(cycles<1||cycles>10||(!multiRoi&&cycles!=1))throw new IllegalArgumentException("roiCycles must be 1..10 and requires multiRoi");
        int expectedRuns=testAiResponsiveness?(multiRoi?3*cycles:1):5;
        int analysisTimeoutSeconds=multiRoi?Math.max(90,expectedRuns*30):testResponsiveness?90:30;
        String[] repairedPairId={null};
        boolean testReview=args.length==5 && args[3].equals("ai-review");
        boolean testPadding=args.length==5&&args[3].equals("ai-padding");
        boolean testAi=args.length==5 && (args[3].equals("ai")||testReview||testAiResponsiveness||testPadding);
        Path output=Path.of(args[2]);Files.createDirectory(output);
        System.setProperty("java.util.prefs.PreferencesFactory",IfPanelSmoke.MemoryPreferencesFactory.class.getName());
        var done=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        var latencyProbe=new AtomicReference<FxLatencyProbe>();
        Platform.startup(()->{
            try {
                Files.createDirectories(Path.of(args[1]));PathPrefs.userPathProperty().set(args[1]);
                var qupath=QuPathGUI.createHiddenInstance();var viewer=qupath.getViewer();
                var server=new MrxsImageServer(Path.of(args[0]).toUri());var data=new ImageData<>(server,ImageData.ImageType.FLUORESCENCE);
                viewer.setImageData(data);
                int roiSize=testAiResponsiveness?4096:(testCancel||testResponsiveness?8192:1024);
                int roiX=32768,roiY=81920;
                String override=System.getProperty("if.smoke.roi");
                if(override!=null) {
                    if(!testAiResponsiveness)throw new IllegalArgumentException("ROI override is restricted to ai-responsiveness tests");
                    String[] coordinates=override.split(",",-1);
                    if(coordinates.length!=3)throw new IllegalArgumentException("if.smoke.roi must be x,y,squareSize");
                    roiX=Integer.parseInt(coordinates[0]);roiY=Integer.parseInt(coordinates[1]);roiSize=Integer.parseInt(coordinates[2]);
                    if(roiX<0||roiY<0||roiSize<512||roiSize>4096
                            ||(long)roiX+roiSize>server.getWidth()||(long)roiY+roiSize>server.getHeight())
                        throw new IllegalArgumentException("Test ROI is outside source bounds or size 512..4096");
                }
                final int testedRoiSize=roiSize;
                var roi=PathObjects.createAnnotationObject(ROIs.createRectangleROI(roiX,roiY,roiSize,roiSize,ImagePlane.getDefaultPlane()));
                data.getHierarchy().addObject(roi);viewer.setSelectedObject(roi);
                int originalLayers=viewer.getCustomOverlayLayers().size();
                var chooserCalls=new java.util.concurrent.atomic.AtomicInteger();
                new IfAnalysisPanel(qupath,owner->{
                    int n=chooserCalls.incrementAndGet();
                    return testWorkload && n==1?null:output;
                },(owner,save)->output.resolve("ai-settings.json")).show();
                var panel=Window.getWindows().stream().filter(w->w instanceof Stage s&&s.getTitle().startsWith("Multiplex IF"))
                        .map(Stage.class::cast).findFirst().orElseThrow();
                var root=panel.getScene().getRoot();root.applyCss();root.layout();
                var mode=(ComboBox<?>)root.lookup("#if-analysis-mode");
                mode.getSelectionModel().select(2);
                var threads=(ComboBox<?>)root.lookup("#if-analysis-ai-threads");
                var tileSize=(ComboBox<?>)root.lookup("#if-analysis-ai-tile-size");
                var padding=(ComboBox<?>)root.lookup("#if-analysis-ai-padding");
                if(!padding.getItems().equals(java.util.List.of(16,32,48,64,80,96))||!padding.getValue().equals(32))throw new IllegalStateException("Unexpected AI padding control");
                if(!threads.getItems().equals(java.util.List.of(1,2,3,4))||!threads.getValue().equals(2)
                        ||!tileSize.getItems().equals(java.util.List.of(256,512,1024))||!tileSize.getValue().equals(512))
                    throw new IllegalStateException("Unexpected AI resource controls");
                if(threads.isDisabled()||tileSize.isDisabled()||padding.isDisabled())throw new IllegalStateException("AI settings inaccessible");
                mode.getSelectionModel().select(0);
                if(!threads.isDisabled()||!tileSize.isDisabled()||!padding.isDisabled())throw new IllegalStateException("AI settings active in existing-cell mode");
                if(testAi) {
                    mode.getSelectionModel().select(2);
                    ((TextField)root.lookup("#if-analysis-ai-model")).setText(args[4]);
                    ((CheckBox)root.lookup("#if-analysis-ai-trust")).setSelected(true);
                    ((ComboBox<?>)root.lookup("#if-analysis-ai-device")).getSelectionModel().select(1);
                    threads.getSelectionModel().select(0); // Non-default value must reach provenance.
                    if(testPadding)padding.getSelectionModel().select(4); // 80, not default 32.
                    ((CheckBox)root.lookup("#if-analysis-ai-channel-3")).setSelected(false);
                    if(testPadding){
                        ((Button)root.lookup("#if-analysis-ai-save")).fire();
                        padding.getSelectionModel().select(1);threads.getSelectionModel().select(1);
                        ((CheckBox)root.lookup("#if-analysis-ai-channel-3")).setSelected(true);
                        ((Button)root.lookup("#if-analysis-ai-load")).fire();
                        if(!padding.getValue().equals(80)||!threads.getValue().equals(1)
                                ||((CheckBox)root.lookup("#if-analysis-ai-channel-3")).isSelected()
                                ||((CheckBox)root.lookup("#if-analysis-ai-trust")).isSelected())throw new IllegalStateException("AI profile controls/trust not restored safely");
                        if(!((TextField)root.lookup("#if-analysis-ai-model")).getText().equals(args[4]))throw new IllegalStateException("AI profile changed model path");
                        ((CheckBox)root.lookup("#if-analysis-ai-trust")).setSelected(true);
                    }
                }
                if(testManual || testRepair) {
                    var outerROI=ROIs.createRectangleROI(32800,82000,100,100,ImagePlane.getDefaultPlane());
                    var group=IfCellReview.create(roi.getROI(),testRepair
                            ?java.util.List.of(new IfAnalysis.Cell("missing",outerROI,null)):java.util.List.of());
                    var outer=testRepair?group.getChildObjects().iterator().next():PathObjects.createAnnotationObject(outerROI);
                    var inner=PathObjects.createAnnotationObject(ROIs.createRectangleROI(32820,82020,20,20,ImagePlane.getDefaultPlane()));
                    data.getHierarchy().addObjects(testRepair?java.util.List.of(group,inner):java.util.List.of(group,outer,inner));
                    var originalOuterParent=outer.getParent();var originalInnerParent=inner.getParent();
                    var originalOuterMetadata=new java.util.HashMap<>(outer.getMetadata());
                    if(testRepair)repairedPairId[0]=outer.getMetadata().get(IfCellReview.PAIR);
                    data.getHierarchy().getSelectionModel().setSelectedObjects(java.util.List.of(inner,group,outer),outer);
                    Platform.runLater(()->{
                        for(var window:Window.getWindows()) {
                            var node=window.getScene().getRoot().lookup(".dialog-pane");
                            if(node instanceof DialogPane dialog && dialog.getButtonTypes().contains(ButtonType.OK)) {
                                ((Button)dialog.lookupButton(ButtonType.OK)).fire();break;
                            }
                        }
                    });
                    ((Button)root.lookup(testRepair?"#if-analysis-repair-nucleus":"#if-analysis-add-manual-pair")).fire();
                    if(viewer.getSelectedObject()!=group || mode.getSelectionModel().getSelectedIndex()!=3
                            || IfCellReview.snapshot(group).size()!=1)throw new IllegalStateException("Manual pair not selected for review");
                    if(outer.getParent()!=originalOuterParent || inner.getParent()!=originalInnerParent
                            || !originalOuterMetadata.equals(new java.util.HashMap<>(outer.getMetadata())) || !inner.getMetadata().isEmpty())throw new IllegalStateException("Source annotations changed");
                    if(testRepair) {
                        var repaired=IfCellReview.snapshot(group).getFirst();
                        if(!repaired.id().equals(repairedPairId[0]) || repaired.nucleus()==null
                                || !repaired.nucleus().equals(inner.getROI()) || group.nChildObjects()!=2)
                            throw new IllegalStateException("Repair changed identity or failed to copy nucleus");
                    }
                }
                var run=(Button)root.lookup("#if-analysis-run");var status=(Label)root.lookup("#if-analysis-status");
                if(testWorkload) {
                    var full=PathObjects.createAnnotationObject(ROIs.createRectangleROI(0,0,server.getWidth(),server.getHeight(),ImagePlane.getDefaultPlane()));
                    data.getHierarchy().addObject(full);viewer.setSelectedObject(full);
                    var resolution=(ComboBox<?>)root.lookup("#if-analysis-resolution");
                    int originalResolution=resolution.getSelectionModel().getSelectedIndex();
                    var dialogCount=new java.util.concurrent.atomic.AtomicInteger();
                    java.util.function.Consumer<ButtonType> answer=button->Platform.runLater(()->{
                        for(var window:Window.getWindows()) {
                            var node=window.getScene().getRoot().lookup(".dialog-pane");
                            if(node instanceof DialogPane dialog && dialog.getButtonTypes().contains(button)) {
                                if(!dialog.getContentText().contains("16,223,240,800")) {
                                    failure.set(new IllegalStateException("Workload dialog missing full native pixel count"));
                                }
                                dialogCount.incrementAndGet();((Button)dialog.lookupButton(button)).fire();break;
                            }
                        }
                    });
                    answer.accept(ButtonType.OK);((Button)root.lookup("#if-analysis-workload")).fire();
                    answer.accept(ButtonType.CANCEL);run.fire();
                    if(chooserCalls.get()!=0 || run.isDisabled())throw new IllegalStateException("Cancelled preflight launched work");
                    answer.accept(ButtonType.OK);run.fire();
                    if(chooserCalls.get()!=1 || run.isDisabled())throw new IllegalStateException("Confirmed preflight did not reach cancelled chooser");
                    try(var files=Files.list(output)){if(files.findAny().isPresent())throw new IllegalStateException("Cancelled preflight/chooser wrote output");}
                    if(dialogCount.get()!=3 || resolution.getSelectionModel().getSelectedIndex()!=originalResolution)
                        throw new IllegalStateException("Missing dialogs or automatic resolution change");
                    if(failure.get()!=null)throw new IllegalStateException("Workload dialog failed",failure.get());
                    viewer.setSelectedObject(roi);
                    System.out.println("IF_WORKLOAD_GUI_OK preview=true cancelledBeforeChooser=true confirmedReachedChooser=true noOutputOnCancel=true resolutionUnchanged=true");
                }
                if(testResponsiveness)latencyProbe.set(new FxLatencyProbe());
                long runHandlerStarted=System.nanoTime();
                run.fire();
                if(testResponsiveness)latencyProbe.get().runHandlerMillis=(System.nanoTime()-runHandlerStarted)/1e6;
                int[] phase={testCancel?0:2};
                Path[] interrupted={null};
                int[] expectedReviewedCount={-1};
                int[] responsivenessRuns={0};
                var runMetrics=new java.util.ArrayList<java.util.Map<String,Object>>();
                long[] runStarted={runHandlerStarted};
                var timer=new PauseTransition(Duration.millis(100));long start=System.nanoTime();
                timer.setOnFinished(e->Platform.runLater(()->{
                    try {
                        if((System.nanoTime()-start)/1e9>analysisTimeoutSeconds)throw new IllegalStateException("GUI analysis timeout: "+status.getText());
                        if(status.getText().startsWith("Failed"))throw new IllegalStateException(status.getText());
                        if(testReview&&phase[0]==4){
                            if(run.isDisabled()){timer.playFromStart();return;}
                            var group=viewer.getSelectedObject();
                            if(!group.getMetadata().containsKey(IfCellReview.GROUP))throw new IllegalStateException("Review copy not selected: "+status.getText());
                            var eligibleSourceId=PathIO.readObjects(interrupted[0].resolve("cells.geojson")).iterator().next().getID().toString();
                            expectedReviewedCount[0]=com.google.gson.JsonParser.parseString(Files.readString(interrupted[0].resolve("manifest.json")))
                                    .getAsJsonObject().get("eligibleCells").getAsInt()-1;
                            var cell=group.getChildObjects().stream().filter(o->eligibleSourceId.equals(o.getMetadata().get("IF source cell ID"))
                                    && "cell".equals(o.getMetadata().get(IfCellReview.COMPARTMENT))).findFirst().orElseThrow();
                            var pair=IfCellReview.snapshot(group).stream().filter(c->c.id().equals(cell.getMetadata().get(IfCellReview.PAIR))).findFirst().orElseThrow();
                            ((PathAnnotationObject)cell).setROI(pair.nucleus());
                            if(mode.getSelectionModel().getSelectedIndex()!=3)throw new IllegalStateException("Review mode not selected");
                            phase[0]=3;run.fire();timer.playFromStart();return;
                        }
                        if(phase[0]==0) {
                            try(var paths=Files.walk(output)) {
                                if(paths.noneMatch(p->p.toString().endsWith(".png"))) {timer.playFromStart();return;}
                            }
                            try(var dirs=Files.list(output)){interrupted[0]=dirs.filter(Files::isDirectory).findFirst().orElseThrow();}
                            if(status.getText().startsWith("Complete:"))throw new IllegalStateException("Cancellation missed active run");
                            ((Button)root.lookup("#if-analysis-cancel")).fire();
                            if(!run.isDisabled())throw new IllegalStateException("Run enabled synchronously before worker exit callback");
                            phase[0]=1;timer.playFromStart();return;
                        }
                        if(phase[0]==1) {
                            if(run.isDisabled()){timer.playFromStart();return;}
                            if(!status.getText().startsWith("Cancelled."))throw new IllegalStateException("Worker did not report cancellation");
                            if(!Files.exists(interrupted[0].resolve("INCOMPLETE"))||Files.exists(interrupted[0].resolve("manifest.json")))
                                throw new IllegalStateException("Cancelled output marked complete");
                            if(viewer.getCustomOverlayLayers().size()!=originalLayers)throw new IllegalStateException("Cancelled result installed overlays");
                            var retry=PathObjects.createAnnotationObject(ROIs.createRectangleROI(32768,81920,1024,1024,ImagePlane.getDefaultPlane()));
                            data.getHierarchy().addObject(retry);viewer.setSelectedObject(retry);
                            phase[0]=2;run.fire();timer.playFromStart();return;
                        }
                        if(!status.getText().startsWith("Complete:")) {
                            if((System.nanoTime()-start)/1e9>analysisTimeoutSeconds)throw new IllegalStateException("GUI analysis timeout: "+status.getText());
                            timer.playFromStart();return;
                        }
                        Path exported;try(var dirs=Files.list(output)){exported=dirs.filter(Files::isDirectory).filter(p->!p.equals(interrupted[0])).sorted(java.util.Comparator.reverseOrder()).findFirst().orElseThrow();}
                        if(testCancel && !Files.exists(interrupted[0].resolve("INCOMPLETE")))throw new IllegalStateException("Retry modified cancelled output");
                        if(!Files.isRegularFile(exported.resolve("area-masks/manifest.json"))||Files.exists(exported.resolve("INCOMPLETE")))throw new IllegalStateException("Mask export did not complete");
                        var completion=com.google.gson.JsonParser.parseString(Files.readString(exported.resolve("manifest.json"))).getAsJsonObject();
                        var coverageJson=completion.getAsJsonObject("measurementCoverage");
                        int measured=completion.get("eligibleCells").getAsInt(),proposed=completion.get("inputCells").getAsInt();
                        if(coverageJson.get("measuredCells").getAsInt()!=measured
                                ||coverageJson.get("proposedCells").getAsInt()!=proposed
                                ||!((Label)root.lookup("#if-analysis-coverage")).getText().contains(measured+" / "+proposed))
                            throw new IllegalStateException("Measurement coverage panel/export mismatch");
                        if(testResponsiveness) {
                            if(run.isDisabled()){timer.playFromStart();return;}
                            var currentRoi=viewer.getSelectedObject().getROI();
                            runMetrics.add(java.util.Map.of("runDirectory",exported.toString(),
                                    "sourceBounds",java.util.List.of(currentRoi.getBoundsX(),currentRoi.getBoundsY(),currentRoi.getBoundsWidth(),currentRoi.getBoundsHeight()),
                                    "wallSecondsIncludingPolling",(System.nanoTime()-runStarted[0])/1e9,
                                    "heapUsedBytes",java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
                                    "gcCollections",java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b->Math.max(0,b.getCollectionCount())).sum(),
                                    "readerCacheBytes",server.cacheStats().cachedBytes(),
                                    "candidateCells",proposed,"measuredCells",measured));
                            Files.writeString(output.resolve("sequential-runs.json"),GsonTools.getInstance(true).toJson(runMetrics));
                            if(++responsivenessRuns[0]<expectedRuns) {
                                if(multiRoi) {
                                    int[][] next={{32768,81920},{36864,81920},{32768,79872}};
                                    int[] xy=next[responsivenessRuns[0]%3];
                                    ((PathAnnotationObject)roi).setROI(ROIs.createRectangleROI(xy[0],xy[1],2048,2048,ImagePlane.getDefaultPlane()));
                                    viewer.setSelectedObject(roi);
                                }
                                runStarted[0]=System.nanoTime();run.fire();timer.playFromStart();return;
                            }
                            var report=latencyProbe.get().finish();
                            report.put("roiPixels",(long)testedRoiSize*testedRoiSize);
                            report.put("roiSourceBounds",java.util.List.of(roi.getROI().getBoundsX(),roi.getROI().getBoundsY(),roi.getROI().getBoundsWidth(),roi.getROI().getBoundsHeight()));
                            report.put("aiInference",testAi);
                            report.put("limitations",testAi
                                    ?"One AI/measurement/mask-export run; queue dispatch only, not active editing, pointer-to-display latency, accuracy or whole-slide validation."
                                    :"Five repeated PA/mask-export runs at the same ROI; warm-cache queue dispatch only, not active editing, AI or whole-slide validation.");
                            report.put("completedRuns",responsivenessRuns[0]);
                            report.put("sequentialRuns",runMetrics);
                            if(multiRoi)report.put("limitations","Repeated cycles of three coordinate-selected ROIs in one GUI/reader session; queue dispatch and end-of-run heap only, not long-term stability, representative tissue, controlled cold cache or biological accuracy.");
                            try(var dirs=Files.list(output)) {
                                if(dirs.filter(Files::isDirectory).count()!=expectedRuns)
                                    throw new IllegalStateException("Unexpected count of independently exported runs");
                            }
                            Files.writeString(output.resolve("fx-responsiveness.json"),GsonTools.getInstance(true).toJson(report));
                            if(((Number)report.get("samples")).intValue()<20)
                                throw new IllegalStateException("Insufficient FX latency samples");
                            if(((Number)report.get("p95Millis")).doubleValue()>250
                                    || ((Number)report.get("maxMillis")).doubleValue()>1000)
                                throw new IllegalStateException("FX latency exceeded diagnostic budget; inspect report");
                            System.out.println("IF_FX_RESPONSIVENESS_OK "+report);
                        }
                        if(testManual || testRepair) {
                            var manifest=com.google.gson.JsonParser.parseString(Files.readString(exported.resolve("manifest.json"))).getAsJsonObject();
                            if(manifest.get("inputCells").getAsInt()!=1 || manifest.get("eligibleCells").getAsInt()!=1)
                                throw new IllegalStateException("Manual cell not counted exactly once");
                            var lines=Files.readAllLines(exported.resolve("cells.csv"));
                            if(lines.size()!=2)throw new IllegalStateException("Unexpected manual cell rows");
                            if(testRepair && !lines.get(1).contains(repairedPairId[0]))throw new IllegalStateException("Export lost repaired pair identity");
                            var reviewProvenance=com.google.gson.JsonParser.parseString(Files.readString(exported.resolve("segmentation.json"))).getAsJsonObject();
                            var compartments=reviewProvenance.getAsJsonArray("reviewCompartments");
                            if(compartments.size()!=2 || reviewProvenance.get("reviewSnapshotSchemaVersion").getAsInt()!=2
                                    || !reviewProvenance.get("reviewMembership").getAsString().equals("owner metadata across the current image hierarchy; conflicting enclosing groups rejected"))
                                throw new IllegalStateException("Review provenance incomplete");
                            for(var compartment:compartments) {
                                var entry=compartment.getAsJsonObject();
                                if(testRepair && !entry.get("pairId").getAsString().equals(repairedPairId[0]))
                                    throw new IllegalStateException("Review provenance pair mismatch");
                                if(testRepair && entry.get("compartment").getAsString().equals("nucleus")
                                        && !entry.getAsJsonObject("sourceMetadata").has("IF manual compartment source ID"))
                                    throw new IllegalStateException("Missing hand-drawn nucleus source ID");
                            }
                            System.out.println((testRepair?"IF_NUCLEUS_REPAIR_GUI_OK":"IF_MANUAL_PAIR_GUI_OK")+" multiselection=true confirmation=true copiedWithoutMutation=true measuredExactlyOnce=true");
                        }
                        if(testAi && phase[0]==2) {
                            if(run.isDisabled()){timer.playFromStart();return;}
                            var provenance=com.google.gson.JsonParser.parseString(Files.readString(exported.resolve("segmentation.json"))).getAsJsonObject();
                            if(provenance.get("threads").getAsInt()!=1 || provenance.get("tileSize").getAsInt()!=512
                                    ||provenance.get("padding").getAsInt()!=(testPadding?80:32)
                                    || !provenance.get("deviceRequested").getAsString().equals("mps")
                                    || provenance.getAsJsonArray("inputChannelIndices").size()!=3
                                    || provenance.get("detectedCells").getAsInt()<=0)
                                throw new IllegalStateException("AI controls not reflected in exported provenance");
                            try(var lines=Files.lines(exported.resolve("cells.csv"))) {
                                if(lines.count()<=1)throw new IllegalStateException("AI run exported no measured cells");
                            }
                            var review=PathIO.readObjects(exported.resolve("excluded-cells.geojson"));
                            if(review.isEmpty()||review.stream().anyMatch(o->!o.isAnnotation()))
                                throw new IllegalStateException("Expected separate excluded-cell review annotations");
                            ((CheckBox)root.lookup("#if-analysis-review-rejected")).fire();
                            var reasons=(ComboBox<?>)root.lookup("#if-analysis-rejection-reason");
                            var expectedReasons=completion.getAsJsonObject("cellExclusionReasonCounts").keySet().stream().sorted().toList();
                            if(reasons.isDisabled() || !reasons.getItems().subList(1,reasons.getItems().size()).equals(expectedReasons))
                                throw new IllegalStateException("Excluded-cell reasons do not match export");
                            for(int reasonIndex=1;reasonIndex<reasons.getItems().size();reasonIndex++)
                                reasons.getSelectionModel().select(reasonIndex);
                            double originalZoom=viewer.getDownsampleFactor();
                            var selectedBeforeJump=viewer.getSelectedObject();
                            ((Button)root.lookup("#if-analysis-rejected-next")).fire();
                            String position=((Label)root.lookup("#if-analysis-rejected-position")).getText();
                            var target=review.stream().filter(o->"cell".equals(o.getMetadata().get("IF review compartment"))
                                    && position.endsWith(o.getMetadata().get("IF source cell ID"))).findFirst().orElseThrow();
                            if(Math.abs(viewer.getCenterPixelX()-target.getROI().getCentroidX())>1e-6
                                    ||Math.abs(viewer.getCenterPixelY()-target.getROI().getCentroidY())>1e-6
                                    ||viewer.getDownsampleFactor()!=originalZoom || viewer.getSelectedObject()!=selectedBeforeJump)
                                throw new IllegalStateException("Review navigation failed or changed zoom/selection");
                            ((Button)root.lookup("#if-analysis-rejected-next")).fire();
                            ((Button)root.lookup("#if-analysis-rejected-previous")).fire();
                            if(!position.equals(((Label)root.lookup("#if-analysis-rejected-position")).getText()))
                                throw new IllegalStateException("Review previous/next did not restore target");
                            reasons.getSelectionModel().selectFirst();
                            ((CheckBox)root.lookup("#if-analysis-review-rejected")).fire();
                            if(!reasons.isDisabled())throw new IllegalStateException("Exclusion filter active in phenotype mode");
                        }
                        if(testReview && phase[0]==2) {
                            if(run.isDisabled()){timer.playFromStart();return;}
                            // Confirm through the actual JavaFX dialog during its nested event loop.
                            Platform.runLater(()->{
                                for(var window:Window.getWindows()) {
                                    var node=window.getScene().getRoot().lookup(".dialog-pane");
                                    if(node instanceof DialogPane dialog && dialog.getButtonTypes().contains(ButtonType.OK)) {
                                        ((Button)dialog.lookupButton(ButtonType.OK)).fire();break;
                                    }
                                }
                            });
                            ((Button)root.lookup("#if-analysis-create-review")).fire();
                            if(!run.isDisabled())throw new IllegalStateException("Copy should keep analysis locked until worker exit");
                            interrupted[0]=exported;phase[0]=4;timer.playFromStart();return;
                        }
                        if(testReview && phase[0]==3) {
                            var provenance=com.google.gson.JsonParser.parseString(Files.readString(exported.resolve("segmentation.json"))).getAsJsonObject();
                            if(!provenance.get("method").getAsString().contains("editable IF review"))throw new IllegalStateException("Review reanalysis reran AI");
                            if(!Files.readString(exported.resolve("excluded-cells.csv")).contains("no_valid_cytoplasm_pixels"))throw new IllegalStateException("Edited compartment was not remeasured");
                            if(com.google.gson.JsonParser.parseString(Files.readString(exported.resolve("manifest.json"))).getAsJsonObject()
                                    .get("eligibleCells").getAsInt()!=expectedReviewedCount[0])throw new IllegalStateException("Edited eligible cell did not leave the denominator exactly once");
                            System.out.println("IF_EDITABLE_REVIEW_GUI_OK confirmation=true editedCopy=true reanalysis=true originalDetectionsUnchanged=true");
                        }
                        if(viewer.getCustomOverlayLayers().size()!=originalLayers+2)throw new IllegalStateException("Both result overlays not installed");
                        var outlineOpacity=(Slider)root.lookup("#if-analysis-outline-opacity");
                        var maskOpacity=(Slider)root.lookup("#if-analysis-mask-opacity");
                        if(outlineOpacity==null||maskOpacity==null||outlineOpacity.getValue()!=1||maskOpacity.getValue()!=0.4)
                            throw new IllegalStateException("Missing opacity controls or changed defaults");
                        var savedTables=new java.util.LinkedHashMap<Path,byte[]>();
                        try(var files=Files.list(exported)) {
                            for(var file:files.filter(p->p.toString().endsWith(".csv")||p.toString().endsWith(".json")).toList())
                                savedTables.put(file,Files.readAllBytes(file));
                        }
                        var layersBefore=java.util.List.copyOf(viewer.getCustomOverlayLayers());
                        String statusBefore=status.getText();double zoomBefore=viewer.getDownsampleFactor();
                        long opacityStart=System.nanoTime();
                        for(int step=0;step<=100;step++) {
                            double alpha=step/100.0;outlineOpacity.setValue(alpha);maskOpacity.setValue(1-alpha);
                            for(var layer:viewer.getCustomOverlayLayers()) {
                                if(layer instanceof IfCellOverlay cell&&Math.abs(cell.opacity()-alpha)>1e-6)
                                    throw new IllegalStateException("Outline control did not update overlay");
                                if(layer instanceof IfAreaMaskOverlay mask&&Math.abs(mask.opacity()-(1-alpha))>1e-6)
                                    throw new IllegalStateException("PA control did not update overlay");
                            }
                        }
                        double controlMillis=(System.nanoTime()-opacityStart)/1e6;
                        if(!layersBefore.equals(viewer.getCustomOverlayLayers())||!statusBefore.equals(status.getText())||zoomBefore!=viewer.getDownsampleFactor())
                            throw new IllegalStateException("Opacity replaced overlays, changed analysis status or zoom");
                        for(var saved:savedTables.entrySet())if(!java.util.Arrays.equals(saved.getValue(),Files.readAllBytes(saved.getKey())))
                            throw new IllegalStateException("Opacity changed saved measurements");
                        Files.writeString(output.resolve("opacity-controls.json"),GsonTools.getInstance(true).toJson(java.util.Map.of(
                                "software",IfSoftwareProvenance.snapshot(),"steps",101,"controlDispatchMillis",controlMillis,
                                "overlayInstancesPreserved",true,"savedTablesUnchanged",true,"screenshots",false,
                                "limitations","Control dispatch timing only; not pointer-to-display latency or visual clarity validation.")));
                        ((CheckBox)root.lookup("#if-analysis-show-pa")).fire();
                        var filter=(ComboBox<?>)root.lookup("#if-analysis-combination");
                        if(filter.getItems().size()!=8)throw new IllegalStateException("Expected seven marker combinations plus all option");
                        filter.getSelectionModel().select(1);((CheckBox)root.lookup("#if-analysis-exact")).fire();
                        if(!data.getHierarchy().getDetectionObjects().isEmpty())throw new IllegalStateException("PA workflow changed stored detections");
                        panel.fireEvent(new javafx.stage.WindowEvent(panel,javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));panel.close();
                        if(viewer.getCustomOverlayLayers().size()!=originalLayers)throw new IllegalStateException("Overlay cleanup failed");
                        viewer.resetImageData();server.close();
                        if(multiRoi) {
                            if(server.openDataFileCount()!=0||server.cacheStats().cachedBytes()!=0)throw new IllegalStateException("Reader resources retained after close");
                            Files.writeString(output.resolve("reader-close.json"),GsonTools.getInstance(true).toJson(java.util.Map.of(
                                    "openDataFiles",server.openDataFileCount(),"decodedCacheBytes",server.cacheStats().cachedBytes(),
                                    "scope","Reader-owned files/cache only; does not prove native model/GPU memory release")));
                        }
                        System.out.println("IF_ANALYSIS_RUN_GUI_OK export=true masks=true filters=7 overlayCleanup=true originalDetectionsUnchanged=true screenshots=false nativeFolderDialog=bypassed");
                        if(testCancel)System.out.println("IF_ANALYSIS_CANCEL_RETRY_OK cancelledAfterMaskWrite=true incompletePreserved=true retryComplete=true");
                        if(testAi)System.out.println("IF_ANALYSIS_AI_GUI_OK settingsProvenance=true measuredCells=true isolatedHierarchy=true");
                    }catch(Throwable t){failure.set(t);}finally {
                        if(!timer.getStatus().equals(javafx.animation.Animation.Status.RUNNING)){done.countDown();Platform.exit();}
                    }
                }));timer.play();
            }catch(Throwable t){failure.set(t);done.countDown();Platform.exit();}
        });
        if(!done.await(analysisTimeoutSeconds+20,TimeUnit.SECONDS)){if(latencyProbe.get()!=null)latencyProbe.get().close();System.err.println("GUI pipeline timed out");System.exit(2);}
        if(latencyProbe.get()!=null)latencyProbe.get().close();
        if(failure.get()!=null){failure.get().printStackTrace();System.exit(1);}System.exit(0);
    }

    /** At most one pending event: a stalled FX queue cannot accumulate probe work. */
    private static final class FxLatencyProbe implements AutoCloseable {
        private final java.util.concurrent.ScheduledExecutorService scheduler=
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{
                    Thread thread=new Thread(r,"IF-FX-latency-probe");thread.setDaemon(true);return thread;
                });
        private final java.util.concurrent.atomic.AtomicBoolean pending=new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.List<Double> millis=new java.util.ArrayList<>(); // FX thread only
        private final long started=System.nanoTime();
        private final Thread fxThread=Thread.currentThread();
        private volatile long pendingSince;
        private double runHandlerMillis;
        private final java.util.List<java.util.Map<String,Object>> slowStacks=
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        FxLatencyProbe() {
            scheduler.scheduleAtFixedRate(()->{
                if(closed.get())return;
                if(!pending.compareAndSet(false,true)) {
                    long age=System.nanoTime()-pendingSince;
                    if(age>250_000_000L && slowStacks.size()<30)slowStacks.add(java.util.Map.of(
                            "pendingMillis",age/1e6,"sinceProbeStartMillis",(System.nanoTime()-started)/1e6,
                            "fxState",fxThread.getState().name(),
                            "fxStack",java.util.Arrays.stream(fxThread.getStackTrace()).map(Object::toString).toList()));
                    return;
                }
                long queued=System.nanoTime();pendingSince=queued;
                Platform.runLater(()->{
                    if(!closed.get())millis.add((System.nanoTime()-queued)/1e6);
                    pending.set(false);
                });
            },0,100,TimeUnit.MILLISECONDS);
        }
        java.util.Map<String,Object> finish() {
            if(!Platform.isFxApplicationThread())throw new IllegalStateException("FX thread required");
            close();
            var sorted=millis.stream().sorted().toList();
            var report=new java.util.LinkedHashMap<String,Object>();
            report.put("samples",sorted.size());report.put("elapsedSeconds",(System.nanoTime()-started)/1e9);
            report.put("p95Millis",sorted.isEmpty()?Double.NaN:sorted.get((int)Math.ceil(sorted.size()*.95)-1));
            report.put("maxMillis",sorted.isEmpty()?Double.NaN:sorted.getLast());
            report.put("latencyMillisInOrder",java.util.List.copyOf(millis));
            report.put("probePeriodMillis",100);
            report.put("initialRunHandlerMillis",runHandlerMillis);
            synchronized(slowStacks){report.put("slowFxStackSamples",java.util.List.copyOf(slowStacks));}
            report.put("diagnosticOverhead","At most 30 FX stack samples when pending >250 ms; thresholds and latency samples unchanged");
            report.put("maximumPendingEvents",1);report.put("screenshots",false);
            report.put("software",IfSoftwareProvenance.snapshot());
            return report;
        }
        @Override public void close(){closed.set(true);scheduler.shutdown();}
    }
}
