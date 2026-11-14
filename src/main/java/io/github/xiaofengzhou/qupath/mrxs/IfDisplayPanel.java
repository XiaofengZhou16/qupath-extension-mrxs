package io.github.xiaofengzhou.qupath.mrxs;

import javafx.animation.PauseTransition;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.io.GsonTools;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.regions.RegionRequest;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local LUT workbench and source-pixel export; never captures the desktop or alters raw intensities. */
final class IfDisplayPanel {
    private final QuPathGUI qupath;
    private final java.util.function.Function<Stage,Path> outputParent;
    private Stage stage;
    private final AtomicBoolean exportActive=new AtomicBoolean();
    IfDisplayPanel(QuPathGUI qupath){this(qupath,owner->{
        var chooser=new DirectoryChooser();chooser.setTitle("Choose output parent folder");
        var folder=chooser.showDialog(owner);return folder==null?null:folder.toPath();
    });}
    IfDisplayPanel(QuPathGUI qupath,java.util.function.Function<Stage,Path> outputParent){this.qupath=qupath;this.outputParent=outputParent;}

    void show() {
        if(stage!=null && stage.isShowing()){stage.toFront();return;}
        var viewer=qupath.getViewer();
        IfDisplayProfile initial;
        try {initial=IfDisplaySettings.capture(viewer);} catch(Exception e){error(e);return;}
        var data=viewer.getImageData();var server=data.getServer();
        stage=new Stage(); stage.initOwner(qupath.getStage());stage.setTitle("IF display & publication export / 调色与导出");
        var status=new Label("Display only. Raw intensities and IF measurements are unchanged.");status.setWrapText(true);
        status.setId("if-publication-status");
        var grid=new GridPane();grid.setHgap(10);grid.setVgap(8);
        grid.addRow(0,new Label("Show"),new Label("Source channel"),new Label("Color"),new Label("Minimum"),new Label("Maximum"));
        List<Row> rows=new ArrayList<>();
        for(var c:initial.channels()) {
            var row=new Row(c); rows.add(row);
            grid.addRow(rows.size(),row.selected,new Label(c.name()),row.color,row.minBox,row.maxBox);
        }
        var gamma=new TextField(Double.toString(initial.gamma()));gamma.setPrefColumnCount(5);gamma.setId("if-display-gamma");
        var grayscale=new CheckBox("Grayscale LUT");grayscale.setSelected(initial.grayscale());
        var inverted=new CheckBox("Inverted display");inverted.setSelected(initial.inverted());
        var save=new Button("Save display profile");var load=new Button("Load display profile");var reset=new Button("Restore opening settings");
        var refresh=new Button("Preview selected ROI / visible region");
        var previews=new FlowPane(10,10);
        var previewQc=new Label("Preview display-range QC: refresh a region to calculate.");
        previewQc.setId("if-display-preview-qc");previewQc.setWrapText(true);
        var previewCaption=new Label("Bounding-box previews share one source region. Export masks outside the annotation. Refresh after changing ROI.");previewCaption.setWrapText(true);
        var previewPool=new java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(1),
                r->{var t=new Thread(r,"if-display-preview");t.setDaemon(true);return t;},
                new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
        var previewRaw=new BufferedImage[1]; var previewJob=new Task<?>[1];var previewDescription=new String[]{""};
        final boolean[] changing={false};
        java.util.function.Supplier<IfDisplayProfile> capture=()->new IfDisplayProfile(1,Double.parseDouble(gamma.getText().trim()),
                grayscale.isSelected(),inverted.isSelected(),rows.stream().map(Row::state).toList());
        var exportLevel=new ComboBox<String>();
        for(int i=0;i<server.nResolutions();i++)exportLevel.getItems().add("Native level "+i+" — downsample "+server.getDownsampleForResolution(i));
        exportLevel.getSelectionModel().selectFirst();
        var dpi=new ComboBox<Integer>();dpi.getItems().addAll(300,600);dpi.getSelectionModel().selectFirst();
        var font=new TextField("8");font.setPrefColumnCount(5);
        var all=new CheckBox("Export every nonempty combination");all.setSelected(true);
        var scale=new CheckBox("Calibrated scale bar");scale.setSelected(server.getPixelCalibration().hasPixelSizeMicrons());
        var scaleLength=new TextField("0");scaleLength.setPrefColumnCount(6);
        var planLabel=new Label("Select an area annotation, then check export dimensions.");planLabel.setWrapText(true);
        java.util.function.Supplier<IfPublicationExport.Options> options=()->new IfPublicationExport.Options(
                exportLevel.getSelectionModel().getSelectedIndex(),dpi.getValue(),Double.parseDouble(font.getText().trim()),
                all.isSelected(),scale.isSelected(),Double.parseDouble(scaleLength.getText().trim()));
        Runnable updatePlan=()-> {
            try {
                var p=IfPublicationExport.plan(server,selectedROI(viewer),capture.get(),options.get());
                planLabel.setText(String.format(Locale.ROOT,"Image area ≈ %d × %d source-derived pixels; %.2f × %.2f cm at %d DPI.\n"
                                +"%d combinations, %d PNGs (clean + labeled). Labels add margins; image pixels are not enlarged.\nEstimated working buffers: %.1f MiB.",
                        p.estimatedWidth(),p.estimatedHeight(),p.estimatedWidth()*2.54/dpi.getValue(),p.estimatedHeight()*2.54/dpi.getValue(),
                        dpi.getValue(),p.combinations(),p.combinations()*2,p.estimatedWorkingBytes()/1048576.0));
            }catch(Exception e){planLabel.setText(e.getMessage());}
        };
        java.util.function.Consumer<Boolean> preview=readRegion-> {
            if(viewer.getImageData()!=data || (!readRegion && previewRaw[0]==null))return;
            try {
                var snapshot=capture.get();var cached=readRegion?null:previewRaw[0];var cachedDescription=previewDescription[0];
                ROI region;
                if(readRegion) {
                    var selected=viewer.getSelectedObject();
                    if(selected!=null && selected.hasROI() && selected.getROI().isArea())region=selected.getROI();
                    else {var b=viewer.getDisplayedRegionShape().getBounds2D();region=ROIs.createRectangleROI(
                            Math.max(0,b.getX()),Math.max(0,b.getY()),Math.min(b.getWidth(),server.getWidth()-Math.max(0,b.getX())),
                            Math.min(b.getHeight(),server.getHeight()-Math.max(0,b.getY())),viewer.getImagePlane());}
                }else region=null;
                if(previewJob[0]!=null)previewJob[0].cancel(false);
                previewQc.setText("Calculating preview QC… previous preview may show older settings.");
                Task<Preview> job=new Task<>() {
                    @Override protected Preview call() throws Exception {
                        var raw=cached;String description=cachedDescription;
                        if(raw==null) {
                            int level=0;
                            while(level+1<server.nResolutions() && Math.max(region.getBoundsWidth(),region.getBoundsHeight())/server.getDownsampleForResolution(level)>512)level++;
                            double ds=server.getDownsampleForResolution(level);
                            if(Math.ceil(region.getBoundsWidth()/ds)*Math.ceil(region.getBoundsHeight()/ds)>1_048_576)
                                throw new IllegalArgumentException("Preview region too large for available pyramid; select a smaller ROI");
                            raw=server.readRegion(RegionRequest.createInstance(server.getPath(),ds,region));
                            description=String.format(Locale.ROOT,"Bounding box x=%.0f y=%.0f w=%.0f h=%.0f source px; preview downsample=%.3f. Export masks outside the ROI. Refresh after ROI changes.",
                                    region.getBoundsX(),region.getBoundsY(),region.getBoundsWidth(),region.getBoundsHeight(),ds);
                        }
                        if(isCancelled())throw new java.util.concurrent.CancellationException();
                        var panels=new ArrayList<PreviewItem>();
                        var selected=snapshot.selectedChannels();
                        var quality=IfDisplayQuality.measurePreview(raw,snapshot,this::isCancelled);
                        var renderer=IfDisplaySettings.prepare(server,snapshot);
                        panels.add(new PreviewItem("Merged",renderer.render(raw,selected.stream().map(IfDisplayProfile.Channel::index).toList())));
                        for(var c:selected) {
                            if(isCancelled())throw new java.util.concurrent.CancellationException();
                            panels.add(new PreviewItem(c.name(),renderer.render(raw,List.of(c.index()))));
                        }
                        return new Preview(raw,panels,description,IfDisplayQuality.previewSummary(quality));
                    }
                };
                previewJob[0]=job;
                job.setOnSucceeded(e->{if(previewJob[0]!=job || viewer.getImageData()!=data || !stage.isShowing())return;
                    previewRaw[0]=job.getValue().raw();previews.getChildren().clear();
                    previewDescription[0]=job.getValue().description();previewCaption.setText(previewDescription[0]);
                    previewQc.setText(job.getValue().qualitySummary());
                    for(var item:job.getValue().items()) {
                        var view=new ImageView(fxImage(item.image()));view.setPreserveRatio(true);view.setFitWidth(220);view.setFitHeight(180);view.setSmooth(false);
                        previews.getChildren().add(new VBox(4,new Label(item.name()),view));
                    }
                });
                job.setOnFailed(e->{if(previewJob[0]==job && stage.isShowing() && viewer.getImageData()==data) {
                    previewQc.setText("Preview QC unavailable; previous images may be outdated.");
                    status.setText("Preview failed: "+job.getException().getMessage());}});previewPool.execute(job);
            }catch(Exception e){status.setText(e.getMessage());}
        };
        var debounce=new PauseTransition(Duration.millis(80));
        Runnable apply=()-> {
            if(changing[0])return;
            if(viewer.getImageData()!=data){status.setText("Source image changed. Reopen this panel.");return;}
            try {IfDisplaySettings.apply(viewer,capture.get());status.setText("Display updated; analysis values unchanged.");updatePlan.run();preview.accept(false);}
            catch(Exception e){status.setText("Not applied: "+e.getMessage());}
        };
        debounce.setOnFinished(e->apply.run());Runnable schedule=()->{if(!changing[0])debounce.playFromStart();};
        for(var row:rows)row.listen(schedule);
        gamma.textProperty().addListener((o,a,b)->schedule.run());grayscale.setOnAction(e->schedule.run());inverted.setOnAction(e->schedule.run());
        java.util.function.Consumer<IfDisplayProfile> populate=profile->{
            profile.validateFor(server);changing[0]=true;
            try {for(var row:rows)row.populate(profile.channels().stream().filter(c->c.index()==row.index).findFirst().orElseThrow());
                gamma.setText(Double.toString(profile.gamma()));grayscale.setSelected(profile.grayscale());inverted.setSelected(profile.inverted());}
            finally{changing[0]=false;}apply.run();
        };
        save.setOnAction(e->{try{var p=capture.get();var file=chooser().showSaveDialog(stage);if(file!=null)Files.writeString(file.toPath(),GsonTools.getInstance(true).toJson(p));}catch(Exception ex){error(ex);}});
        load.setOnAction(e->{try{var file=chooser().showOpenDialog(stage);if(file!=null)populate.accept(GsonTools.getInstance().fromJson(Files.readString(file.toPath()),IfDisplayProfile.class));}catch(Exception ex){error(ex);}});
        reset.setOnAction(e->populate.accept(initial));refresh.setOnAction(e->{preview.accept(true);updatePlan.run();});
        var checkDimensions=new Button("Check selected ROI dimensions");checkDimensions.setOnAction(e->updatePlan.run());
        exportLevel.setOnAction(e->updatePlan.run());dpi.setOnAction(e->updatePlan.run());all.setOnAction(e->updatePlan.run());
        var run=new Button("Export publication panels…");var cancel=new Button("Cancel export");cancel.setDisable(true);
        run.setId("if-publication-run");cancel.setId("if-publication-cancel");
        var progress=new ProgressBar(0);progress.setMaxWidth(Double.MAX_VALUE);var exportJob=new Task<?>[1];
        var displayControls=new VBox(10,grid,new HBox(10,new Label("Gamma"),gamma,grayscale,inverted),new HBox(8,save,load,reset));
        var exportControls=new VBox(8,new Label("Native export resolution"),exportLevel,
                new HBox(10,new Label("DPI"),dpi,new Label("Label font (pt)"),font),all,
                new HBox(8,scale,new Label("Length µm (0 = automatic)"),scaleLength),checkDimensions,planLabel);
        run.setOnAction(e->{
            try {
                if(exportActive.get())throw new IllegalStateException("Previous export is still stopping");
                if(viewer.getImageData()!=data)throw new IllegalStateException("Source image changed");
                var roi=selectedROI(viewer);var profile=capture.get();var settings=options.get();
                var plan=IfPublicationExport.plan(server,roi,profile,settings);updatePlan.run();
                if(plan.combinations()>31) {
                    var confirm=new Alert(Alert.AlertType.CONFIRMATION,"This creates "+(2*plan.combinations())+" PNG files. Continue?",ButtonType.OK,ButtonType.CANCEL);
                    if(confirm.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return;
                }
                var parent=outputParent.apply(stage);if(parent==null)return;
                Path output=parent.resolve("if-figure-"+UUID.randomUUID().toString().substring(0,12));
                Task<Path> job=new Task<>(){protected Path call()throws Exception{return IfPublicationExport.export(server,roi,profile,settings,output,this::isCancelled,v->updateProgress(v,1));}};
                if(!exportActive.compareAndSet(false,true))throw new IllegalStateException("Export already running");
                exportJob[0]=job;displayControls.setDisable(true);exportControls.setDisable(true);run.setDisable(true);cancel.setDisable(false);
                progress.progressProperty().bind(job.progressProperty());status.setText("Exporting from source pixels, not the screen…");
                Runnable finish=()->{progress.progressProperty().unbind();displayControls.setDisable(false);exportControls.setDisable(false);run.setDisable(false);cancel.setDisable(true);};
                job.setOnSucceeded(done->{status.setText("Complete: "+output+"\nReview display-qc.txt for per-channel display-range clipping; this is not a sensor-saturation test.");});
                job.setOnFailed(done->{status.setText("Failed: "+job.getException().getMessage()+" — do not use INCOMPLETE output.");});
                job.setOnCancelled(done->{cancel.setDisable(true);status.setText(exportActive.get()
                        ? "Stopping export… Waiting for the active source read/write to finish."
                        : "Cancelled. Do not use any output carrying INCOMPLETE.");});
                var worker=new Thread(()->{try{job.run();}finally{
                    exportActive.set(false);
                    javafx.application.Platform.runLater(()->{
                        if(exportJob[0]!=job)return;
                        finish.run();
                        if(job.isCancelled())status.setText("Cancelled. Do not use any output carrying INCOMPLETE.");
                    });
                }},"if-publication-export");worker.setDaemon(true);worker.start();
            }catch(Exception ex){error(ex);}
        });
        cancel.setOnAction(e->{if(exportJob[0]!=null)exportJob[0].cancel(false);});
        var displayTab=new Tab("Live color & synchronized previews",new VBox(10,displayControls,refresh,previewCaption,previewQc,previews));
        var exportTab=new Tab("Publication export",new VBox(10,exportControls,new HBox(8,run,cancel),progress));
        displayTab.setClosable(false);exportTab.setClosable(false);
        var tabs=new TabPane(displayTab,exportTab);
        var heading=new Label("Bound to: "+server.getMetadata().getName()+"\nUse consistent ranges across comparison groups. Clean PNGs are display composites, not raw intensities.");heading.setWrapText(true);
        var root=new VBox(12,heading,tabs,status);root.setPadding(new Insets(16));var scroll=new ScrollPane(root);scroll.setFitToWidth(true);
        stage.setScene(new Scene(scroll,1050,760));
        stage.setOnCloseRequest(e->{debounce.stop();if(previewJob[0]!=null)previewJob[0].cancel(false);
            previewPool.getQueue().clear();previewPool.shutdown();if(exportJob[0]!=null)exportJob[0].cancel(false);});
        stage.show();
    }

    private static ROI selectedROI(QuPathViewer viewer) {
        var selected=viewer.getSelectedObject();
        if(selected==null || !selected.isAnnotation() || !selected.hasROI() || !selected.getROI().isArea())
            throw new IllegalArgumentException("Select an area annotation for publication export");
        return selected.getROI();
    }
    private static FileChooser chooser(){var c=new FileChooser();c.setTitle("IF display profile");c.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON display profile","*.json"));return c;}
    private static void error(Exception e){new Alert(Alert.AlertType.ERROR,e.getMessage()==null?e.toString():e.getMessage(),ButtonType.OK).showAndWait();}
    private static WritableImage fxImage(BufferedImage image) {
        var fx=new WritableImage(image.getWidth(),image.getHeight());
        int[] pixels=image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth());
        fx.getPixelWriter().setPixels(0,0,image.getWidth(),image.getHeight(),PixelFormat.getIntArgbInstance(),pixels,0,image.getWidth());return fx;
    }
    private record Preview(BufferedImage raw,List<PreviewItem> items,String description,String qualitySummary){}
    private record PreviewItem(String name,BufferedImage image){}
    private static final class Row {
        final int index;final String name;final CheckBox selected=new CheckBox();final ColorPicker color=new ColorPicker();
        final TextField minimum=new TextField(),maximum=new TextField();final Slider minSlider=new Slider(),maxSlider=new Slider();
        final HBox minBox=new HBox(4,minSlider,minimum),maxBox=new HBox(4,maxSlider,maximum);
        boolean populating;
        Row(IfDisplayProfile.Channel c){index=c.index();name=c.name();minimum.setPrefColumnCount(5);maximum.setPrefColumnCount(5);
            minSlider.setPrefWidth(140);maxSlider.setPrefWidth(140);populate(c);
            minSlider.valueProperty().addListener((o,a,b)->{if(!populating)minimum.setText(String.format(Locale.ROOT,"%.4g",b.doubleValue()));});
            maxSlider.valueProperty().addListener((o,a,b)->{if(!populating)maximum.setText(String.format(Locale.ROOT,"%.4g",b.doubleValue()));});}
        void populate(IfDisplayProfile.Channel c){populating=true;try{selected.setSelected(c.selected());color.setValue(Color.rgb((c.color()>>16)&255,(c.color()>>8)&255,c.color()&255));
            double lo=Math.min(0,c.minimum()),hi=Math.max(255,c.maximum());minSlider.setMin(lo);minSlider.setMax(hi);maxSlider.setMin(lo);maxSlider.setMax(hi);
            minSlider.setValue(c.minimum());maxSlider.setValue(c.maximum());minimum.setText(Double.toString(c.minimum()));maximum.setText(Double.toString(c.maximum()));}finally{populating=false;}}
        void listen(Runnable action){selected.selectedProperty().addListener((o,a,b)->action.run());color.valueProperty().addListener((o,a,b)->action.run());
            minimum.textProperty().addListener((o,a,b)->{syncSlider(minSlider,b);action.run();});maximum.textProperty().addListener((o,a,b)->{syncSlider(maxSlider,b);action.run();});}
        void syncSlider(Slider slider,String text){if(populating)return;try{double value=Double.parseDouble(text.trim());if(!Double.isFinite(value))return;
            populating=true;slider.setMin(Math.min(slider.getMin(),value));slider.setMax(Math.max(slider.getMax(),value));slider.setValue(value);
        }catch(NumberFormatException ignored){}finally{populating=false;}}
        IfDisplayProfile.Channel state(){var c=color.getValue();int rgb=((int)Math.round(c.getRed()*255)<<16)|((int)Math.round(c.getGreen()*255)<<8)|(int)Math.round(c.getBlue()*255);
            return new IfDisplayProfile.Channel(index,name,rgb,Double.parseDouble(minimum.getText().trim()),Double.parseDouble(maximum.getText().trim()),selected.isSelected());}
    }
}
