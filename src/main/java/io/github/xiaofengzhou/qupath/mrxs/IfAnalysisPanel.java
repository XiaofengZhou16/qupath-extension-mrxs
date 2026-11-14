package io.github.xiaofengzhou.qupath.mrxs;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.PathCellObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Non-modal, cancellable IF analysis command. Does not alter existing detections. */
final class IfAnalysisPanel {
    private final QuPathGUI qupath;
    private final java.util.function.Function<Stage,Path> outputParent;
    private final java.util.function.BiFunction<Stage,Boolean,Path> aiProfileFile;
    private Stage stage;
    private Task<?> task;
    private final java.util.concurrent.atomic.AtomicBoolean workerActive = new java.util.concurrent.atomic.AtomicBoolean();
    private IfCellOverlay overlay;
    private IfAreaMaskOverlay areaOverlay;
    private qupath.lib.gui.viewer.QuPathViewer overlayViewer;

    IfAnalysisPanel(QuPathGUI qupath) { this(qupath,owner->{
        var chooser=new DirectoryChooser();chooser.setTitle("Choose analysis output parent folder");
        var parent=chooser.showDialog(owner);return parent==null?null:parent.toPath();
    }); }
    // Test seam bypasses the native folder dialog only; button handlers and worker lifecycle remain real.
    IfAnalysisPanel(QuPathGUI qupath,java.util.function.Function<Stage,Path> outputParent) {
        this(qupath,outputParent,(owner,save)->{var chooser=profileChooser();chooser.setTitle("AI segmentation settings (not fluorescence thresholds)");
            var file=save?chooser.showSaveDialog(owner):chooser.showOpenDialog(owner);return file==null?null:file.toPath();});
    }
    IfAnalysisPanel(QuPathGUI qupath,java.util.function.Function<Stage,Path> outputParent,java.util.function.BiFunction<Stage,Boolean,Path> aiProfileFile) {
        this.qupath=qupath;this.outputParent=outputParent;this.aiProfileFile=aiProfileFile;
    }

    void show() {
        show(null);
    }
    void show(IfAnalysisProfile historicalProfile) {
        if (stage != null && stage.isShowing()) { stage.toFront(); return; }
        var imageData = qupath.getImageData();
        if (imageData == null || imageData.getServer().isRGB()) {
            error("Open an image with separate fluorescence channels first."); return;
        }
        var server = imageData.getServer();
        stage = new Stage();
        stage.initOwner(qupath.getStage());
        stage.setTitle("Multiplex IF analysis / 多重 IF 分析");
        var help = new Label("Select an area annotation. Use existing cells, classical watershed, or optional InstanSeg AI.\n"
                + "Without cells, PA area statistics are available; PN/NPN/CPN ratios are unavailable, not zero.\n"
                + "PA is pixel overlap; NPN/CPN/PN are cell co-expression, not proof of molecular colocalization.\n"
                + "Raw-intensity thresholds below are independent of display colors/contrast.\n"
                + "DAPI is normally excluded from phenotype combinations. Review thresholds with controls.");
        help.setWrapText(true);
        GridPane grid = new GridPane(); grid.setHgap(12); grid.setVgap(8);
        String[] headings = {"Use", "Channel", "PA pixel ≥", "Nucleus mean ≥", "Cytoplasm mean ≥"};
        for (int c = 0; c < headings.length; c++) grid.add(new Label(headings[c]), c, 0);
        List<MarkerRow> rows = new ArrayList<>();
        for (int c = 0; c < server.nChannels(); c++) {
            String name = server.getMetadata().getChannels().get(c).getName();
            var row = new MarkerRow(c, name); rows.add(row);
            row.use.setSelected(!name.toUpperCase(Locale.ROOT).contains("DAPI") && c < 8);
            grid.addRow(c + 1, row.use, new Label(name), row.pixel, row.nucleus, row.cytoplasm);
        }
        ComboBox<String> resolution = new ComboBox<>();
        for (int i = 0; i < server.nResolutions(); i++) resolution.getItems().add(String.format(
                Locale.ROOT, "Level %d — downsample %.3f", i, server.getDownsampleForResolution(i)));
        resolution.getSelectionModel().select(0);
        resolution.setId("if-analysis-resolution");
        var workloadButton=new Button("Check selected ROI workload / 查看工作量");
        workloadButton.setId("if-analysis-workload");
        var mode = new ComboBox<String>();
        mode.setId("if-analysis-mode");
        mode.getItems().addAll("Existing QuPath cells", "Classical watershed (not AI)", "InstanSeg AI (optional extension)", "Selected editable IF review group");
        mode.getSelectionModel().selectFirst();
        if(historicalProfile!=null){
            if(historicalProfile.resolutionLevel()>=server.nResolutions())throw new IllegalArgumentException("Historical resolution unavailable");
            rows.forEach(row->row.use.setSelected(false));
            for(var marker:historicalProfile.markers()){
                if(marker.channel()>=rows.size()||!rows.get(marker.channel()).name.equals(marker.name()))throw new IllegalArgumentException("Historical channels do not match");
                var row=rows.get(marker.channel());row.use.setSelected(true);
                row.pixel.setText(Double.toString(marker.pixelThreshold()));row.nucleus.setText(Double.toString(marker.nucleusThreshold()));row.cytoplasm.setText(Double.toString(marker.cytoplasmThreshold()));
            }
            resolution.getSelectionModel().select(historicalProfile.resolutionLevel());mode.getSelectionModel().select(3);
        }
        var nuclearChannel = new ComboBox<String>();
        server.getMetadata().getChannels().forEach(c -> nuclearChannel.getItems().add(c.getName()));
        nuclearChannel.getSelectionModel().selectFirst();
        nuclearChannel.getItems().stream().filter(s -> s.toUpperCase(Locale.ROOT).contains("DAPI")).findFirst()
                .ifPresent(s -> nuclearChannel.getSelectionModel().select(s));
        var detectionThreshold = new TextField("30");
        var expansion = new TextField("3");
        var detectionPixelSize = new TextField("0.5");
        var minNucleusArea = new TextField("10");
        var maxNucleusArea = new TextField("400");
        var segmentationGrid = new GridPane(); segmentationGrid.setHgap(10); segmentationGrid.setVgap(6);
        segmentationGrid.addRow(0, new Label("Nuclear channel"), nuclearChannel);
        segmentationGrid.addRow(1, new Label("Detection threshold (after background correction)"), detectionThreshold);
        segmentationGrid.addRow(2, new Label("Detection pixel size µm (0 = native)"), detectionPixelSize);
        segmentationGrid.addRow(3, new Label("Nucleus area min / max µm²"), new HBox(6, minNucleusArea, maxNucleusArea));
        segmentationGrid.addRow(4, new Label("Cell expansion µm"), expansion);
        segmentationGrid.disableProperty().bind(mode.getSelectionModel().selectedIndexProperty().isNotEqualTo(1));
        segmentationGrid.visibleProperty().bind(segmentationGrid.disableProperty().not());
        segmentationGrid.managedProperty().bind(segmentationGrid.visibleProperty());
        ClassLoader extensionLoader = IfInstanSeg.extensionLoader();
        var aiStatus = new Label(IfInstanSeg.isAvailable(extensionLoader)
                ? "InstanSeg found. Choose a trusted fluorescence_nuclei_and_cells model folder."
                : "InstanSeg not found. Install the official extension and DJL, then restart QuPath.");
        aiStatus.setWrapText(true);
        var modelPath = new TextField(); modelPath.setPromptText("Folder containing rdf.yaml and instanseg.pt");
        modelPath.setId("if-analysis-ai-model");
        var chooseModel = new Button("Choose model…");
        chooseModel.setOnAction(e -> {
            var chooser = new DirectoryChooser(); chooser.setTitle("Trusted InstanSeg fluorescence model folder");
            var folder = chooser.showDialog(stage); if (folder != null) modelPath.setText(folder.getAbsolutePath());
        });
        var device = new ComboBox<String>(); device.getItems().addAll("cpu", "mps"); device.getSelectionModel().selectFirst();
        device.setId("if-analysis-ai-device");
        var aiThreads = new ComboBox<Integer>(); aiThreads.getItems().addAll(1,2,3,4); aiThreads.setValue(2);
        aiThreads.setId("if-analysis-ai-threads");
        var aiTileSize = new ComboBox<Integer>(); aiTileSize.getItems().addAll(256,512,1024); aiTileSize.setValue(512);
        aiTileSize.setId("if-analysis-ai-tile-size");
        var aiPadding=new ComboBox<Integer>();aiPadding.getItems().addAll(16,32,48,64,80,96);aiPadding.setValue(32);
        aiPadding.setId("if-analysis-ai-padding");
        aiPadding.setTooltip(new Tooltip("Overlap padding in model-input pixels, not native slide pixels. Larger overlap adds inference work and can change boundary objects; it does not guarantee better accuracy."));
        var aiResources = new HBox(8, new Label("Tile worker threads"), aiThreads,
                new Label("Tile size (pixels)"), aiTileSize,new Label("Padding"),aiPadding);
        var aiResourceHelp = new Label("Defaults: 2 workers, 512-pixel tiles, 32-pixel padding. "
                + "Padding is measured in model-input pixels; increasing it adds overlap and processing, not guaranteed accuracy. "
                + "Larger tiles can use more memory and change segmentation at tile boundaries. "
                + "Worker count does not cap PyTorch's internal threads. Compare results before batch use. "
                + "Cancellation waits for active tiles to finish; DJL may download its runtime on first use.");
        aiResourceHelp.setWrapText(true);
        var aiChannels = new FlowPane(8, 6); List<CheckBox> inputs = new ArrayList<>();
        for (var channel : server.getMetadata().getChannels()) {
            int channelIndex=inputs.size();
            var input = new CheckBox(channel.getName()); input.setSelected(true); inputs.add(input); aiChannels.getChildren().add(input);
            input.setId("if-analysis-ai-channel-"+channelIndex);
        }
        var trustModel = new CheckBox("I trust this local model's source (TorchScript can execute code).");
        trustModel.setId("if-analysis-ai-trust");
        var saveAi=new Button("Save AI settings…");saveAi.setId("if-analysis-ai-save");
        var loadAi=new Button("Load AI settings…");loadAi.setId("if-analysis-ai-load");
        var aiProfileStatus=new Label("AI settings are separate from intensity thresholds. Model path and trust are never saved.");aiProfileStatus.setWrapText(true);
        var channelNames=server.getMetadata().getChannels().stream().map(c->c.getName()).toList();
        saveAi.setOnAction(e->{try{
            if(workerActive.get()||qupath.getImageData()!=imageData)throw new IllegalArgumentException("Keep the source image open and wait for analysis");
            var value=new IfAiSettingsProfile(1,IfAiSettingsProfile.KIND,device.getValue(),aiThreads.getValue(),aiTileSize.getValue(),aiPadding.getValue(),channelNames,
                    java.util.stream.IntStream.range(0,inputs.size()).filter(c->inputs.get(c).isSelected()).boxed().toList());
            var path=aiProfileFile.apply(stage,true);if(path==null)return;
            Files.writeString(path,GsonTools.getInstance(true).toJson(value));aiProfileStatus.setText("AI settings saved; no model path, trust decision or intensity thresholds included.");
        }catch(Exception ex){error(ex.getMessage());}});
        loadAi.setOnAction(e->{try{
            if(workerActive.get()||qupath.getImageData()!=imageData)throw new IllegalArgumentException("Keep the source image open and wait for analysis");
            var path=aiProfileFile.apply(stage,false);if(path==null)return;
            var value=IfAiSettingsProfile.read(path);value.validateChannels(channelNames);
            if(qupath.getImageData()!=imageData)throw new IllegalArgumentException("Source image changed; settings not applied");
            device.setValue(value.device());aiThreads.setValue(value.threads());aiTileSize.setValue(value.tileSize());aiPadding.setValue(value.padding());
            for(int c=0;c<inputs.size();c++)inputs.get(c).setSelected(value.inputChannels().contains(c));
            trustModel.setSelected(false);
            aiProfileStatus.setText("AI settings loaded, not run. Model path unchanged; recheck the model and confirm trust. Intensity thresholds unchanged.");
        }catch(Exception ex){error(ex.getMessage());}});
        var aiSettings = new VBox(6, aiStatus, new HBox(8, modelPath, chooseModel), new Label("Inference device"), device,
                new Label("AI input channels (independent of phenotype markers)"), aiChannels, trustModel,
                aiResources, aiResourceHelp,new HBox(8,saveAi,loadAi),aiProfileStatus);
        aiSettings.disableProperty().bind(mode.getSelectionModel().selectedIndexProperty().isNotEqualTo(2));
        aiSettings.visibleProperty().bind(aiSettings.disableProperty().not());
        aiSettings.managedProperty().bind(aiSettings.visibleProperty());
        var run = new Button("Analyze selected ROI / 分析");
        run.setId("if-analysis-run");
        var exportMasks = new CheckBox("Export PA pixel masks / 导出像素共定位掩膜 (one lossless tile set for all combinations)");
        exportMasks.setSelected(true);
        var cancel = new Button("Cancel / 取消"); cancel.setDisable(true);
        cancel.setId("if-analysis-cancel");
        var save = new Button("Save profile"); var load = new Button("Load profile");
        var status = new Label("Ready. Level 0 is recommended for cell measurements."); status.setWrapText(true);
        status.setId("if-analysis-status");
        var coverage = new Label("Measurement coverage / 有效测量覆盖: awaiting completed analysis.");
        coverage.setId("if-analysis-coverage"); coverage.setWrapText(true);
        var progress = new ProgressBar(0); progress.setMaxWidth(Double.MAX_VALUE);
        var summary = new TextArea(); summary.setEditable(false); summary.setPrefRowCount(10);
        var reviewCopy=new Button("Create editable review copy…");reviewCopy.setDisable(true);
        reviewCopy.setId("if-analysis-create-review");
        var reviewSource=new java.util.concurrent.atomic.AtomicReference<ReviewSource>();
        reviewCopy.setOnAction(e->{try {
            if(workerActive.get() || qupath.getImageData()!=imageData)throw new IllegalArgumentException("Wait for analysis and keep the source image open");
            var source=reviewSource.get();if(source==null)return;
            var confirm=new Alert(Alert.AlertType.CONFIRMATION,
                    "Add a new editable annotation group for "+source.cells().size()+" candidate cells? Original detections remain unchanged. "
                    +"Edit the paired Cell/Nucleus annotations, then select their review group and analyze in review mode. "
                    +"Copies are proposals, not validated ground truth.",ButtonType.OK,ButtonType.CANCEL);
            if(confirm.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return;
            var copyStage=stage;var copyViewer=qupath.getViewer();
            if(!copyStage.isShowing()||copyViewer==null||copyViewer.getImageData()!=imageData)throw new IllegalArgumentException("Image changed during confirmation; no copy added");
            Task<qupath.lib.objects.PathObject> copyJob=new Task<>(){
                protected qupath.lib.objects.PathObject call(){return IfCellReview.create(source.roi(),source.cells(),this::isCancelled,p->updateProgress(p,1));}
            };
            copyJob.setOnSucceeded(done->{
                if(!copyStage.isShowing()||qupath.getViewer()!=copyViewer||copyViewer.getImageData()!=imageData){status.setText("Image/window changed; detached review copy discarded.");return;}
                imageData.getHierarchy().getRootObject().addChildObject(copyJob.getValue());imageData.getHierarchy().fireHierarchyChangedEvent(this);
                copyViewer.setSelectedObject(copyJob.getValue());mode.getSelectionModel().select(3);
                status.setText("Editable copy added. Preserve pair metadata while editing. Select the review group before reanalysis; save the project to retain edits.");
            });
            copyJob.setOnFailed(done->status.setText("Review copy failed: "+copyJob.getException().getMessage()));
            copyJob.setOnCancelled(done->status.setText("Stopping review copy… Waiting for worker cleanup."));
            if(!workerActive.compareAndSet(false,true))throw new IllegalStateException("Wait for the previous worker to stop");
            task=copyJob;run.setDisable(true);reviewCopy.setDisable(true);cancel.setDisable(false);
            progress.progressProperty().unbind();progress.progressProperty().bind(copyJob.progressProperty());status.setText("Preparing editable review in background…");
            Runnable unlock=()->{if(task!=copyJob)return;progress.progressProperty().unbind();run.setDisable(false);reviewCopy.setDisable(false);cancel.setDisable(true);if(copyJob.isCancelled())status.setText("Review copy cancelled; no project objects added.");};
            var worker=new Thread(()->{try{copyJob.run();}finally{javafx.application.Platform.runLater(()->{workerActive.set(false);unlock.run();});}},"qupath-if-review-copy");
            worker.setDaemon(true);try{worker.start();}catch(RuntimeException|Error ex){workerActive.set(false);unlock.run();throw ex;}
        }catch(Exception ex){error(ex.getMessage());}});
        var addManualPair=new Button("Add hand-drawn cell/nucleus pair…");
        addManualPair.setId("if-analysis-add-manual-pair");
        addManualPair.setOnAction(e->{try {
            if(workerActive.get() || qupath.getImageData()!=imageData)throw new IllegalArgumentException("Wait for analysis and keep the source image open");
            var selected=List.copyOf(imageData.getHierarchy().getSelectionModel().getSelectedObjects());
            var groups=selected.stream().filter(o->o.getMetadata().containsKey(IfCellReview.GROUP)).toList();
            if(selected.size()!=3 || groups.size()!=1)throw new IllegalArgumentException("Multi-select one review group and two hand-drawn area annotations in the hierarchy");
            var group=groups.getFirst();var shapes=selected.stream().filter(o->o!=group).toList();
            var copies=IfCellReview.prepareManualPair(group,shapes.get(0),shapes.get(1));
            var confirm=new Alert(Alert.AlertType.CONFIRMATION,"Copy the outer annotation as Cell and inner annotation as Nucleus into this review group? Original annotations remain unchanged.",ButtonType.OK,ButtonType.CANCEL);
            if(confirm.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return;
            group.addChildObjects(copies);imageData.getHierarchy().fireHierarchyChangedEvent(this);
            qupath.getViewer().setSelectedObject(group);mode.getSelectionModel().select(3);
            status.setText("Manual pair added. Review the contours and reanalyze the selected group; save the project to retain edits.");
        }catch(Exception ex){error(ex.getMessage());}});
        var selectReviewGroup=new Button("Select owning review group / 返回复核组");
        selectReviewGroup.setId("if-analysis-select-review-group");
        selectReviewGroup.setOnAction(e->{try{
            if(workerActive.get()||qupath.getImageData()!=imageData)throw new IllegalArgumentException("Wait for analysis and keep the source image open");
            var group=IfCellReview.owningGroup(imageData.getHierarchy().getSelectionModel().getSelectedObject());
            qupath.getViewer().setSelectedObject(group);mode.getSelectionModel().select(3);
            status.setText("Selected the full review group. Reanalysis will use its entire ROI and all paired candidates, not only the previously selected cell or batch.");
        }catch(Exception ex){error(ex.getMessage());}});
        var showOutlines = new CheckBox("Show result cell/nucleus outlines"); showOutlines.setSelected(true);
        var repairNucleus=new Button("Add missing nucleus to review cell…");
        repairNucleus.setId("if-analysis-repair-nucleus");
        repairNucleus.setOnAction(e->{try {
            if(workerActive.get() || qupath.getImageData()!=imageData)throw new IllegalArgumentException("Wait for analysis and keep the source image open");
            var selected=List.copyOf(imageData.getHierarchy().getSelectionModel().getSelectedObjects());
            var groups=selected.stream().filter(o->o.getMetadata().containsKey(IfCellReview.GROUP)).toList();
            var cells=selected.stream().filter(o->"cell".equals(o.getMetadata().get(IfCellReview.COMPARTMENT))).toList();
            if(selected.size()!=3 || groups.size()!=1 || cells.size()!=1)throw new IllegalArgumentException("Multi-select the review group, its paired cell, and one new nuclear annotation");
            var group=groups.getFirst();var cell=cells.getFirst();
            var nucleus=selected.stream().filter(o->o!=group && o!=cell).findFirst().orElseThrow();
            var copy=IfCellReview.prepareMissingNucleus(group,cell,nucleus);
            var confirm=new Alert(Alert.AlertType.CONFIRMATION,"Add a copy of the hand-drawn nucleus to this previously nucleus-free pair? Existing objects will not be overwritten.",ButtonType.OK,ButtonType.CANCEL);
            if(confirm.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return;
            group.addChildObject(copy);imageData.getHierarchy().fireHierarchyChangedEvent(this);
            qupath.getViewer().setSelectedObject(group);mode.getSelectionModel().select(3);
            status.setText("Nuclear copy added. Reanalyze this review group; eligibility and positivity are not assumed until measurement.");
        }catch(Exception ex){error(ex.getMessage());}});
        var reviewRejected = new CheckBox("Review excluded cells only — orange dashed outlines; phenotype filter does not apply");
        reviewRejected.setId("if-analysis-review-rejected");
        var rejectedReason = new ComboBox<String>();
        rejectedReason.setId("if-analysis-rejection-reason");
        rejectedReason.getItems().add("All exclusion reasons / 全部排除原因");
        rejectedReason.getSelectionModel().selectFirst();
        rejectedReason.disableProperty().bind(reviewRejected.selectedProperty().not());
        rejectedReason.setTooltip(new Tooltip("Filter excluded outlines by their recorded first-failure reason; missing cell geometry cannot be drawn. / 按首次失败原因筛选；无细胞轮廓者无法绘制。"));
        var navigation=new java.util.concurrent.atomic.AtomicReference<>(new IfRejectedNavigation(List.of(),List.of()));
        var previousRejected=new Button("Previous / 上一个"); previousRejected.setId("if-analysis-rejected-previous");
        var nextRejected=new Button("Next / 下一个"); nextRejected.setId("if-analysis-rejected-next");
        var navigationStatus=new Label("No review targets / 暂无复核目标");navigationStatus.setWrapText(true);
        navigationStatus.setId("if-analysis-rejected-position");
        previousRejected.disableProperty().bind(reviewRejected.selectedProperty().not().or(run.disabledProperty()));
        nextRejected.disableProperty().bind(previousRejected.disabledProperty());
        java.util.function.IntConsumer navigate=direction->{
            if(workerActive.get() || overlayViewer==null || overlayViewer.getImageData()!=imageData
                    || qupath.getViewer()!=overlayViewer) {
                navigationStatus.setText("Return to the completed analysis image before navigating.");return;
            }
            var target=navigation.get().move(direction);
            if(target==null){navigationStatus.setText("No drawable cells for this reason; see excluded-cells.csv.");return;}
            overlayViewer.setZPosition(target.roi().getZ());overlayViewer.setTPosition(target.roi().getT());
            overlayViewer.setCenterPixelLocation(target.roi().getCentroidX(),target.roi().getCentroidY());
            navigationStatus.setText(navigation.get().position()+" / "+navigation.get().size()+" — "+target.reason()+" — "+target.id());
        };
        previousRejected.setOnAction(e->navigate.accept(-1));nextRejected.setOnAction(e->navigate.accept(1));
        var showArea = new CheckBox("Show PA pixel masks / 显示像素共定位掩膜");
        showArea.setId("if-analysis-show-pa");
        var maskOpacity = new Slider(0,1,0.4);maskOpacity.setPrefWidth(150);
        maskOpacity.setId("if-analysis-mask-opacity");
        var outlineOpacity = new Slider(0,1,1);outlineOpacity.setPrefWidth(150);
        outlineOpacity.setId("if-analysis-outline-opacity");
        outlineOpacity.setTooltip(new Tooltip("Display only; 0 hides outlines without changing measurements. / 仅影响显示，0 隐藏轮廓，不改变测量。"));
        var maskStatus = new Label("PA masks use raw thresholds. Zoom in for pixel-level review; colors identify bitmask classes, not fluorophore LUTs.");maskStatus.setWrapText(true);
        var exact = new CheckBox("Exact phenotype (exclude other selected markers)");
        exact.setId("if-analysis-exact");
        var filter = new ComboBox<String>(); filter.getItems().add("All cells / any PA-positive pixel"); filter.getSelectionModel().selectFirst();
        filter.setId("if-analysis-combination");
        List<Integer> filterMasks = new ArrayList<>(); filterMasks.add(0);
        Runnable updateOverlay = () -> {
            if (overlayViewer == null) return;
            int selected = filter.getSelectionModel().getSelectedIndex();
            int bits=selected >= 0 && selected < filterMasks.size() ? filterMasks.get(selected) : 0;
            if(overlay!=null){overlay.setVisible(showOutlines.isSelected());overlay.setFilter(bits,exact.isSelected());overlay.setRejectedOnly(reviewRejected.isSelected());}
            if(overlay!=null)overlay.setRejectedReason(rejectedReason.getSelectionModel().getSelectedIndex()>0?rejectedReason.getValue():null);
            if(overlay!=null)overlay.setOpacity(outlineOpacity.getValue());
            if(areaOverlay!=null){areaOverlay.setVisible(showArea.isSelected());areaOverlay.setFilter(bits,exact.isSelected());areaOverlay.setOpacity(maskOpacity.getValue());}
            else if(showArea.isSelected())maskStatus.setText("No PA masks for this run. Enable mask export and analyze again.");
            overlayViewer.repaint();
        };
        showOutlines.setOnAction(e -> updateOverlay.run()); exact.setOnAction(e -> updateOverlay.run());
        reviewRejected.setOnAction(e -> updateOverlay.run());
        rejectedReason.setOnAction(e -> {
            navigation.get().filter(rejectedReason.getSelectionModel().getSelectedIndex()>0?rejectedReason.getValue():null);
            navigationStatus.setText(navigation.get().size()+" drawable review targets / 可定位复核对象");
            updateOverlay.run();
        });
        showArea.setOnAction(e -> updateOverlay.run());maskOpacity.valueProperty().addListener((o,a,b)->updateOverlay.run());
        outlineOpacity.valueProperty().addListener((o,a,b)->updateOverlay.run());
        filter.setOnAction(e -> updateOverlay.run());
        var settings = new VBox(10, help, grid, new Label("Fixed analysis resolution (independent of viewer zoom)"),
                resolution, exportMasks, new HBox(8, save, load), new Label("Cell segmentation source"), mode, workloadButton, segmentationGrid, aiSettings,
                new Label("Profile buttons save analysis thresholds only. Each run separately saves full segmentation settings."));
        var batch=new Button("Project AI batch… / 项目 AI 批处理…");
        batch.setId("if-analysis-project-batch");
        batch.setOnAction(e->{try {
            if(workerActive.get()||qupath.getImageData()!=imageData)throw new IllegalArgumentException("Wait for analysis and keep the source image open");
            if(mode.getSelectionModel().getSelectedIndex()!=2||!trustModel.isSelected()||modelPath.getText().isBlank())
                throw new IllegalArgumentException("Select InstanSeg AI and confirm the trusted model first");
            if(!IfInstanSeg.isAvailable(extensionLoader))throw new IllegalArgumentException("InstanSeg extension is not available");
            var config=new IfInstanSeg.Config(Path.of(modelPath.getText().trim()),device.getValue(),aiThreads.getValue(),aiTileSize.getValue(),aiPadding.getValue(),
                    java.util.stream.IntStream.range(0,inputs.size()).filter(c->inputs.get(c).isSelected()).boxed().toList());
            IfBatchPanel.show(qupath,stage,profile(rows,resolution),config,server.getMetadata().getChannels().stream().map(c->c.getName()).toList(),
                    IfDisplaySettings.capture(qupath.getViewer()),exportMasks.isSelected(),extensionLoader);
        }catch(Exception ex){error(ex.getMessage());}});
        var showManualTools = new CheckBox("显示人工修正工具（可选） / Manual correction tools");
        showManualTools.setId("if-analysis-show-manual-tools");
        var manualTools = new VBox(8, reviewCopy, selectReviewGroup, addManualPair, repairNucleus);
        manualTools.setId("if-analysis-manual-tools");
        manualTools.visibleProperty().bind(showManualTools.selectedProperty());
        manualTools.managedProperty().bind(manualTools.visibleProperty());
        showManualTools.setSelected(mode.getSelectionModel().getSelectedIndex()==3);
        mode.getSelectionModel().selectedIndexProperty().addListener((o,oldIndex,newIndex)->{
            if(newIndex.intValue()==3)showManualTools.setSelected(true);
        });
        var root = new VBox(12, settings, coverage,
                new HBox(8,showOutlines,new Label("Outline opacity / 轮廓不透明度"),outlineOpacity), reviewRejected, rejectedReason,new HBox(8,previousRejected,nextRejected),navigationStatus,
                new HBox(8,showArea,new Label("Mask opacity"),maskOpacity),maskStatus,filter, exact, showManualTools, manualTools, summary);
        root.setPadding(new Insets(16));
        var scroll = new ScrollPane(root); scroll.setFitToWidth(true);
        var actions = new FlowPane(8, 8, run, cancel, batch);
        actions.setId("if-analysis-actions");
        var footer = new VBox(8, actions, progress, status);
        footer.setId("if-analysis-footer");footer.setPadding(new Insets(12,16,12,16));
        var layout = new BorderPane(scroll);layout.setBottom(footer);
        stage.setScene(new Scene(layout, 850, 680));
        workloadButton.setOnAction(e->{
            try {
                if(qupath.getImageData()!=imageData)throw new IllegalArgumentException("Image changed; reopen this panel");
                var selected=imageData.getHierarchy().getSelectionModel().getSelectedObject();
                var p=profile(rows,resolution);
                var estimate=IfAnalysisWorkload.estimate(server.getWidth(),server.getHeight(),selected==null?null:selected.getROI(),
                        server.getDownsampleForResolution(p.resolutionLevel()),p.markers().size());
                int index=mode.getSelectionModel().getSelectedIndex();
                var dialog=new Alert(Alert.AlertType.INFORMATION,estimate.description(index==1 || index==2),ButtonType.OK);
                dialog.initOwner(stage);dialog.setTitle("IF analysis workload");dialog.setHeaderText("No image pixels read");dialog.showAndWait();
            }catch(Exception ex){error(ex.getMessage());}
        });
        save.setOnAction(e -> {
            try {
                if (workerActive.get()) throw new IllegalArgumentException("Previous analysis is still stopping. Please wait.");
                var profile = profile(rows, resolution);
                var file = profileChooser().showSaveDialog(stage);
                if (file != null) Files.writeString(file.toPath(), GsonTools.getInstance(true).toJson(profile));
            } catch (Exception ex) { error(ex.getMessage()); }
        });
        load.setOnAction(e -> {
            var file = profileChooser().showOpenDialog(stage);
            if (file == null) return;
            try {
                var p = GsonTools.getInstance().fromJson(Files.readString(file.toPath()), IfAnalysisProfile.class);
                if (p == null || p.resolutionLevel() >= server.nResolutions()) throw new IllegalArgumentException("Invalid resolution");
                for (var marker : p.markers()) if (marker.channel() >= rows.size()
                        || !rows.get(marker.channel()).name.equals(marker.name()))
                    throw new IllegalArgumentException("Saved channel mapping does not match this image");
                rows.forEach(row -> row.use.setSelected(false));
                for (var marker : p.markers()) {
                    var row = rows.get(marker.channel()); row.use.setSelected(true);
                    row.pixel.setText(Double.toString(marker.pixelThreshold()));
                    row.nucleus.setText(Double.toString(marker.nucleusThreshold()));
                    row.cytoplasm.setText(Double.toString(marker.cytoplasmThreshold()));
                }
                resolution.getSelectionModel().select(p.resolutionLevel());
            } catch (Exception ex) { error("Cannot load profile: " + ex.getMessage()); }
        });
        run.setOnAction(e -> {
            try {
                if (workerActive.get()) throw new IllegalArgumentException("Previous analysis is still stopping. Please wait.");
                if (qupath.getImageData() != imageData) throw new IllegalArgumentException("Image changed. Close and reopen this panel.");
                var selected = imageData.getHierarchy().getSelectionModel().getSelectedObject();
                if (selected == null || !selected.isAnnotation() || selected.getROI() == null || !selected.getROI().isArea())
                    throw new IllegalArgumentException("Select an area annotation (not a cell) first");
                var roi = selected.getROI();
                var profile = profile(rows, resolution);
                boolean reviewMode=mode.getSelectionModel().getSelectedIndex()==3;
                var reviewSnapshot=reviewMode?IfCellReview.capture(selected):null;
                var cells = reviewMode ? reviewSnapshot.cells() : imageData.getHierarchy().getDetectionObjects().stream()
                        .filter(PathCellObject.class::isInstance).map(PathCellObject.class::cast)
                        .map(IfAnalysis.Cell::snapshot).toList();
                var segmentationConfig = mode.getSelectionModel().getSelectedIndex() == 1 ? new IfSegmentation.Config(nuclearChannel.getValue(),
                        Double.parseDouble(detectionThreshold.getText().trim()), Double.parseDouble(detectionPixelSize.getText().trim()),
                        Double.parseDouble(minNucleusArea.getText().trim()), Double.parseDouble(maxNucleusArea.getText().trim()),
                        Double.parseDouble(expansion.getText().trim())) : null;
                IfInstanSeg.Config aiConfig = null;
                if (mode.getSelectionModel().getSelectedIndex() == 2) {
                    if (!IfInstanSeg.isAvailable(extensionLoader)) throw new IllegalArgumentException("InstanSeg extension is not available");
                    if (!trustModel.isSelected()) throw new IllegalArgumentException("Confirm the model is from a trusted source before running");
                    if (modelPath.getText().isBlank()) throw new IllegalArgumentException("Choose the model folder");
                    aiConfig = new IfInstanSeg.Config(Path.of(modelPath.getText().trim()), device.getValue(), aiThreads.getValue(), aiTileSize.getValue(), aiPadding.getValue(),
                            java.util.stream.IntStream.range(0, inputs.size()).filter(c -> inputs.get(c).isSelected()).boxed().toList());
                }
                var selectedAiConfig = aiConfig;
                var workload=IfAnalysisWorkload.estimate(server.getWidth(),server.getHeight(),roi,
                        server.getDownsampleForResolution(profile.resolutionLevel()),profile.markers().size());
                boolean segmentationRequested=segmentationConfig!=null || selectedAiConfig!=null;
                if(workload.requiresConfirmation(segmentationRequested)) {
                    var confirmation=new Alert(Alert.AlertType.CONFIRMATION,workload.description(segmentationRequested)
                            +"\nContinue with the selected ROI and settings?",ButtonType.OK,ButtonType.CANCEL);
                    confirmation.initOwner(stage);confirmation.setTitle("Large IF analysis");
                    confirmation.setHeaderText("Large region: consider testing a smaller ROI first");
                    if(confirmation.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return;
                }
                boolean writeMasks=exportMasks.isSelected();
                var parent = outputParent.apply(stage); if (parent == null) return;
                Path output = parent.resolve("if-analysis-" + LocalDateTime.now().format(
                        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + UUID.randomUUID().toString().substring(0, 8));
                var sourceViewer = qupath.getViewer();
                // Snapshot display state on the FX thread; background figures never read mutable viewer settings.
                var reportDisplay = IfDisplaySettings.capture(sourceViewer);
                Task<AnalysisRun> job = new Task<>() {
                    @Override protected AnalysisRun call() throws Exception {
                        var inputs = cells;
                        java.util.Map<String, Object> provenance = reviewMode
                                ? reviewSnapshot.provenance()
                                : java.util.Map.of("method", "existing QuPath cell objects; upstream method not inferred");
                        if (selectedAiConfig != null) {
                            updateMessage("InstanSeg AI inference (first run may initialize/download DJL runtime)…"); updateProgress(-1,1);
                            var segmentation = IfInstanSeg.detect(server, roi, selectedAiConfig, extensionLoader,
                                    this::isCancelled, v -> updateProgress(v,1));
                            inputs = segmentation.cells(); provenance = segmentation.provenance();
                        } else if (segmentationConfig != null) {
                            updateMessage("Segmenting nuclei (classical watershed)…"); updateProgress(-1, 1);
                            var segmentation = IfSegmentation.detect(server, roi, segmentationConfig, this::isCancelled);
                            inputs = segmentation.cells(); provenance = segmentation.provenance();
                        }
                        updateMessage("Measuring raw channels…");
                        var result = IfAnalysisExport.analyzeAndWrite(output, server, roi, profile, inputs, provenance,
                                writeMasks, reportDisplay, this::isCancelled, v -> updateProgress(v, 1));
                        var masks=writeMasks?IfAreaMaskOverlay.readIndex(output,result,roi.getZ(),roi.getT(),this::isCancelled):null;
                        updateMessage("Preparing viewport-indexed outlines in background…");
                        var preparedOverlay=new IfCellOverlay(imageData,inputs,result,this::isCancelled);
                        var preparedNavigation=new IfRejectedNavigation(inputs,result.rejectedCells(),this::isCancelled);
                        if(isCancelled())throw new java.util.concurrent.CancellationException();
                        return new AnalysisRun(result, inputs, masks,preparedOverlay,preparedNavigation);
                    }
                };
                task = job;
                settings.setDisable(true); run.setDisable(true); cancel.setDisable(false);reviewCopy.setDisable(true);
                coverage.setText("Measurement coverage / 有效测量覆盖: awaiting this analysis; previous results are not current.");
                navigation.set(new IfRejectedNavigation(List.of(),List.of()));
                navigationStatus.setText("Awaiting this analysis / 等待本次分析");
                status.setText("Analyzing raw tiles. You can continue using the viewer.");
                progress.progressProperty().bind(job.progressProperty());
                job.messageProperty().addListener((observable, oldValue, value) -> {
                    if (!job.isCancelled()) status.setText(value);
                });
                job.setOnSucceeded(done -> {
                    var r = job.getValue().result();
                    reviewSource.set(new ReviewSource(roi,job.getValue().cells()));
                    reviewCopy.setDisable(job.getValue().cells().isEmpty());
                    clearOverlay();
                    navigation.set(job.getValue().navigation());
                    rejectedReason.getItems().setAll("All exclusion reasons / 全部排除原因");
                    rejectedReason.getItems().addAll(r.rejectedCells().stream().map(IfAnalysis.RejectedCell::reason).distinct().sorted().toList());
                    rejectedReason.getSelectionModel().selectFirst();
                    if (stage.isShowing() && sourceViewer.getImageData() == imageData) {
                        overlayViewer = sourceViewer;
                        overlay = job.getValue().overlay();
                        if(job.getValue().masks()!=null) {
                            areaOverlay=new IfAreaMaskOverlay(imageData,job.getValue().masks(),
                                    ()->javafx.application.Platform.runLater(sourceViewer::repaint),
                                    message->javafx.application.Platform.runLater(()->maskStatus.setText(message)));
                            overlayViewer.getCustomOverlayLayers().add(areaOverlay);
                        }
                        overlayViewer.getCustomOverlayLayers().add(overlay);
                        filterMasks.clear(); filterMasks.add(0);
                        filter.getItems().setAll("All cells / any PA-positive pixel");
                        for (var c : r.combinations()) { filterMasks.add(c.bits()); filter.getItems().add(c.markers()); }
                        filter.getSelectionModel().selectFirst(); updateOverlay.run();
                    }
                    status.setText("Complete: " + output);
                    coverage.setText(IfMeasurementCoverage.from(r).displayText());
                    StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
                            "Sampled ROI pixels: %d; eligible cells: %d; excluded: %d; unmeasurable: %d%n"
                                    + "Read %d tiles in %.3f s; counted %d index-certified zero tiles without reading. All results and parameters saved.%n%n"
                                    + "Inclusive combinations (AB includes ABC); exact combinations are separate in exported tables.%n"
                                    + "PA uses sampled ROI pixels, not tissue-only pixels. Cell co-expression does not imply molecular interaction.%n"
                                    + "Combination\tPA pixels\tPN cells\tNPN cells\tCPN cells%n",
                            r.roiPixels(), r.cells().size(), r.excludedCells(), r.unmeasurableCells(), r.tilesRead(), r.elapsedSeconds(),r.zeroTilesCounted()));
                    for (var c : r.combinations()) text.append(c.markers()).append('\t').append(c.paPixels())
                            .append('\t').append(c.pn()).append('\t').append(c.npn()).append('\t').append(c.cpn()).append('\n');
                    text.append("\nExcluded-cell first-failure reasons (not negative phenotypes):\n");
                    r.rejectedCells().stream().collect(java.util.stream.Collectors.groupingBy(IfAnalysis.RejectedCell::reason,
                            java.util.TreeMap::new,java.util.stream.Collectors.counting()))
                            .forEach((reason,count)->text.append(reason).append(": ").append(count).append('\n'));
                    text.append("Objects without drawable cell ROIs cannot have outlines; see excluded-cells.csv.\n");
                    text.append("\nIndependent nucleus-only measurements: ").append(r.nuclearMeasurements().nuclei().size())
                            .append(" measured; ").append(r.nuclearMeasurements().rejected().size()).append(" excluded.\n")
                            .append("See nuclei.csv and nuclear-combinations.csv. This population does not require valid cytoplasm; it is not all DAPI proposals.\n")
                            .append("Legacy NPN/CPN/PN tables, overlays and Word/Excel reports retain the fully eligible-cell denominator.\n");
                    text.append("Word/Excel batch reports also include the independent NPN_NUCLEUS_ONLY metric with its separate denominator.\n");
                    text.append("Combination\tNucleus-only positive (inclusive)\tMeasured nuclei denominator\n");
                    for(var c:r.nuclearMeasurements().combinations())text.append(c.markers()).append('\t')
                            .append(c.inclusive()).append('\t').append(r.nuclearMeasurements().nuclei().size()).append('\n');
                    summary.setText(text.toString());
                });
                job.setOnFailed(done -> {
                    coverage.setText("Measurement coverage unavailable: analysis failed.");
                    status.setText("Failed. Any output with INCOMPLETE must not be used."); error(job.getException().getMessage()); });
                job.setOnCancelled(done -> {
                    coverage.setText("Measurement coverage unavailable: analysis cancelled.");
                    cancel.setDisable(true);
                    status.setText(workerActive.get()
                            ? "Stopping analysis… Waiting for the background worker to exit."
                            : "Cancelled. Any output with INCOMPLETE must not be used.");
                });
                if (!workerActive.compareAndSet(false, true)) throw new IllegalStateException("Analysis is already running");
                var worker = new Thread(() -> {
                    try { job.run(); }
                    finally {
                        workerActive.set(false);
                        javafx.application.Platform.runLater(() -> {
                            if (task != job) return;
                            finish(settings, run, cancel, progress);
                            if (job.isCancelled()) status.setText(
                                    "Cancelled. Any output with INCOMPLETE must not be used.");
                        });
                    }
                }, "qupath-if-analysis");
                worker.setDaemon(true); worker.start();
            } catch (Exception ex) { error(ex.getMessage()); }
        });
        // MRXS FileChannels are shared with the viewer: interruption can close a channel
        // inside an active read. Request cooperative cancellation; finally gates retry.
        cancel.setOnAction(e -> { if (task != null) task.cancel(false); });
        stage.setOnCloseRequest(e -> { if (task != null && !task.isDone()) task.cancel(false); clearOverlay(); });
        stage.show();
    }

    private record AnalysisRun(IfAnalysis.Result result, List<IfAnalysis.Cell> cells, IfAreaMaskOverlay.Dataset masks,
                               IfCellOverlay overlay,IfRejectedNavigation navigation) {}
    private record ReviewSource(qupath.lib.roi.interfaces.ROI roi,List<IfAnalysis.Cell> cells) {}

    private void clearOverlay() {
        if(areaOverlay!=null){if(overlayViewer!=null)overlayViewer.getCustomOverlayLayers().remove(areaOverlay);areaOverlay.close();areaOverlay=null;}
        if (overlayViewer != null && overlay != null) {
            overlayViewer.getCustomOverlayLayers().remove(overlay); overlayViewer.repaint();
        }
        overlay = null; overlayViewer = null;
    }

    private static IfAnalysisProfile profile(List<MarkerRow> rows, ComboBox<String> resolution) {
        var markers = rows.stream().filter(row -> row.use.isSelected()).map(row -> new IfAnalysisProfile.Marker(
                row.channel, row.name, Double.parseDouble(row.pixel.getText().trim()),
                Double.parseDouble(row.nucleus.getText().trim()), Double.parseDouble(row.cytoplasm.getText().trim()))).toList();
        return new IfAnalysisProfile(1, resolution.getSelectionModel().getSelectedIndex(), markers);
    }
    private static FileChooser profileChooser() {
        var chooser = new FileChooser(); chooser.setTitle("IF analysis profile");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON profile", "*.json")); return chooser;
    }
    private static void finish(VBox settings, Button run, Button cancel, ProgressBar progress) {
        progress.progressProperty().unbind(); settings.setDisable(false); run.setDisable(false); cancel.setDisable(true);
    }
    private static void error(String message) {
        var alert = new Alert(Alert.AlertType.ERROR, message == null ? "Unknown error" : message, ButtonType.OK);
        alert.setHeaderText("IF analysis"); alert.showAndWait();
    }
    private static final class MarkerRow {
        final int channel; final String name;
        final CheckBox use = new CheckBox();
        final TextField pixel = new TextField("30"), nucleus = new TextField("30"), cytoplasm = new TextField("30");
        MarkerRow(int channel, String name) {
            this.channel = channel; this.name = name;
            use.setId("if-analysis-use-"+channel);pixel.setId("if-analysis-pixel-"+channel);
            nucleus.setId("if-analysis-nucleus-"+channel);cytoplasm.setId("if-analysis-cytoplasm-"+channel);
            for (var field : List.of(pixel, nucleus, cytoplasm)) field.setPrefColumnCount(7);
        }
    }
}
