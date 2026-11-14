package io.github.xiaofengzhou.qupath.mrxs;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.concurrent.Task;
import javafx.event.EventHandler;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.QuPathViewerListener;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Opt-in device-resolution layer in the existing viewer; stock interactions remain authoritative. */
final class MrxsHiDpiView implements AutoCloseable {
    record Key(IfHiDpiRenderer.View view,IfDisplayProfile profile) {}
    record Statistics(boolean enabled,boolean visible,boolean interacting,long requestedFrames,long acceptedFrames,
                      long discardedFrames,Integer width,Integer height,Integer nativeLevel,String status,IfHiDpiRenderer.View view,
                      long cachedBytes,Integer lastFrameSourceReads,Integer lastFrameCacheHits,
                      long presentationCount,long presentationBufferAllocations,long presentationPixelBufferBytes,Integer lastFrameRawCacheHits) {}
    @FunctionalInterface interface SourceRenderer {
        IfHiDpiRenderer.Frame render(qupath.lib.images.servers.ImageServer<BufferedImage> server,IfDisplayProfile profile,
                IfHiDpiRenderer.View view,java.util.function.BooleanSupplier cancelled)throws Exception;
    }
    private final QuPathViewer viewer;
    private final ImageData<BufferedImage> source;
    private final Consumer<String> statusSink;
    private final SourceRenderer renderer;
    private final IfHiDpiRenderer.TileCache tileCache=new IfHiDpiRenderer.TileCache(Math.min(64L*1024*1024,Runtime.getRuntime().maxMemory()/32));
    private final ImageView layer=new ImageView();
    private final PauseTransition renderDelay=new PauseTransition(Duration.millis(80));
    private final PauseTransition overlayDelay=new PauseTransition(Duration.millis(30));
    private final PauseTransition resumeDelay=new PauseTransition(Duration.millis(120));
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r->{
        var t=new Thread(r,"mrxs-hidpi-source");t.setDaemon(true);return t;},new ThreadPoolExecutor.DiscardOldestPolicy());
    private final InvalidationListener changed=o->refresh();
    private final InvalidationListener focusChanged=o->focusChanged();
    private final EventHandler<MouseEvent> pressed=e->suspend();
    private final EventHandler<MouseEvent> released=e->resumeLater();
    private final EventHandler<ScrollEvent> scrolling=e->{suspend();resumeLater();};
    private final QuPathViewerListener viewerListener=new QuPathViewerListener() {
        public void imageDataChanged(QuPathViewer v,ImageData<BufferedImage> before,ImageData<BufferedImage> after){if(after!=source)close();}
        public void visibleRegionChanged(QuPathViewer v,Shape shape){refresh();}
        public void selectedObjectChanged(QuPathViewer v,PathObject object){refresh();}
        public void viewerClosed(QuPathViewer v){close();}
    };
    private Key wanted,completed;
    private Key failed;
    private javafx.scene.Scene scene;
    private javafx.stage.Window window;
    private IfHiDpiRenderer.Frame frame;
    private WritableImage presentationImage;
    private int[] presentationPixels;
    private long presentationCount,presentationBufferAllocations;
    private Task<IfHiDpiRenderer.Frame> task;
    private long generation,requested,accepted,discarded;
    private boolean closed,interacting;
    private String status="Starting";

    MrxsHiDpiView(QuPathViewer viewer,Consumer<String> statusSink) {
        this(viewer,statusSink,null);
    }
    MrxsHiDpiView(QuPathViewer viewer,Consumer<String> statusSink,SourceRenderer renderer) {
        if(!Platform.isFxApplicationThread())throw new IllegalStateException("Enable HiDPI on the JavaFX thread");
        if(viewer==null||!(viewer.getServer() instanceof MrxsImageServer))throw new IllegalArgumentException("HiDPI mode currently requires the native MRXS reader");
        this.viewer=viewer;this.source=viewer.getImageData();this.statusSink=statusSink;
        this.renderer=renderer==null?(server,profile,view,cancelled)->IfHiDpiRenderer.render(server,profile,view,cancelled,tileCache):renderer;
        layer.setId("mrxs-hidpi-source-layer");layer.setManaged(false);layer.setMouseTransparent(true);layer.setSmooth(false);layer.setVisible(false);
        var clip=new Rectangle();clip.widthProperty().bind(viewer.getView().widthProperty());clip.heightProperty().bind(viewer.getView().heightProperty());layer.setClip(clip);
        var children=viewer.getView().getChildren();int canvas=-1;
        for(int i=0;i<children.size();i++)if(children.get(i) instanceof Canvas){canvas=i;break;}
        if(canvas<0){worker.shutdownNow();throw new IllegalArgumentException("Unsupported viewer layout: canvas not found");}
        children.add(canvas+1,layer); // below QuPathViewerPlus navigator/scalebar controls
        viewer.addViewerListener(viewerListener);viewer.repaintTimestamp().addListener(changed);
        viewer.getView().widthProperty().addListener(changed);viewer.getView().heightProperty().addListener(changed);
        viewer.getView().sceneProperty().addListener(changed);
        viewer.getView().addEventFilter(MouseEvent.MOUSE_PRESSED,pressed);viewer.getView().addEventFilter(MouseEvent.MOUSE_RELEASED,released);
        viewer.getView().addEventFilter(ScrollEvent.ANY,scrolling);
        renderDelay.setOnFinished(e->submit());overlayDelay.setOnFinished(e->present());
        resumeDelay.setOnFinished(e->{interacting=false;refresh();});refresh();
    }
    private Key capture() {
        var pane=viewer.getView();var window=pane.getScene()==null?null:pane.getScene().getWindow();
        if(window==null||!window.isShowing()||pane.getWidth()<=0||pane.getHeight()<=0)return null;
        // QuPath's own affine transform uses ceil(logical dimensions); preserve that geometry exactly.
        var geometry=new IfHiDpiRenderer.View((int)Math.ceil(pane.getWidth()),(int)Math.ceil(pane.getHeight()),
                window.getRenderScaleX(),window.getRenderScaleY(),viewer.getCenterPixelX(),viewer.getCenterPixelY(),
                viewer.getDownsampleFactor(),viewer.getRotation(),viewer.getZPosition(),viewer.getTPosition(),
                PathPrefs.viewerBackgroundColorProperty().get(),false);
        return new Key(geometry,IfDisplaySettings.capture(viewer));
    }
    private void refresh() {
        if(!Platform.isFxApplicationThread()){Platform.runLater(this::refresh);return;}
        if(closed)return;if(viewer.getImageData()!=source){close();return;}
        trackWindow();
        if(interacting){layer.setVisible(false);return;}
        try {
            var now=capture();
            if(now==null){layer.setVisible(false);return;}
            if(!now.equals(wanted)) {
                wanted=now;generation++;cancelTask();layer.setVisible(false);renderDelay.playFromStart();
            } else if(frame!=null&&now.equals(completed)) {
                layer.setVisible(false);overlayDelay.playFromStart();
            } else if(task==null&&!now.equals(failed)&&!renderDelay.getStatus().equals(javafx.animation.Animation.Status.RUNNING))renderDelay.playFromStart();
        }catch(Exception e){layer.setVisible(false);report("Stock view retained: "+e.getMessage());}
    }
    private void submit() {
        if(closed||interacting||wanted==null)return;
        final Key key=wanted;final long token=generation;requested++;
        var job=new Task<IfHiDpiRenderer.Frame>() {
            protected IfHiDpiRenderer.Frame call()throws Exception{return renderer.render(source.getServer(),key.profile(),key.view(),this::isCancelled);}
        };
        task=job;report("Rendering native device-resolution tiles…");
        job.setOnSucceeded(e->{
            if(task==job)task=null;
            try {
                if(closed||interacting||token!=generation||viewer.getImageData()!=source||!key.equals(capture())){discarded++;return;}
                frame=job.getValue();completed=key;failed=null;accepted++;present();
            }catch(Exception ex){layer.setVisible(false);report("Stock view retained: "+ex.getMessage());}
        });
        job.setOnFailed(e->{if(task==job)task=null;if(!closed&&token==generation){failed=key;layer.setVisible(false);report("Stock view retained: "+job.getException().getMessage());}});
        job.setOnCancelled(e->{if(task==job)task=null;discarded++;});worker.execute(job);
    }
    private void present() {
        if(closed||interacting||frame==null||viewer.getImageData()!=source)return;
        try {
            if(!completed.equals(capture())){layer.setVisible(false);refresh();return;}
            var image=IfHiDpiOverlays.compose(viewer,frame);
            int width=image.getWidth(),height=image.getHeight();
            if(presentationImage==null || presentationImage.getWidth()!=width || presentationImage.getHeight()!=height) {
                presentationImage=new WritableImage(width,height);
                presentationPixels=new int[Math.multiplyExact(width,height)];
                presentationBufferAllocations++;
            }
            image.getRGB(0,0,width,height,presentationPixels,0,width);
            presentationImage.getPixelWriter().setPixels(0,0,width,height,PixelFormat.getIntArgbInstance(),presentationPixels,0,width);
            presentationCount++;
            layer.setImage(presentationImage);layer.setFitWidth(frame.view().logicalWidth());layer.setFitHeight(frame.view().logicalHeight());layer.relocate(0,0);layer.setVisible(true);
            report("HiDPI active: "+image.getWidth()+" × "+image.getHeight()+", native level "+frame.nativeLevel()+". Drag/edit uses the stock viewer.");
        }catch(Exception e){layer.setVisible(false);report("Stock view retained: overlay composition failed: "+e.getMessage());}
    }
    private void suspend(){if(closed)return;interacting=true;generation++;layer.setVisible(false);renderDelay.stop();overlayDelay.stop();resumeDelay.stop();cancelTask();}
    private void resumeLater(){if(!closed)resumeDelay.playFromStart();}
    // Shared ImageServer FileChannels must not be interrupted by a display-only change.
    // Renderer checks cancellation between reads; generation checks reject stale frames.
    private void cancelTask(){if(task!=null){task.cancel(false);task=null;}worker.getQueue().clear();}
    private void report(String message){if(!message.equals(status)){status=message;statusSink.accept(message);}}
    private void focusChanged(){if(window!=null&&!window.isFocused()){suspend();interacting=false;}else refresh();}
    private void trackWindow() {
        var currentScene=viewer.getView().getScene();
        if(scene!=currentScene){if(scene!=null)scene.windowProperty().removeListener(changed);scene=currentScene;if(scene!=null)scene.windowProperty().addListener(changed);}
        var current=scene==null?null:scene.getWindow();
        if(current!=window){detachWindow();window=current;if(window!=null){
            window.renderScaleXProperty().addListener(changed);window.renderScaleYProperty().addListener(changed);
            window.showingProperty().addListener(changed);window.focusedProperty().addListener(focusChanged);
        }}
    }
    private void detachWindow(){if(window!=null){window.renderScaleXProperty().removeListener(changed);window.renderScaleYProperty().removeListener(changed);
        window.showingProperty().removeListener(changed);window.focusedProperty().removeListener(focusChanged);window=null;}}
    Statistics statistics(){return new Statistics(!closed,layer.isVisible(),interacting,requested,accepted,discarded,
            frame==null?null:frame.image().getWidth(),frame==null?null:frame.image().getHeight(),frame==null?null:frame.nativeLevel(),status,frame==null?null:frame.view(),
            tileCache.bytes(),frame==null?null:frame.tilesRead(),frame==null?null:frame.cachedTiles(),
            presentationCount,presentationBufferAllocations,presentationPixels==null?0:4L*presentationPixels.length,frame==null?null:frame.rawCachedTiles());}
    ImageView layer(){return layer;}
    @Override public void close() {
        if(!Platform.isFxApplicationThread()){Platform.runLater(this::close);return;}
        if(closed)return;closed=true;generation++;cancelTask();worker.shutdown();tileCache.close();renderDelay.stop();overlayDelay.stop();resumeDelay.stop();
        viewer.removeViewerListener(viewerListener);viewer.repaintTimestamp().removeListener(changed);
        viewer.getView().widthProperty().removeListener(changed);viewer.getView().heightProperty().removeListener(changed);
        viewer.getView().sceneProperty().removeListener(changed);if(scene!=null){scene.windowProperty().removeListener(changed);scene=null;}detachWindow();
        viewer.getView().removeEventFilter(MouseEvent.MOUSE_PRESSED,pressed);viewer.getView().removeEventFilter(MouseEvent.MOUSE_RELEASED,released);
        viewer.getView().removeEventFilter(ScrollEvent.ANY,scrolling);viewer.getView().getChildren().remove(layer);
        layer.setVisible(false);layer.setImage(null);presentationImage=null;presentationPixels=null;
        frame=null;completed=null;wanted=null;report("HiDPI disabled; stock viewer restored.");
    }
}
