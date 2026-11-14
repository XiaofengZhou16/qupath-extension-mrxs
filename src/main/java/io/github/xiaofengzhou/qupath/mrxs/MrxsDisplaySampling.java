package io.github.xiaofengzhou.qupath.mrxs;

import javafx.scene.canvas.Canvas;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.servers.ServerTools;
import java.util.List;
import java.util.Locale;

/** Read-only sampling diagnosis; no image capture, display change or private viewer access. */
final class MrxsDisplaySampling {
    record Plan(double sourcePixelsPerLogicalPixel,double renderScaleX,double renderScaleY,
                double sourcePixelsPerRenderPixel,int logicalLevel,int detailLevel,
                double logicalLevelDownsample,double detailLevelDownsample,
                long logicalPixelCount,long renderPixelCount) {}
    record Report(String imageName,String reader,String qupathVersion,double logicalWidth,double logicalHeight,
                  Double canvasWidth,Double canvasHeight,Double windowOutputScaleX,Double windowOutputScaleY,
                  boolean windowShowing,Plan plan,List<String> limits) {}

    static Plan plan(double[] nativeDownsamples,double downsample,double scaleX,double scaleY,int width,int height) {
        if(nativeDownsamples==null||nativeDownsamples.length==0||!(downsample>0)||!Double.isFinite(downsample)
                ||!(scaleX>0)||!Double.isFinite(scaleX)||!(scaleY>0)||!Double.isFinite(scaleY)||width<1||height<1)
            throw new IllegalArgumentException("Invalid display geometry");
        for(int i=0;i<nativeDownsamples.length;i++)if(!Double.isFinite(nativeDownsamples[i])||nativeDownsamples[i]<=0
                ||(i>0&&nativeDownsamples[i]<=nativeDownsamples[i-1]))throw new IllegalArgumentException("Invalid native pyramid");
        double requested=downsample/Math.max(scaleX,scaleY);
        // Choose a native level no coarser than the device-pixel target; never claim added optical detail.
        int logical=notCoarser(nativeDownsamples,downsample),detail=notCoarser(nativeDownsamples,requested);
        long renderWidth=(long)Math.ceil(width*scaleX),renderHeight=(long)Math.ceil(height*scaleY);
        return new Plan(downsample,scaleX,scaleY,requested,logical,detail,nativeDownsamples[logical],nativeDownsamples[detail],
                Math.multiplyExact((long)width,height),Math.multiplyExact(renderWidth,renderHeight));
    }
    private static int notCoarser(double[] levels,double target) {
        int level=0;while(level+1<levels.length&&levels[level+1]<=target)level++;return level;
    }
    static Report capture(QuPathViewer viewer) {
        if(viewer==null||viewer.getServer()==null)throw new IllegalArgumentException("Open an image first");
        var view=viewer.getView();var scene=view.getScene();var window=scene==null?null:scene.getWindow();
        var canvas=view.getChildren().stream().filter(Canvas.class::isInstance).map(Canvas.class::cast).findFirst().orElse(null);
        double sx=window==null?1:window.getRenderScaleX(),sy=window==null?1:window.getRenderScaleY();
        var server=viewer.getServer();
        var p=plan(server.getPreferredDownsamples(),viewer.getDownsampleFactor(),sx,sy,
                Math.max(1,(int)Math.ceil(view.getWidth())),Math.max(1,(int)Math.ceil(view.getHeight())));
        // The core's preferred-level rule has a tolerance; report its actual choice for logical rendering.
        int actualLogical=ServerTools.getPreferredResolutionLevel(server,viewer.getDownsampleFactor());
        p=new Plan(p.sourcePixelsPerLogicalPixel(),p.renderScaleX(),p.renderScaleY(),p.sourcePixelsPerRenderPixel(),actualLogical,p.detailLevel(),
                server.getDownsampleForResolution(actualLogical),p.detailLevelDownsample(),p.logicalPixelCount(),p.renderPixelCount());
        return new Report(server.getMetadata().getName(),server.getServerType(),qupath.lib.common.GeneralTools.getVersion(),view.getWidth(),view.getHeight(),
                canvas==null?null:canvas.getWidth(),canvas==null?null:canvas.getHeight(),window==null?null:window.getOutputScaleX(),window==null?null:window.getOutputScaleY(),
                window!=null&&window.isShowing(),p,List.of(
                "Read-only geometry report; no screenshot, source pixel read, preference or zoom change",
                "QuPath 0.7.0 paintCanvas creates its image cache using logical Canvas dimensions; device render scale is not included there",
                "Detail level is a proposed source-sampling target for a HiDPI renderer, not a claim that the existing main viewer uses it",
                "Window render scale describes rendering allocation, not microscopy resolution or guaranteed physical monitor pixel mapping",
                "At native level 0, a larger display cannot recover missing acquisition detail or bit depth",
                "If no visible window is attached, device-scale results are provisional; no automatic mode is applied"));
    }
    static String text(Report report) {
        var p=report.plan();
        return String.format(Locale.ROOT,"Logical viewport: %.0f × %.0f\nWindow render scale: %.3f × %.3f\n"
                +"Source px / logical px: %.4f\nTarget source px / render px: %.4f\n"
                +"Current logical sampling level: %d (downsample %.4f)\nHiDPI detail target level: %d (downsample %.4f)\n"
                +"Logical / render-grid pixels: %,d / %,d\n\n"
                +"This is diagnosis only, not a high-DPI rendering fix. DPI metadata does not add source detail.\n"
                +"No screenshot or image modification was performed.",report.logicalWidth(),report.logicalHeight(),p.renderScaleX(),p.renderScaleY(),
                p.sourcePixelsPerLogicalPixel(),p.sourcePixelsPerRenderPixel(),p.logicalLevel(),p.logicalLevelDownsample(),p.detailLevel(),p.detailLevelDownsample(),
                p.logicalPixelCount(),p.renderPixelCount());
    }
}
