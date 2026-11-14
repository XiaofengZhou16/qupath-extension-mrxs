package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.util.Duration;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.io.GsonTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/** Synthetic or previously exported real-contour workload; no screen capture. */
public class IfReviewScaleGuiSmoke {
    public static void main(String[] args)throws Exception {
        boolean includeRejected=Boolean.getBoolean("if.review.includeRejected");
        boolean real=args[0].equals("real"); // real output-directory analysis-run source.mrxs
        boolean panSelect=real&&args.length==5&&args[4].equals("pan-select");
        boolean settled=real&&args.length==5&&(args[4].equals("settled")||panSelect);
        if(real&&args.length==5&&!List.of("compact-labels","settled","pan-select").contains(args[4]))throw new IllegalArgumentException("Unknown real review experiment");
        IfAnalysisHistory.Entry loadedEntry=null;
        List<IfAnalysis.Cell> loadedCells=List.of();
        if(real) {
            if(args.length!=4&&args.length!=5)throw new IllegalArgumentException("real output-directory analysis-run source.mrxs [compact-labels]");
            try(var source=new MrxsImageServer(Path.of(args[3]).toUri())) {
                loadedEntry=IfHistorySource.validate(Path.of(args[2]),IfHistorySource.capture(source),()->false);
                loadedCells=new ArrayList<>(IfHistoricalCells.load(loadedEntry,()->false).cells());
                if(includeRejected)for(var rejected:IfHistoricalExclusions.load(loadedEntry,()->false))
                    loadedCells.add(new IfAnalysis.Cell(rejected.id(),rejected.cell(),rejected.nucleus()));
                if(includeRejected&&loadedCells.size()!=loadedEntry.proposedCells())throw new AssertionError("Full candidate count mismatch");
            }
        }
        final var realEntry=loadedEntry;final var realCells=loadedCells;
        int count=real?realCells.size():Integer.parseInt(args[0]);if(count<1||count>100000)throw new IllegalArgumentException("Use 1..100000 candidates");
        int expectedCompartments=real?(int)realCells.stream().flatMap(c->java.util.stream.Stream.of(c.cell(),c.nucleus())).filter(r->r!=null&&!r.isEmpty()).count():count*2;
        long expectedMissingNuclei=real?realCells.stream().filter(c->c.nucleus()==null).count():0;
        boolean productionGrouped=false; // ROI-free annotation containers are unsafe in QuPath selection.
        boolean interact=args.length>2&&args[2].equals("interact");
        boolean batched=args.length>2&&(args[2].equals("batch-experiment")||interact);
        int batches=(productionGrouped||batched)?(count+1023)/1024:0;
        Path output=Path.of(args[1]);Files.createDirectory(output);
        System.setProperty("java.util.prefs.PreferencesFactory",IfPanelSmoke.MemoryPreferencesFactory.class.getName());
        var done=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();var report=new LinkedHashMap<String,Object>();
        var pending=new AtomicBoolean();var latencies=new ConcurrentLinkedQueue<Double>();
        var probe=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"if-review-queue-probe");t.setDaemon(true);return t;});
        Platform.startup(()->{try{
            Path prefs=Files.createDirectory(output.resolve("prefs"));PathPrefs.userPathProperty().set(prefs.toString());
            var q=QuPathGUI.createHiddenInstance();Thread.currentThread().setUncaughtExceptionHandler((t,e)->failure.compareAndSet(null,e));
            qupath.lib.images.servers.ImageServer<java.awt.image.BufferedImage> server=real
                    ?new MrxsImageServer(Path.of(args[3]).toUri())
                    :new WrappedBufferedImageServer("SYNTHETIC review scale fixture",new java.awt.image.BufferedImage(2048,2048,java.awt.image.BufferedImage.TYPE_BYTE_GRAY));
            var data=new ImageData<>(server,ImageData.ImageType.FLUORESCENCE);q.getViewer().setImageData(data);
            q.showAnalysisPaneProperty().set(true);q.getStage().setWidth(1100);q.getStage().setHeight(800);q.getStage().show();
            var tabs=q.getAnalysisTabPane();report.put("availableTabs",tabs.getTabs().stream().map(javafx.scene.control.Tab::getText).toList());
            var hierarchyTab=tabs.getTabs().stream().filter(t->t.getText().toLowerCase(Locale.ROOT).contains("hierarchy")).findFirst().orElseThrow(()->new AssertionError("Hierarchy tab unavailable"));
            tabs.getSelectionModel().select(hierarchyTab);q.getStage().getScene().getRoot().applyCss();q.getStage().getScene().getRoot().layout();
            var plane=ImagePlane.getDefaultPlane();var sentinel=PathObjects.createAnnotationObject(ROIs.createRectangleROI(1,1,5,5,plane));data.getHierarchy().addObject(sentinel);var parent=sentinel.getParent();
            if(settled)sentinel.setName("Existing annotation");
            report.put("preInsertionSettlingSeconds",settled?2:0);
            var worker=new Thread(()->{try{
                long start=System.nanoTime();var cells=new ArrayList<IfAnalysis.Cell>(count);
                if(real)cells.addAll(realCells);
                else for(int i=0;i<count;i++){int x=(i%200)*10,y=(i/200)*3;cells.add(new IfAnalysis.Cell("synthetic-"+i,ROIs.createRectangleROI(x,y,8,3,plane),ROIs.createRectangleROI(x+2,y+1,2,1,plane)));}
                var group=IfCellReview.create(real?realEntry.roi():ROIs.createRectangleROI(0,0,2048,2048,plane),cells,()->false,p->{});
                boolean compact=real&&args.length==5&&args[4].equals("compact-labels");
                if(compact) {
                    var pairNumbers=new LinkedHashMap<String,Integer>();
                    for(var child:group.getChildObjects()) {
                        var metadata=child.getMetadata();int number=pairNumbers.computeIfAbsent(metadata.get(IfCellReview.PAIR),key->pairNumbers.size()+1);
                        child.setName(("cell".equals(metadata.get(IfCellReview.COMPARTMENT))?"Cell ":"Nucleus ")+number);
                    }
                }
                report.put("compactLabelExperiment",compact);
                report.put("geometryCoordinateCount",cells.stream().flatMap(c->java.util.stream.Stream.of(c.cell(),c.nucleus())).filter(java.util.Objects::nonNull).mapToLong(r->r.getGeometry().getNumPoints()).sum());
                report.put("software",IfSoftwareProvenance.snapshot());
                if(real)report.put("sourceAnalysis",realEntry.directory().toString());
                if(batched){
                    var originals=new ArrayList<>(group.getChildObjects());
                    if(IfReviewBatchExperiment.regroup(group,2048)!=batches)throw new AssertionError("Unexpected batch count");
                    var restored=group.getDescendantObjects(null).stream().filter(o->o.hasROI()).toList();
                    if(restored.size()!=originals.size()||!new HashSet<>(restored).equals(new HashSet<>(originals)))throw new AssertionError("Batch experiment changed compartment objects");
                }
                report.put("experimentalRoiFreeBatchContainers",batched?batches:0);
                report.put("productionRoiFreeBatchContainers",productionGrouped?batches:0);
                report.put("backgroundGenerationAndCopyMillis",(System.nanoTime()-start)/1e6);
                Platform.runLater(()->{try{
                    long attach=System.nanoTime();data.getHierarchy().getRootObject().addChildObject(group);data.getHierarchy().fireHierarchyChangedEvent(IfReviewScaleGuiSmoke.class);q.getViewer().setSelectedObject(group);
                    if(real){q.getViewer().setCenterPixelLocation(realEntry.roi().getCentroidX(),realEntry.roi().getCentroidY());q.getViewer().setDownsampleFactor(4);}
                    report.put("fxAttachNotifySelectMillis",(System.nanoTime()-attach)/1e6);
                    var settling=new PauseTransition(Duration.seconds(2));settling.setOnFinished(e->{try{
                        if(failure.get()!=null)throw new AssertionError("Uncaught FX failure",failure.get());
                        int descendants=group.nDescendants(),annotations=data.getHierarchy().getAnnotationObjects().size();
                        report.put("actualDescendants",descendants);report.put("actualIndexedAnnotations",annotations);
                        // QuPath's spatial annotation index excludes ROI-free containers.
                        report.put("expectedIndexedAnnotations",expectedCompartments+2);
                        report.put("sentinelParentPreserved",sentinel.getParent()==parent);
                        var spatialObjects=new HashSet<>(data.getHierarchy().getAnnotationObjects());
                        var roiObjects=new HashSet<>(group.getDescendantObjects(null).stream().filter(o->o.hasROI()).toList());
                        roiObjects.add(group);roiObjects.add(sentinel);
                        report.put("indexedRoiObjectIdentitiesMatch",spatialObjects.equals(roiObjects));
                        var snapshot=IfCellReview.capture(group);
                        boolean paired=snapshot.cells().size()==count&&snapshot.cells().stream().allMatch(c->c.cell()!=null)&&snapshot.cells().stream().filter(c->c.nucleus()==null).count()==expectedMissingNuclei;
                        report.put("reviewPairsPreserved",paired);report.put("includedRejectedCandidates",includeRejected);report.put("missingNucleiPreserved",expectedMissingNuclei);
                        if(descendants!=expectedCompartments+batches||sentinel.getParent()!=parent||annotations!=expectedCompartments+2||!spatialObjects.equals(roiObjects)||!paired){
                            report.put("passed",false);
                            Files.writeString(output.resolve("verification.json"),GsonTools.getInstance(true).toJson(report));
                            throw new AssertionError("Hierarchy count/ownership changed: "+GsonTools.getInstance().toJson(report));
                        }
                        probe.shutdownNow();var samples=new ArrayList<>(latencies);Collections.sort(samples);if(samples.isEmpty())throw new AssertionError("No queue probes");
                        report.put(real?(includeRejected?"realAllCandidates":"realMeasuredCandidates"):"syntheticCandidates",count);report.put("compartmentAnnotations",expectedCompartments);report.put("queueSamples",samples.size());
                        report.put("queueP95Millis",samples.get(Math.min(samples.size()-1,(int)Math.ceil(samples.size()*.95)-1)));report.put("queueMaxMillis",samples.getLast());
                        report.put("hierarchyTabSelected",true);report.put("originalParentPreserved",true);report.put("screenshots",false);
                        report.put("limitations",(real?(includeRejected?"Real full candidate contours including exclusions, not manual ground truth. ":"Real previously measured model contours; excludes rejected candidates, not manual ground truth. "):"Synthetic simple rectangles. ")+(panSelect?"Queue statistics cover programmatic pan/select after initial insertion, not startup. ":"")+"One normally laid-out GUI, hierarchy tab selected. Queue probes include deferred UI work but are not pointer latency or whole-slide AI benchmarks. Timing is descriptive, not a speedup claim.");
                        Files.writeString(output.resolve("verification.json"),GsonTools.getInstance(true).toJson(report));System.out.println("IF_REVIEW_SCALE_GUI_OK "+GsonTools.getInstance().toJson(report));
                        q.getViewer().resetImageData();server.close();q.getStage().hide();done.countDown();Platform.exit();
                    }catch(Throwable problem){failure.set(problem);done.countDown();Platform.exit();}});
                    if(interact){
                        if(!batched)throw new IllegalArgumentException("Interaction test requires experimental grouping");
                        var warmup=new PauseTransition(Duration.seconds(2));warmup.setOnFinished(event->{try{
                            var node=hierarchyTab.getContent().lookupAll(".tree-view").stream().filter(n->n instanceof javafx.scene.control.TreeView<?>).findFirst().orElseThrow(()->new AssertionError("Hierarchy tree not found"));
                            @SuppressWarnings("unchecked") var tree=(javafx.scene.control.TreeView<qupath.lib.objects.PathObject>)node;
                            var rootItem=tree.getRoot();rootItem.setExpanded(true);
                            var groupItem=rootItem.getChildren().stream().filter(t->t.getValue()==group).findFirst().orElseThrow(()->new AssertionError("Review tree item missing"));
                            groupItem.setExpanded(true);
                            var batchItems=groupItem.getChildren();if(batchItems.size()!=batches)throw new AssertionError("Tree batches missing");
                            var operations=new ArrayList<Map<String,Object>>();report.put("treeInteractions",operations);
                            var step=new AtomicInteger();var sequence=new javafx.animation.Timeline();
                            sequence.getKeyFrames().add(new javafx.animation.KeyFrame(Duration.millis(500),tick->{try{
                                int index=step.getAndIncrement();int batchIndex=(index/2)*(batches-1)/5;
                                var item=batchItems.get(batchIndex);long started=System.nanoTime();
                                if(index%2==0){
                                    item.setExpanded(true);var child=item.getChildren().getLast();
                                    tree.getSelectionModel().clearAndSelect(tree.getRow(child));tree.scrollTo(tree.getRow(child));
                                    if(data.getHierarchy().getSelectionModel().getSelectedObject()!=child.getValue())throw new AssertionError("Tree-to-hierarchy selection mismatch");
                                }else{
                                    item.setExpanded(false);q.getViewer().setSelectedObject(IfCellReview.owningGroup(item.getValue()));
                                    if(data.getHierarchy().getSelectionModel().getSelectedObject()!=group)throw new AssertionError("Return to review group failed");
                                }
                                operations.add(Map.of("step",index,"batchIndex",batchIndex,"action",index%2==0?"expand-select-cell":"collapse-return-group","synchronousMillis",(System.nanoTime()-started)/1e6));
                                if(index==11){sequence.stop();report.put("treeInteractionStepsPassed",operations.size());settling.play();}
                            }catch(Throwable problem){sequence.stop();failure.set(problem);done.countDown();Platform.exit();}}));
                            sequence.setCycleCount(12);sequence.play();
                        }catch(Throwable problem){failure.set(problem);done.countDown();Platform.exit();}});warmup.play();
                    }else if(panSelect) {
                        var warmup=new PauseTransition(Duration.seconds(2));warmup.setOnFinished(event->{try{
                            var initial=new ArrayList<>(latencies);Collections.sort(initial);
                            report.put("initialQueueMaxMillis",initial.isEmpty()?null:initial.getLast());
                            latencies.clear();
                            var children=new ArrayList<>(group.getChildObjects());
                            var operations=new ArrayList<Map<String,Object>>();report.put("programmaticPanSelect",operations);
                            var step=new AtomicInteger();var sequence=new javafx.animation.Timeline();
                            sequence.getKeyFrames().add(new javafx.animation.KeyFrame(Duration.millis(150),tick->{try{
                                int index=step.getAndIncrement();var child=children.get(index*(children.size()-1)/19);
                                long started=System.nanoTime();
                                q.getViewer().setCenterPixelLocation(realEntry.roi().getCentroidX()+((index%5)-2)*64,realEntry.roi().getCentroidY()+((index/5)-2)*64);
                                q.getViewer().setSelectedObject(child);
                                if(q.getViewer().getSelectedObject()!=child||IfCellReview.owningGroup(child)!=group)throw new AssertionError("Pan/select changed review ownership");
                                operations.add(Map.of("step",index,"compartment",child.getMetadata().get(IfCellReview.COMPARTMENT),"synchronousMillis",(System.nanoTime()-started)/1e6));
                                if(index==19){sequence.stop();q.getViewer().setSelectedObject(group);settling.play();}
                            }catch(Throwable problem){sequence.stop();failure.set(problem);done.countDown();Platform.exit();}}));
                            sequence.setCycleCount(20);sequence.play();
                        }catch(Throwable problem){failure.set(problem);done.countDown();Platform.exit();}});warmup.play();
                    }else settling.play();
                }catch(Throwable problem){failure.set(problem);done.countDown();Platform.exit();}});
            }catch(Throwable problem){failure.set(problem);done.countDown();Platform.exit();}},"if-review-scale-copy");worker.setDaemon(true);
            Runnable begin=()->{
                probe.scheduleAtFixedRate(()->{if(!pending.compareAndSet(false,true))return;long sent=System.nanoTime();Platform.runLater(()->{latencies.add((System.nanoTime()-sent)/1e6);pending.set(false);});},0,50,TimeUnit.MILLISECONDS);
                worker.start();
            };
            if(settled){var warmup=new PauseTransition(Duration.seconds(2));warmup.setOnFinished(e->begin.run());warmup.play();}
            else begin.run();
        }catch(Throwable problem){failure.set(problem);done.countDown();Platform.exit();}});
        if(!done.await(90,TimeUnit.SECONDS)){probe.shutdownNow();System.exit(2);}probe.shutdownNow();if(failure.get()!=null){failure.get().printStackTrace();System.exit(1);}System.exit(0);
    }
}
