package io.github.xiaofengzhou.qupath.mrxs;

import javafx.concurrent.Task;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.io.GsonTools;
import qupath.lib.roi.RoiTools;

/** Read-only source catalog with separately written reports; never mutates historical runs. */
final class IfHistoryPanel {
    static Stage show(QuPathGUI qupath) {
        return show(qupath,owner->{
            var dialog=new DirectoryChooser();dialog.setTitle("Completed IF analysis folder");
            var directory=dialog.showDialog(owner);return directory==null?null:directory.toPath();
        });
    }
    static Stage show(QuPathGUI qupath,java.util.function.Function<javafx.stage.Window,java.nio.file.Path> selectFolder) {
        return show(qupath,selectFolder,owner->{
            var dialog=new DirectoryChooser();dialog.setTitle("Report parent folder / 报告保存位置（自动新建子目录）");
            var directory=dialog.showDialog(owner);return directory==null?null:directory.toPath();
        });
    }
    static Stage show(QuPathGUI qupath,java.util.function.Function<javafx.stage.Window,java.nio.file.Path> selectFolder,
                      java.util.function.Function<javafx.stage.Window,java.nio.file.Path> selectOutput) {
        var stage=new Stage();stage.initOwner(qupath.getStage());stage.setTitle("IF analysis history / 分析历史（只读）");
        var choose=new Button("Choose results folder / 选择结果目录");
        choose.setId("if-history-choose");
        var status=new Label("Choose a run or its immediate parent. Subfolders are not recursively searched.");status.setWrapText(true);
        status.setId("if-history-status");
        var busy=new javafx.beans.property.SimpleBooleanProperty(false);
        var export=new Button("Export selected reports / 导出所选记录");export.setId("if-history-export");
        var exportExcel=new CheckBox("Excel (.xlsx)");exportExcel.setId("if-history-export-excel");exportExcel.setSelected(true);
        var exportWord=new CheckBox("Word (.docx)");exportWord.setId("if-history-export-word");exportWord.setSelected(true);
        exportExcel.disableProperty().bind(busy);exportWord.disableProperty().bind(busy);
        var cancel=new Button("Cancel / 取消任务");cancel.setId("if-history-cancel");
        var locate=new Button("Locate ROI on current slide / 定位历史区域");locate.setId("if-history-locate");
        var editCopy=new Button("Create editable copy… / 创建可编辑副本");editCopy.setId("if-history-edit-copy");
        var clearRegion=new Button("Clear historical ROI / 清除历史区域显示");clearRegion.setId("if-history-clear-region");
        var clearOverlay=new java.util.concurrent.atomic.AtomicReference<Runnable>(() -> {});
        var loadCells=new CheckBox("Load saved measured cells / 载入历史可测量细胞");loadCells.setId("if-history-load-cells");
        var loadNuclei=new CheckBox("Nucleus-only NPN / 独立核统计（替代 PN 轮廓）");loadNuclei.setId("if-history-load-nuclei");
        loadNuclei.selectedProperty().addListener((o,a,b)->{if(b)loadCells.setSelected(false);});
        loadCells.selectedProperty().addListener((o,a,b)->{if(b)loadNuclei.setSelected(false);});
        var loadMasks=new CheckBox("Load saved PA masks / 载入历史 PA 掩膜");loadMasks.setId("if-history-load-masks");
        var loadExcluded=new CheckBox("Review exclusions / 复核历史排除细胞");loadExcluded.setId("if-history-load-excluded");
        var excluded=new java.util.concurrent.atomic.AtomicReference<IfHistoricalExclusions>();
        var reasons=new ComboBox<String>();reasons.setId("if-history-exclusion-reason");
        var previousExcluded=new Button("Previous excluded / 上一个");previousExcluded.setId("if-history-exclusion-previous");
        var nextExcluded=new Button("Next excluded / 下一个");nextExcluded.setId("if-history-exclusion-next");
        var excludedStatus=new Label();excludedStatus.setWrapText(true);excludedStatus.setId("if-history-exclusion-status");
        reasons.setOnAction(e->{var review=excluded.get();if(review!=null){review.filter(reasons.getSelectionModel().getSelectedIndex()<=0?null:reasons.getValue());excludedStatus.setText("Exclusion reason filter changed; not a negative phenotype.");if(qupath.getViewer()!=null)qupath.getViewer().repaint();}});
        java.util.function.IntConsumer navigateExcluded=direction->{var review=excluded.get();var viewer=qupath.getViewer();if(review==null||viewer==null||!review.isFor(viewer.getImageData()))return;
            var item=review.move(direction);if(item==null){excludedStatus.setText("No navigable geometry for this reason; missing/out-of-image objects remain excluded, not negative.");return;}
            var target=item.target();viewer.setZPosition(target.getZ());viewer.setTPosition(target.getT());viewer.setCenterPixelLocation(target.getCentroidX(),target.getCentroidY());
            excludedStatus.setText(item.id()+" | "+item.stage()+" | "+item.reason()+" | "+(item.cell()==null?"nucleus outline only":item.nucleus()==null?"cell outline only":"cell + nucleus outlines"));};
        previousExcluded.setOnAction(e->navigateExcluded.accept(-1));nextExcluded.setOnAction(e->navigateExcluded.accept(1));
        var maskStatus=new Label();maskStatus.setWrapText(true);maskStatus.setId("if-history-mask-status");
        var historicalMasks=new java.util.concurrent.atomic.AtomicReference<IfAreaMaskOverlay>();
        var maskToken=new java.util.concurrent.atomic.AtomicReference<Object>();
        var phenotype=new ComboBox<String>();phenotype.setId("if-history-phenotype");phenotype.setPromptText("Combination filter / 通道组合筛选");
        var exact=new CheckBox("Exact combination (PN / nuclear / PA) / 精确组合");exact.setId("if-history-exact");
        var historicalCellOverlay=new java.util.concurrent.atomic.AtomicReference<IfCellOverlay>();
        var outlineOpacity=new Slider(0,1,1);outlineOpacity.setId("if-history-outline-opacity");outlineOpacity.setPrefWidth(140);
        var maskOpacity=new Slider(0,1,0.4);maskOpacity.setId("if-history-mask-opacity");maskOpacity.setPrefWidth(140);
        Runnable updateCells=()->{int bits=Math.max(0,phenotype.getSelectionModel().getSelectedIndex());var o=historicalCellOverlay.get();if(o!=null){o.setFilter(bits,exact.isSelected());o.setOpacity(outlineOpacity.getValue());}var pa=historicalMasks.get();if(pa!=null){pa.setFilter(bits,exact.isSelected());pa.setOpacity(maskOpacity.getValue());}if(qupath.getViewer()!=null)qupath.getViewer().repaint();};
        outlineOpacity.valueProperty().addListener((o,a,b)->updateCells.run());
        maskOpacity.valueProperty().addListener((o,a,b)->updateCells.run());
        phenotype.setOnAction(e->updateCells.run());exact.setOnAction(e->updateCells.run());
        clearRegion.setOnAction(e->clearOverlay.get().run());
        var details=new TextArea();details.setEditable(false);details.setWrapText(true);
        details.setId("if-history-details");
        var table=new TableView<IfAnalysisHistory.Entry>();table.setId("if-history-table");
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        choose.disableProperty().bind(busy);table.disableProperty().bind(busy);cancel.disableProperty().bind(busy.not());
        export.disableProperty().bind(busy.or(javafx.beans.binding.Bindings.isEmpty(table.getSelectionModel().getSelectedItems()))
                .or(exportExcel.selectedProperty().not().and(exportWord.selectedProperty().not())));
        locate.disableProperty().bind(busy.or(javafx.beans.binding.Bindings.size(table.getSelectionModel().getSelectedItems()).isNotEqualTo(1)));
        editCopy.disableProperty().bind(locate.disableProperty());
        loadCells.disableProperty().bind(busy);
        loadNuclei.disableProperty().bind(busy);
        loadMasks.disableProperty().bind(busy);
        loadExcluded.disableProperty().bind(busy);previousExcluded.disableProperty().bind(busy);nextExcluded.disableProperty().bind(busy);
        table.getColumns().add(column("Image / 图像",IfAnalysisHistory.Entry::imageName));
        table.getColumns().add(column("Created / 时间",IfAnalysisHistory.Entry::createdAt));
        table.getColumns().add(column("Measured / candidates",e->e.measuredCells()+" / "+e.proposedCells()));
        table.getColumns().add(column("Source identity",IfAnalysisHistory.Entry::sourceStatus));
        table.getColumns().add(column("Run directory",e->e.directory().getFileName().toString()));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No complete records loaded / 暂无完整记录"));
        var loaded=new java.util.concurrent.atomic.AtomicReference<>(new IfAnalysisHistory.Catalog(java.util.List.of(),java.util.List.of()));
        table.getSelectionModel().getSelectedItems().addListener((javafx.collections.ListChangeListener<IfAnalysisHistory.Entry>)change->{
            var selected=java.util.List.copyOf(table.getSelectionModel().getSelectedItems());
            if(selected.isEmpty()){details.setText(IfAnalysisHistory.format(loaded.get()));return;}
            var text=new StringBuilder("Selected runs: "+selected.size()+". Selection does not pool cells or establish independent biological replicates.\n\n");
            text.append(IfAnalysisHistory.format(new IfAnalysisHistory.Catalog(selected,java.util.List.of())));
            for(var entry:selected)text.append("Full profile: ").append(entry.directory()).append('\n')
                    .append(GsonTools.getInstance(true).toJson(entry.profile())).append('\n');
            details.setText(text.toString());
        });
        var active=new java.util.concurrent.atomic.AtomicReference<Task<?>>();
        cancel.setOnAction(e->{var job=active.get();if(job!=null)job.cancel(false);});
        choose.setOnAction(event->{
            if(busy.get())return;
            var directory=selectFolder.apply(stage);if(directory==null)return;
            loaded.set(new IfAnalysisHistory.Catalog(java.util.List.of(),java.util.List.of()));
            table.getItems().clear();busy.set(true);status.setText("Reading history metadata…");details.clear();
            Task<IfAnalysisHistory.Catalog> job=new Task<>(){
                @Override protected IfAnalysisHistory.Catalog call() throws Exception {
                    return IfAnalysisHistory.scan(directory,this::isCancelled);
                }
            };
            job.setOnSucceeded(e->{loaded.set(job.getValue());table.getItems().setAll(job.getValue().entries());
                status.setText("Loaded "+job.getValue().entries().size()+"; skipped "+job.getValue().rejected().size()+". Source identity and scientific validity are not verified.");
                details.setText(IfAnalysisHistory.format(job.getValue()));});
            job.setOnFailed(e->{status.setText("Could not read history: "+job.getException().getMessage());});
            job.setOnCancelled(e->{status.setText("History scan cancelled. Controls unlock when the worker has stopped.");});
            startWorker(job,busy,active,"if-history-catalog");
        });
        export.setOnAction(event->{
            var selected=table.getSelectionModel().getSelectedItems().stream().map(IfAnalysisHistory.Entry::directory).toList();
            if(selected.isEmpty()||busy.get())return;
            if(!exportExcel.isSelected()&&!exportWord.isSelected())return;
            var formats=new IfOfficeReport.Formats(exportExcel.isSelected(),exportWord.isSelected());
            var parent=selectOutput.apply(stage);if(parent==null)return;
            var output=parent.resolve("if-report-"+java.util.UUID.randomUUID());
            busy.set(true);status.setText("Exporting "+selected.size()+" runs in background… "+output);
            Task<Void> job=new Task<>(){
                @Override protected Void call() throws Exception {IfOfficeReport.write(selected,output,formats,this::isCancelled);return null;}
            };
            job.setOnSucceeded(e->{status.setText("Report saved: "+output+". Exported: "+(formats.excel()?"Excel ":"")+(formats.word()?"Word":"")+". Separate per-run statistics; no biological pooling or visual acceptance implied.");});
            job.setOnFailed(e->{status.setText("Report failed: "+job.getException().getMessage()+". Any partial output remains at "+output+"; check INCOMPLETE before use.");});
            job.setOnCancelled(e->{status.setText("Report cancelled; controls unlock when writing stops. Any partial output remains at "+output+"; do not use a directory marked INCOMPLETE.");});
            startWorker(job,busy,active,"if-history-report");
        });
        editCopy.setOnAction(event->{
            if(busy.get())return;
            var selected=table.getSelectionModel().getSelectedItem();var viewer=qupath.getViewer();
            if(selected==null||viewer==null||viewer.getImageData()==null){status.setText("Open the original slide before creating a review copy.");return;}
            var imageData=viewer.getImageData();var source=IfHistorySource.capture(imageData.getServer());
            Task<IfHistoricalReview.Prepared> job=new Task<>(){
                protected IfHistoricalReview.Prepared call()throws Exception{return IfHistoricalReview.prepare(selected.directory(),source,this::isCancelled);}
            };
            status.setText("Checking history and preparing detached editable copies…");
            job.setOnSucceeded(e->{
                if(!stage.isShowing()||qupath.getViewer()!=viewer||viewer.getImageData()!=imageData||!source.equals(IfHistorySource.capture(imageData.getServer()))){status.setText("Image changed; no review copy added.");return;}
                var prepared=job.getValue();
                var confirm=new Alert(Alert.AlertType.CONFIRMATION,"Add a NEW annotation group with "+prepared.editableCandidates()+" editable candidates? Original results and project objects remain unchanged.\n"
                        +prepared.geometrylessCandidates()+" records have no geometry: retained in provenance only, not counted as negatives.\n"
                        +"Original exclusions are included when geometry exists. Review and remeasure before interpreting positivity. Source matching checks metadata, not original pixel identity.",ButtonType.OK,ButtonType.CANCEL);
                confirm.initOwner(stage);
                if(confirm.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return;
                if(!stage.isShowing()||qupath.getViewer()!=viewer||viewer.getImageData()!=imageData||!source.equals(IfHistorySource.capture(imageData.getServer()))){status.setText("Image changed during confirmation; no copy added.");return;}
                // Attach explicitly at root: do not spatially reparent pre-existing project objects.
                imageData.getHierarchy().getRootObject().addChildObject(prepared.group());
                imageData.getHierarchy().fireHierarchyChangedEvent(IfHistoryPanel.class);viewer.setSelectedObject(prepared.group());
                new IfAnalysisPanel(qupath).show(prepared.entry().profile());
                status.setText("Independent review copy added; historical thresholds restored in review mode. Edit paired annotations and analyze to a NEW output directory. Save the project to retain edits; original history was not changed.");
            });
            job.setOnFailed(e->status.setText("Historical review rejected: "+job.getException().getMessage()));
            job.setOnCancelled(e->status.setText("Review preparation cancelled; waiting for worker cleanup."));
            startWorker(job,busy,active,"if-history-review");
        });
        locate.setOnAction(event->{
            if(busy.get())return;
            var selected=table.getSelectionModel().getSelectedItem();var viewer=qupath.getViewer();
            if(selected==null||viewer==null||viewer.getImageData()==null){status.setText("Open the original slide before locating history.");return;}
            var imageData=viewer.getImageData();var source=IfHistorySource.capture(imageData.getServer());
            boolean withCells=loadCells.isSelected();
            boolean withNuclei=loadNuclei.isSelected();
            boolean withMasks=loadMasks.isSelected();
            boolean withExcluded=loadExcluded.isSelected();
            busy.set(true);status.setText("Checking source locator, reader, channels, calibration and ROI…");
            Task<HistoryView> job=new Task<>(){
                @Override protected HistoryView call() throws Exception{
                    var entry=IfHistorySource.validate(selected.directory(),source,this::isCancelled);
                    var loaded=withCells?IfHistoricalCells.load(entry,this::isCancelled):null;
                    var nuclei=withNuclei?IfHistoricalNuclei.load(entry,this::isCancelled):null;
                    var overlay=nuclei!=null?new IfCellOverlay(imageData,nuclei.outlines(),nuclei.masks(),this::isCancelled):
                            loaded==null?null:new IfCellOverlay(imageData,loaded.cells(),loaded.pnMasks(),this::isCancelled);
                    var masks=withMasks?IfAreaMaskOverlay.readHistoricalIndex(entry,this::isCancelled):null;
                    var exclusions=withExcluded?new IfHistoricalExclusions(IfHistoricalExclusions.load(entry,this::isCancelled),imageData,this::isCancelled):null;
                    return new HistoryView(entry,overlay,masks,exclusions,nuclei==null?-1:nuclei.outlines().size());
                }
            };
            job.setOnSucceeded(e->{
                if(!stage.isShowing()||qupath.getViewer()!=viewer||viewer.getImageData()!=imageData||!source.equals(IfHistorySource.capture(imageData.getServer()))){status.setText("Current image changed; historical ROI was not displayed.");return;}
                clearOverlay.get().run();var result=job.getValue();var roi=result.entry().roi();var shape=RoiTools.getShape(roi);
                var overlay=new qupath.lib.gui.viewer.overlays.PathOverlay(){
                    @Override public void paintOverlay(java.awt.Graphics2D g,qupath.lib.regions.ImageRegion region,double ds,
                            qupath.lib.images.ImageData<java.awt.image.BufferedImage> data,boolean complete){
                        if(data!=imageData||region.getZ()!=roi.getZ()||region.getT()!=roi.getT())return;
                        var copy=(java.awt.Graphics2D)g.create();try{copy.setColor(java.awt.Color.CYAN);copy.setStroke(new java.awt.BasicStroke((float)Math.max(0.5,ds)));copy.draw(shape);}finally{copy.dispose();}
                    }
                };
                javafx.beans.value.ChangeListener<qupath.lib.images.ImageData<java.awt.image.BufferedImage>> listener=(o,a,b)->clearOverlay.get().run();
                clearOverlay.set(()->{viewer.getCustomOverlayLayers().remove(overlay);if(result.cells()!=null)viewer.getCustomOverlayLayers().remove(result.cells());historicalCellOverlay.set(null);maskToken.set(null);var pa=historicalMasks.getAndSet(null);if(pa!=null){viewer.getCustomOverlayLayers().remove(pa);pa.close();}var review=excluded.getAndSet(null);if(review!=null)viewer.getCustomOverlayLayers().remove(review);reasons.getItems().clear();excludedStatus.setText("");maskStatus.setText("");viewer.imageDataProperty().removeListener(listener);viewer.repaint();});
                viewer.imageDataProperty().addListener(listener);viewer.getCustomOverlayLayers().add(overlay);
                phenotype.getItems().clear();phenotype.getItems().add(withNuclei?"All measured nuclei / any PA-positive pixel":"All measured cells / any PA-positive pixel");
                var markers=result.entry().profile().markers();
                for(int bits=1;bits<(1<<markers.size());bits++){var names=new java.util.ArrayList<String>();for(int c=0;c<markers.size();c++)if((bits&(1<<c))!=0)names.add(markers.get(c).name());phenotype.getItems().add(String.join("+",names));}
                phenotype.getSelectionModel().selectFirst();
                if(result.masks()!=null){Object token=new Object();maskToken.set(token);
                    var pa=new IfAreaMaskOverlay(imageData,result.masks(),()->javafx.application.Platform.runLater(()->{if(maskToken.get()==token)viewer.repaint();}),
                            message->javafx.application.Platform.runLater(()->{if(maskToken.get()==token)maskStatus.setText(message);}));
                    historicalMasks.set(pa);pa.setVisible(true);viewer.getCustomOverlayLayers().add(pa);updateCells.run();
                    maskStatus.setText("Historical PA mask index loaded; tiles are verified and decoded on demand. Pixel overlap is not molecular interaction.");
                }
                if(result.cells()!=null){historicalCellOverlay.set(result.cells());viewer.getCustomOverlayLayers().add(result.cells());updateCells.run();}
                if(result.excluded()!=null){var review=result.excluded();excluded.set(review);viewer.getCustomOverlayLayers().add(review);reasons.getItems().setAll("All exclusion reasons");reasons.getItems().addAll(review.reasons());reasons.getSelectionModel().selectFirst();excludedStatus.setText("Excluded records: "+review.total()+"; with outlines: "+review.drawable()+". Orange dashed review shapes, NOT negative cells. No historical measurements modified.");}
                viewer.setZPosition(roi.getZ());viewer.setTPosition(roi.getT());viewer.setCenterPixelLocation(roi.getCentroidX(),roi.getCentroidY());viewer.repaint();
                status.setText("Historical ROI shown (cyan); zoom and project objects unchanged. Locator/metadata match only: unchanged original pixels are NOT authenticated. "+(withCells?"Measured cells loaded: "+result.entry().measuredCells()+"; PN filters available. Excluded cells are NOT negative.":"Cells not requested.")+(withMasks?" PA masks restored.":" PA masks not requested."));
                if(withNuclei)status.setText(status.getText()+" Independent measured nuclei loaded: "+result.nuclearCount()+"; NPN_NUCLEUS_ONLY filters, not cell PN or all DAPI candidates.");
            });
            job.setOnFailed(e->{status.setText("Historical ROI rejected: "+job.getException().getMessage());});
            job.setOnCancelled(e->{status.setText("Historical ROI lookup cancelled. Controls unlock when the worker has stopped.");});
            startWorker(job,busy,active,"if-history-locate");
        });
        stage.setOnHidden(e->{var job=active.get();if(job!=null)job.cancel(false);clearOverlay.get().run();});
        var actions=new javafx.scene.layout.FlowPane(10,8,choose,exportExcel,exportWord,export,locate,editCopy,clearRegion,cancel);
        var opacityControls=new javafx.scene.layout.FlowPane(10,8,new Label("PN/NPN outline opacity / 轮廓不透明度"),outlineOpacity,new Label("PA opacity / 掩膜不透明度"),maskOpacity,new Label("Display only; 0 hides result layer / 仅显示，不改统计；0 隐藏结果层"));
        var root=new VBox(10,actions,new javafx.scene.layout.FlowPane(10,8,loadCells,loadNuclei,loadMasks,phenotype,exact),opacityControls,new javafx.scene.layout.FlowPane(10,8,loadExcluded,reasons,previousExcluded,nextExcluded),status,maskStatus,excludedStatus,table,details);root.setPadding(new javafx.geometry.Insets(12));
        VBox.setVgrow(table,javafx.scene.layout.Priority.ALWAYS);
        VBox.setVgrow(details,javafx.scene.layout.Priority.ALWAYS);
        stage.setScene(new Scene(root,1000,700));stage.show();return stage;
    }
    /** Task cancellation is terminal before call() necessarily exits. Keep launch controls locked until actual exit. */
    static void startWorker(Task<?> job,javafx.beans.property.BooleanProperty busy,
                            java.util.concurrent.atomic.AtomicReference<Task<?>> active,String name) {
        if(!javafx.application.Platform.isFxApplicationThread())throw new IllegalStateException("Launch on FX thread");
        if(!active.compareAndSet(null,job))throw new IllegalStateException("Previous history worker has not stopped");
        busy.set(true);
        var worker=new Thread(()->{
            try{job.run();}
            finally{javafx.application.Platform.runLater(()->{
                if(active.compareAndSet(job,null))busy.set(false);
            });}
        },name);
        worker.setDaemon(true);
        try{worker.start();}catch(RuntimeException|Error e){if(active.compareAndSet(job,null))busy.set(false);throw e;}
    }
    private record HistoryView(IfAnalysisHistory.Entry entry,IfCellOverlay cells,IfAreaMaskOverlay.Dataset masks,IfHistoricalExclusions excluded,int nuclearCount) {}
    private static TableColumn<IfAnalysisHistory.Entry,String> column(String title,java.util.function.Function<IfAnalysisHistory.Entry,String> value) {
        var column=new TableColumn<IfAnalysisHistory.Entry,String>(title);
        column.setCellValueFactory(cell->new javafx.beans.property.ReadOnlyStringWrapper(value.apply(cell.getValue())));
        return column;
    }
}
