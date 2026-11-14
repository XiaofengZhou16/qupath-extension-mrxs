package io.github.xiaofengzhou.qupath.mrxs;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.roi.interfaces.ROI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Project selection and cooperative cancellation; workers never borrow viewer image servers. */
final class IfBatchPanel {
    record Choice(String label,String id,Path source,ROI roi) { @Override public String toString(){return label;} }
    static void show(QuPathGUI qupath,Stage owner,IfAnalysisProfile profile,IfInstanSeg.Config ai,
                     List<String> channels,IfDisplayProfile display,boolean masks,ClassLoader loader) {
        show(qupath,owner,profile,ai,channels,display,masks,loader,(window,resuming)->{
            var chooser=new DirectoryChooser();chooser.setTitle(resuming?"选择含 batch-plan.json 的批次目录":"选择新批次输出的父目录");
            var folder=chooser.showDialog(window);return folder==null?null:folder.toPath();
        });
    }
    // Test seam replaces only the native directory chooser; handlers and worker lifecycle remain real.
    static void show(QuPathGUI qupath,Stage owner,IfAnalysisProfile profile,IfInstanSeg.Config ai,
                     List<String> channels,IfDisplayProfile display,boolean masks,ClassLoader loader,
                     java.util.function.BiFunction<Stage,Boolean,Path> chooseDirectory) {
        var project=qupath.getProject();
        if(project==null)throw new IllegalArgumentException("Open a QuPath project first / 请先打开项目");
        var entries=List.copyOf(project.getImageList());
        var stage=new Stage();stage.initOwner(owner);stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("IF project batch / 项目 ROI 批处理");
        var help=new Label("先保存项目中的图像数据，再读取 ROI。这里只列出已保存且有名称的区域标注；不含 IF 复核细胞。\n"
                +"按住 Command/Shift 多选。新批次使用当前分析面板设置；恢复使用清单设置，但只允许当前已确认可信的模型目录。\n"
                +"所选切片必须具有相同通道顺序和名称。无 ROI 不会转为整片分析。");help.setWrapText(true);
        var list=new ListView<Choice>();list.setId("if-batch-rois");list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        var scan=new Button("读取已保存 ROI");scan.setId("if-batch-scan");
        var start=new Button("运行所选 ROI…");start.setId("if-batch-start");
        var resume=new Button("恢复已保存批次…");resume.setId("if-batch-resume");
        var report=new Button("导出批次结果…");report.setId("if-batch-report");
        var reportWord=new CheckBox("同时导出 Word");reportWord.setId("if-batch-report-word");
        var cancel=new Button("取消");cancel.setId("if-batch-cancel");cancel.setDisable(true);
        var status=new Label("尚未读取 ROI");status.setId("if-batch-status");status.setWrapText(true);
        var detail=new TextArea();detail.setEditable(false);detail.setPrefRowCount(6);detail.setId("if-batch-detail");
        var progress=new ProgressBar(0);progress.setId("if-batch-progress");progress.setMaxWidth(Double.MAX_VALUE);
        var selected=new Label("已选 0 个 ROI");
        list.getSelectionModel().getSelectedItems().addListener((javafx.collections.ListChangeListener<Choice>)c->
                selected.setText("已选 "+list.getSelectionModel().getSelectedItems().size()+" 个 ROI"));
        var busy=new AtomicBoolean();var stopped=new AtomicBoolean();
        java.util.function.Consumer<Boolean> controls=b->{report.setDisable(b);reportWord.setDisable(b);scan.setDisable(b);start.setDisable(b);resume.setDisable(b);list.setDisable(b);cancel.setDisable(!b);};
        cancel.setOnAction(e->{stopped.set(true);cancel.setDisable(true);status.setText("正在停止：等待当前推理安全结束，保留已完成结果…");});
        stage.setOnCloseRequest(e->{if(busy.get()){e.consume();cancel.fire();}});
        java.util.function.Consumer<Runnable> launch=operation->{
            if(!busy.compareAndSet(false,true))return;stopped.set(false);controls.accept(true);progress.setProgress(-1);
            var thread=new Thread(()->{
                try{operation.run();}
                finally{Platform.runLater(()->{busy.set(false);controls.accept(false);progress.setProgress(0);});}
            },"mrxs-if-project-batch");thread.start();
        };
        scan.setOnAction(e->launch.accept(()->{
            var choices=new ArrayList<Choice>();var errors=new StringBuilder();
            for(var entry:entries) {
                if(stopped.get())break;
                try {
                    var uris=entry.getURIs();if(uris.size()!=1)throw new IllegalArgumentException("需要单一 MRXS 源");
                    var uri=uris.iterator().next();if(!"file".equalsIgnoreCase(uri.getScheme()))throw new IllegalArgumentException("仅支持本地切片");
                    Path source=Path.of(uri);if(!source.toString().toLowerCase(Locale.ROOT).endsWith(".mrxs"))continue;
                    var hierarchy=entry.readHierarchy();if(hierarchy==null)throw new IllegalArgumentException("无已保存图像数据");
                    int count=0;
                    for(var object:hierarchy.getAnnotationObjects()) {
                        var roi=object.getROI();var name=object.getName();
                        if(roi==null||!roi.isArea()||roi.isEmpty()||name==null||name.isBlank()
                                ||object.getMetadata().containsKey(IfCellReview.GROUP)||object.getMetadata().containsKey(IfCellReview.PAIR))continue;
                        String id="roi-"+IfBatchOutput.hashText(uri+"|"+entry.getID()+"|"+object.getID()).substring(0,32);
                        choices.add(new Choice(entry.getImageName()+" / "+name+" ["+(long)roi.getBoundsWidth()+"×"+(long)roi.getBoundsHeight()+" px]",id,source,roi));count++;
                    }
                    if(count==0)errors.append(entry.getImageName()).append(": 无已保存命名 ROI\n");
                }catch(Exception ex){errors.append(entry.getImageName()).append(": ").append(ex.getMessage()).append('\n');}
            }
            Platform.runLater(()->{list.getItems().setAll(choices);detail.setText(errors.toString());status.setText((stopped.get()?"读取已取消；":"")+"可选择 "+choices.size()+" 个 ROI");});
        }));
        java.util.function.BiConsumer<Path,List<Choice>> run=(root,selection)->launch.accept(()->{
            try {
                IfBatchAnalysis work;
                if(selection==null) {
                    var plan=IfBatchPlan.read(root.resolve("batch-plan.json"));
                    for(var request:plan.requests())if(!request.ai().modelDirectory().equals(ai.modelDirectory()))
                        throw new IllegalArgumentException("清单模型目录与当前已确认可信的模型不同，请先在分析面板选择并确认该模型");
                    work=IfBatchAnalysis.reopen(root,loader,stopped::get,p->Platform.runLater(()->progress.setProgress(p)));
                }else {
                    var requests=selection.stream().map(c->new IfBatchAnalysis.Request(c.id,c.source,c.roi,profile,channels,ai,masks,display)).toList();
                    work=new IfBatchAnalysis(requests,loader,stopped::get,p->Platform.runLater(()->progress.setProgress(p)));
                }
                try(work) {
                    work.statusListener(s->Platform.runLater(()->{if(!stopped.get())status.setText(s);}));
                    var result=work.run(root);var latest=new LinkedHashMap<String,String>();
                    result.attempts().forEach(a->latest.put(a.task(),a.status()));
                    long complete=latest.values().stream().filter("complete"::equals).count();
                    String summary=(stopped.get()?"已停止":"队列结束")+"；完成 "+complete+" / "+result.items().size()+"。";
                    var text=new StringBuilder(root.toString()+"\n");
                    for(var a:result.attempts())text.append(a.task()).append(" | ").append(a.status()).append(" | ").append(a.error()).append('\n');
                    Platform.runLater(()->{status.setText(summary);detail.setText(text.toString());});
                }
            }catch(Exception ex){Platform.runLater(()->{status.setText(stopped.get()?"已停止，保留已有任务记录":"批处理未完成："+ex.getMessage());detail.setText(root+"\n"+ex);});}
        });
        start.setOnAction(e->{
            var selection=List.copyOf(list.getSelectionModel().getSelectedItems());
            if(selection.isEmpty()){status.setText("请先选择命名 ROI");return;}
            var folder=chooseDirectory.apply(stage,false);
            if(folder!=null)run.accept(folder.resolve("if-batch-"+UUID.randomUUID()),selection);
        });
        resume.setOnAction(e->{var folder=chooseDirectory.apply(stage,true);if(folder!=null)run.accept(folder,null);});
        report.setOnAction(e->{
            var folder=chooseDirectory.apply(stage,true);if(folder==null)return;
            boolean word=reportWord.isSelected();var output=folder.resolve("batch-report-"+UUID.randomUUID());
            launch.accept(()->{try {
                var summary=IfBatchResults.export(folder,output,new IfOfficeReport.Formats(true,word),stopped::get);
                Platform.runLater(()->{status.setText("已导出校验通过的 "+summary.included()+" / "+summary.planned()+" 个 ROI；其余状态见清单。");detail.setText(output.toString());});
            }catch(Exception ex){Platform.runLater(()->{status.setText("报告未完成："+ex.getMessage());detail.setText(output+"\n"+ex);});}});
        });
        var root=new VBox(10,help,new HBox(8,scan,start,resume,cancel),new HBox(8,report,reportWord),selected,list,progress,status,detail);
        root.setPadding(new Insets(14));VBox.setVgrow(list,Priority.ALWAYS);stage.setScene(new Scene(root,920,700));stage.show();
    }
}
