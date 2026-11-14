package io.github.xiaofengzhou.qupath.mrxs;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import qupath.lib.gui.QuPathGUI;

/** Navigation only: shares existing panels and never changes analysis/display settings. */
final class IfWorkflowPanel {
    private final QuPathGUI qupath;
    private final Runnable display, analysis, history;
    private Stage stage;

    IfWorkflowPanel(QuPathGUI qupath, Runnable display, Runnable analysis, Runnable history) {
        this.qupath=qupath;this.display=display;this.analysis=analysis;this.history=history;
    }

    void show() {
        if(stage!=null && stage.isShowing()){stage.toFront();return;}
        var window=new Stage();stage=window;window.initOwner(qupath.getStage());
        window.setTitle("IF 工作台 / IF workflow");
        var image=new Label();image.setId("if-workflow-image");image.setWrapText(true);
        var selection=new Label();selection.setId("if-workflow-selection");selection.setWrapText(true);
        var colors=button("1  调色与发表图 / Display & figures","if-workflow-display",display);
        var analyze=button("2  IF 分析与批处理 / Analysis & batch","if-workflow-analysis",analysis);
        var results=button("3  历史结果与报告 / History & reports","if-workflow-history",history);
        var help=new Label("先选组织区域，再设置分析参数。批处理在分析窗口中打开，读取项目里已保存的命名 ROI。\n分析后可直接导出图片与报告，无需人工修正。显示调色与定量阈值分别设置。");
        help.setWrapText(true);
        var root=new VBox(12,image,selection,colors,analyze,results,help);root.setPadding(new Insets(18));
        Runnable refresh=()->{
            var viewer=qupath.getViewer();var data=viewer==null?null:viewer.getImageData();
            boolean supported=data!=null && data.getServer() instanceof MrxsImageServer;
            image.setText(data==null?"尚未打开切片 / No image open":"当前切片："+data.getServer().getMetadata().getName());
            var object=viewer==null?null:viewer.getSelectedObject();var roi=object==null?null:object.getROI();
            selection.setText(!supported?"请先打开多通道 MRXS；历史结果可直接浏览。":
                roi==null||!roi.isArea()||roi.isEmpty()?"未选中面积 ROI：可先调色，或在分析窗口配置批处理。":
                "当前 ROI："+(object.getName()==null?"未命名":object.getName())+String.format(java.util.Locale.ROOT," · %.0f × %.0f 源像素（外接框）",roi.getBoundsWidth(),roi.getBoundsHeight()));
            colors.setDisable(!supported);analyze.setDisable(!supported);
        };
        var timer=new Timeline(new KeyFrame(Duration.millis(500),e->refresh.run()));timer.setCycleCount(Timeline.INDEFINITE);
        window.setOnShown(e->{refresh.run();timer.play();});
        window.setOnHidden(e->{timer.stop();if(stage==window)stage=null;});
        window.setScene(new Scene(root,480,340));window.setMinWidth(420);window.setMinHeight(320);window.show();
    }

    private static Button button(String title,String id,Runnable action) {
        var button=new Button(title);button.setId(id);button.setMaxWidth(Double.MAX_VALUE);button.setWrapText(true);
        button.setOnAction(e->action.run());return button;
    }
}
