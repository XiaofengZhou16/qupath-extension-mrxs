package io.github.xiaofengzhou.qupath.mrxs;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.tools.PathTools;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Visible isolated viewer; tests source-rendered layer state, never reads screen pixels. */
public class MrxsHiDpiGuiSmoke {
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("slide.mrxs isolated-user-directory new-output-directory");
        Path output=Path.of(args[2]);Files.createDirectory(output);
        System.setProperty("java.util.prefs.PreferencesFactory",IfPanelSmoke.MemoryPreferencesFactory.class.getName());
        var done=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        Platform.startup(()->{
            try {
                Files.createDirectories(Path.of(args[1]));PathPrefs.userPathProperty().set(args[1]);
                var qupath=QuPathGUI.createHiddenInstance();var viewer=qupath.getViewer();
                // Match an already-open QuPath window before loading image/tree nodes.
                // Image-before-stage reproduces stock QuPath CSS lookup warnings even
                // without this extension; see IfStockViewerCssSmoke.
                qupath.getStage().setWidth(1000);qupath.getStage().setHeight(760);qupath.getStage().show();
                qupath.getStage().getScene().getRoot().applyCss();
                qupath.getStage().getScene().getRoot().layout();
                var server=new MrxsImageServer(Path.of(args[0]).toUri());var data=new ImageData<>(server,ImageData.ImageType.FLUORESCENCE);
                viewer.setImageData(data);
                var annotation=PathObjects.createAnnotationObject(ROIs.createRectangleROI(33100,82300,100,100,ImagePlane.getDefaultPlane()));
                data.getHierarchy().addObject(annotation);viewer.setSelectedObject(annotation);
                var profile=IfDisplaySettings.capture(viewer);boolean interpolation=PathPrefs.viewerInterpolateBilinearProperty().get();
                double globalGamma=PathPrefs.viewerGammaProperty().get();int childCount=viewer.getView().getChildren().size();
                var observations=new ArrayList<Object>();var mode=new MrxsHiDpiView[1];int[] phase={0};long[] accepted={0};
                double initialScaleX=qupath.getStage().getRenderScaleX(),initialScaleY=qupath.getStage().getRenderScaleY();
                double[][] scales={{1,1},{1.5,1.5},{initialScaleX,initialScaleY}};int[] scaleIndex={0};
                var reusablePresentation=new javafx.scene.image.Image[1];long[] presentationAllocations={0};
                var previousTool=new qupath.lib.gui.viewer.tools.PathTool[1];
                var staleStarted=new java.util.concurrent.atomic.AtomicBoolean();var releaseStale=new CountDownLatch(1);
                var staleInterrupted=new java.util.concurrent.atomic.AtomicBoolean();
                var renderCalls=new java.util.concurrent.atomic.AtomicInteger();
                var overlayCalls=new java.util.concurrent.atomic.AtomicInteger();
                qupath.lib.gui.viewer.overlays.PathOverlay sentinel=(g,region,ds,imageData,complete)->{
                    if(Math.abs(g.getTransform().getScaleX()-0.5)<1e-8)overlayCalls.incrementAndGet();
                    g.setColor(java.awt.Color.RED);g.fillRect(33200,82400,10,10);
                };
                viewer.getCustomOverlayLayers().add(sentinel);
                long started=System.nanoTime();var timer=new PauseTransition(Duration.seconds(1));
                timer.setOnFinished(event->{
                    boolean finished=false;
                    try {
                        if((System.nanoTime()-started)/1e9>55)throw new IllegalStateException("HiDPI GUI timeout phase="+phase[0]+" status="+(mode[0]==null?"none":mode[0].statistics()));
                        switch(phase[0]) {
                            case 0 -> {
                                viewer.setDownsampleFactor(4,-1,-1);viewer.setCenterPixelLocation(33280,82432);
                                mode[0]=new MrxsHiDpiView(viewer,System.out::println);phase[0]=1;
                            }
                            case 1 -> {
                                if(!mode[0].statistics().visible())break;
                                var state=mode[0].statistics();observations.add(state);
                                int expectedWidth=(int)Math.ceil(Math.ceil(viewer.getView().getWidth())*qupath.getStage().getRenderScaleX());
                                int expectedHeight=(int)Math.ceil(Math.ceil(viewer.getView().getHeight())*qupath.getStage().getRenderScaleY());
                                require(state.width()==expectedWidth&&state.height()==expectedHeight,"Wrong device-size frame");
                                require(state.nativeLevel()==MrxsDisplaySampling.plan(server.getPreferredDownsamples(),4,
                                        initialScaleX,initialScaleY,(int)Math.ceil(viewer.getView().getWidth()),(int)Math.ceil(viewer.getView().getHeight())).detailLevel(),
                                        "Wrong native level for initial render scale");
                                require(mode[0].layer().isMouseTransparent()&&!mode[0].layer().isManaged(),"Layer intercepts viewer input/layout");
                                require(viewer.getView().getChildren().indexOf(mode[0].layer())==1,"Unexpected layer order");
                                require(overlayCalls.get()>0,"Custom overlay not composed at device resolution");
                                var center=viewer.imagePointToComponentPoint(new java.awt.geom.Point2D.Double(33280,82432),null,false);
                                require(Math.abs(center.getX()-Math.ceil(viewer.getView().getWidth())/2)<1e-6,"Pointer transform changed");
                                accepted[0]=state.acceptedFrames();
                                qupath.getStage().setRenderScaleX(scales[0][0]);qupath.getStage().setRenderScaleY(scales[0][1]);
                                require(!mode[0].statistics().visible(),"Scale change did not immediately hide old frame");
                                phase[0]=10;
                            }
                            case 10 -> {
                                var state=mode[0].statistics();
                                if(!state.visible() || state.acceptedFrames()<=accepted[0])break;
                                double sx=scales[scaleIndex[0]][0],sy=scales[scaleIndex[0]][1];
                                int lw=(int)Math.ceil(viewer.getView().getWidth()),lh=(int)Math.ceil(viewer.getView().getHeight());
                                require(state.view().scaleX()==sx && state.view().scaleY()==sy,"Presented old render scale");
                                require(state.width()==(int)Math.ceil(lw*sx)&&state.height()==(int)Math.ceil(lh*sy),"Wrong resized device raster");
                                require(state.nativeLevel()==MrxsDisplaySampling.plan(server.getPreferredDownsamples(),4,sx,sy,lw,lh).detailLevel(),"Wrong scale-dependent pyramid level");
                                require(state.view().centerX()==33280&&state.view().centerY()==82432&&state.view().downsample()==4,"Render scale moved source coordinates");
                                observations.add(state);accepted[0]=state.acceptedFrames();
                                if(++scaleIndex[0]<scales.length) {
                                    qupath.getStage().setRenderScaleX(scales[scaleIndex[0]][0]);
                                    qupath.getStage().setRenderScaleY(scales[scaleIndex[0]][1]);
                                    require(!mode[0].statistics().visible(),"Old scale frame stayed visible");
                                    break;
                                }
                                reusablePresentation[0]=mode[0].layer().getImage();
                                presentationAllocations[0]=state.presentationBufferAllocations();
                                require(state.presentationPixelBufferBytes()==4L*state.width()*state.height(),"Wrong presentation pixel buffer size");
                                Event.fireEvent(viewer.getView(),mouse(true));
                                require(mode[0].statistics().interacting()&&!mode[0].statistics().visible(),"Mouse press did not restore stock view immediately");
                                Event.fireEvent(viewer.getView(),mouse(false));phase[0]=2;
                            }
                            case 2 -> {
                                if(!mode[0].statistics().visible())break;
                                previousTool[0]=qupath.getToolManager().getSelectedTool();
                                viewer.setSelectedObject(null);viewer.getView().requestFocus();
                                qupath.getStage().requestFocus();
                                qupath.getToolManager().setSelectedTool(PathTools.RECTANGLE);
                                phase[0]=20;
                            }
                            case 20 -> {
                                require(viewer.getActiveTool()==PathTools.RECTANGLE,"Rectangle tool not active");
                                System.out.println("DRAW_FOCUS node="+viewer.getView().isFocused()+" window="+qupath.getStage().isFocused());
                                // Automation host may deny OS window activation. Exercise event routing,
                                // not OS focus acquisition; bypass only QuPath's first-click-focus guard.
                                boolean focusTraversable=viewer.getView().isFocusTraversable();
                                viewer.getView().setFocusTraversable(false);
                                try {
                                Event.fireEvent(viewer.getView(),dragEvent(MouseEvent.MOUSE_PRESSED,100,150,true));
                                Event.fireEvent(viewer.getView(),dragEvent(MouseEvent.MOUSE_DRAGGED,180,230,true));
                                Event.fireEvent(viewer.getView(),dragEvent(MouseEvent.MOUSE_RELEASED,180,230,false));
                                } finally {viewer.getView().setFocusTraversable(focusTraversable);}
                                var drawn=viewer.getSelectedObject();
                                require(drawn!=null && drawn!=annotation && drawn.isAnnotation() && drawn.hasROI()
                                        && drawn.getROI().getArea()>0,"Rectangle tool did not draw through HiDPI layer");
                                require(data.getHierarchy().getAnnotationObjects().size()==2,"Drawing did not commit exactly one annotation");
                                require(Math.abs(drawn.getROI().getArea()-102400)<1,"Drawn rectangle has wrong source-pixel area");
                                data.getHierarchy().removeObject(drawn,true);viewer.setSelectedObject(annotation);
                                qupath.getToolManager().setSelectedTool(previousTool[0]);
                                observations.add(mode[0].statistics());accepted[0]=mode[0].statistics().acceptedFrames();
                                // Several programmatic moves before settling: only the latest view may be presented.
                                viewer.setCenterPixelLocation(34000,83000);viewer.setDownsampleFactor(2.5,-1,-1);
                                viewer.setCenterPixelLocation(33500,82700);viewer.setRotation(0.2);
                                viewer.setDownsampleFactor(4,-1,-1);viewer.setCenterPixelLocation(33280,82432);phase[0]=3;
                            }
                            case 3 -> {
                                if(!mode[0].statistics().visible()||mode[0].statistics().acceptedFrames()<=accepted[0])break;
                                observations.add(mode[0].statistics());
                                require(mode[0].layer().getImage()==reusablePresentation[0],"Same-size redraw replaced presentation image");
                                require(mode[0].statistics().presentationBufferAllocations()==presentationAllocations[0],"Same-size redraw reallocated presentation buffers");
                                require(Math.abs(viewer.getCenterPixelX()-33280)<1e-6&&Math.abs(viewer.getCenterPixelY()-82432)<1e-6,"HiDPI moved source center");
                                require(profile.equals(IfDisplaySettings.capture(viewer)),"HiDPI altered ranges/colors/gamma");
                                require(PathPrefs.viewerInterpolateBilinearProperty().get()==interpolation&&PathPrefs.viewerGammaProperty().get()==globalGamma,"HiDPI changed global preferences");
                                require(data.getHierarchy().getAnnotationObjects().size()==1&&annotation.getROI().getArea()==10000,"HiDPI changed stored annotation");
                                mode[0].close();require(viewer.getView().getChildren().size()==childCount,"Explicit disable left a layer");
                                require(!mode[0].statistics().enabled()&&mode[0].layer().getImage()==null,"Disable retained frame data");
                                require(mode[0].statistics().presentationPixelBufferBytes()==0,"Disable retained presentation pixel buffer");
                                mode[0]=new MrxsHiDpiView(viewer,System.out::println,(imageServer,settings,view,cancelled)->{
                                    if(renderCalls.incrementAndGet()==1) {
                                        staleStarted.set(true);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                                        // Deliberately emulate an in-flight decoder that ignores interruption.
                                        while(releaseStale.getCount()>0&&System.nanoTime()<deadline) {
                                            try{releaseStale.await(50,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){staleInterrupted.set(true);}
                                        }
                                        return IfHiDpiRenderer.render(imageServer,settings,view,()->false);
                                    }
                                    return IfHiDpiRenderer.render(imageServer,settings,view,cancelled);
                                });phase[0]=4;
                            }
                            case 4 -> {
                                if(!staleStarted.get())break;
                                viewer.setCenterPixelLocation(33792,82944);viewer.setDownsampleFactor(2,-1,-1);phase[0]=5;
                            }
                            case 5 -> {
                                if(mode[0].statistics().requestedFrames()<2)break;
                                require(!mode[0].statistics().visible(),"Stale in-flight frame became visible");
                                releaseStale.countDown();phase[0]=6;
                            }
                            case 6 -> {
                                if(!mode[0].statistics().visible())break;
                                var state=mode[0].statistics();
                                require(state.acceptedFrames()==1&&state.discardedFrames()>=1,"Old uninterruptible result was not discarded");
                                require(!staleInterrupted.get(),"Display change interrupted an active source read");
                                require(state.nativeLevel()==0&&state.view().centerX()==33792&&state.view().centerY()==82944&&state.view().downsample()==2,"Presented stale view geometry");
                                observations.add(state);
                                var other=new WrappedBufferedImageServer("non-MRXS",new BufferedImage(32,32,BufferedImage.TYPE_BYTE_GRAY));
                                viewer.setImageData(new ImageData<>(other,ImageData.ImageType.FLUORESCENCE));
                                require(!mode[0].statistics().enabled()&&!mode[0].statistics().visible(),"Mode remained active on non-MRXS");
                                require(mode[0].statistics().presentationPixelBufferBytes()==0&&mode[0].layer().getImage()==null,"Source switch retained presentation buffers");
                                require(viewer.getView().getChildren().size()==childCount,"Auto-close left a layer");
                                viewer.getCustomOverlayLayers().remove(sentinel);viewer.resetImageData();server.close();other.close();
                                var report=new LinkedHashMap<String,Object>();report.put("frames",observations);
                                report.put("overlayCallsAtDeviceScale",overlayCalls.get());report.put("mouseInputTransparent",true);
                                report.put("mousePressFallbackAndReleaseRecovery",true);report.put("rapidViewChangesAndRotation",true);
                                report.put("rectangleToolEventDrawingAndSourceArea",true);
                                report.put("rectangleTestFirstClickFocusGuardBypassed",true);
                                report.put("globalPreferencesUnchanged",true);report.put("sourceAnnotationsUnchanged",true);
                                report.put("uninterruptibleOldFrameDiscarded",true);
                                report.put("activeSourceReadNotInterrupted",true);
                                report.put("explicitAndNonMrxsCleanup",true);report.put("screenshots",false);
                                report.put("stageStyledBeforeImageLoad",true);
                                report.put("programmaticRenderScaleTransitions",scales);
                                report.put("physicalMonitorTransitionTested",false);
                                report.put("sameSizePresentationBuffersReused",true);
                                report.put("presentationBuffersReleasedOnCloseAndSourceChange",true);
                                Files.writeString(output.resolve("verification.json"),GsonTools.getInstance(true).toJson(report));
                                System.out.println("MRXS_HIDPI_GUI_OK "+GsonTools.getInstance().toJson(report));qupath.getStage().hide();finished=true;
                            }
                        }
                        if(!finished){timer.setDuration(Duration.millis(100));timer.playFromStart();}
                    }catch(Throwable t){failure.set(t);releaseStale.countDown();if(mode[0]!=null)mode[0].close();finished=true;}
                    finally{if(finished){done.countDown();Platform.exit();}}
                });timer.play();
            }catch(Throwable t){failure.set(t);done.countDown();Platform.exit();}
        });
        if(!done.await(70,TimeUnit.SECONDS)){System.err.println("HiDPI GUI watchdog timeout");System.exit(2);}
        if(failure.get()!=null){failure.get().printStackTrace();System.exit(1);}System.exit(0);
    }
    private static MouseEvent mouse(boolean pressed) {
        return new MouseEvent(pressed?MouseEvent.MOUSE_PRESSED:MouseEvent.MOUSE_RELEASED,20,20,20,20,MouseButton.PRIMARY,1,
                false,false,false,false,pressed,false,false,true,false,true,null);
    }
    private static MouseEvent dragEvent(javafx.event.EventType<MouseEvent> type,double x,double y,boolean down) {
        return new MouseEvent(type,x,y,x,y,MouseButton.PRIMARY,1,false,false,false,false,
                down,false,false,true,false,true,null);
    }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
