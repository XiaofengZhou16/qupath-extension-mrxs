package io.github.xiaofengzhou.qupath.mrxs;

import javafx.application.Platform;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.PathObjectPainter;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.regions.ImageRegion;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Reuses QuPath's public overlay/ROI drawing APIs on a source-rendered device-size frame. */
final class IfHiDpiOverlays {
    private IfHiDpiOverlays() {}
    static BufferedImage compose(QuPathViewer viewer,IfHiDpiRenderer.Frame frame) {
        if(!Platform.isFxApplicationThread())throw new IllegalStateException("Overlay composition must run on the JavaFX thread");
        var base=frame.image();var view=frame.view();
        var result=new BufferedImage(base.getWidth(),base.getHeight(),BufferedImage.TYPE_INT_RGB);
        var graphics=result.createGraphics();
        try {
            graphics.drawImage(base,0,0,null);graphics.setClip(0,0,result.getWidth(),result.getHeight());
            graphics.transform(IfHiDpiRenderer.sourceToDevice(view));
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            var options=viewer.getOverlayOptions();float opacity=options.getOpacity();
            var data=viewer.getImageData();
            var bounds=viewer.getDisplayedRegionShape().getBounds();
            var region=ImageRegion.createInstance(bounds.x,bounds.y,bounds.width,bounds.height,view.z(),view.t());
            if(opacity>0||PathPrefs.alwaysPaintSelectedObjectsProperty().get()) {
                graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,opacity));
                for(var overlay:java.util.List.copyOf(viewer.getOverlayLayers()))overlay.paintOverlay(graphics,region,view.downsample(),data,true);
            }
            graphics.setComposite(AlphaComposite.SrcOver);
            var hierarchy=viewer.getHierarchy();
            if(hierarchy!=null)for(var object:java.util.List.copyOf(hierarchy.getSelectionModel().getSelectedObjects())) {
                var roi=object.getROI();if(roi==null||roi.getZ()!=view.z()||roi.getT()!=view.t())continue;
                if((object.isDetection()&&PathPrefs.useSelectedColorProperty().get())||!PathObjectTools.hierarchyContainsObject(hierarchy,object))
                    PathObjectPainter.paintObject(object,graphics,options,hierarchy.getSelectionModel(),view.downsample());
                Integer rgb=PathPrefs.colorSelectedObjectProperty().get();var color=new Color(rgb==null?0xffff00:rgb);
                if(PathPrefs.paintSelectedBoundsProperty().get()) {
                    graphics.setColor(color);graphics.setStroke(new BasicStroke((float)view.downsample()));
                    graphics.draw(new java.awt.geom.Rectangle2D.Double(roi.getBoundsX(),roi.getBoundsY(),roi.getBoundsWidth(),roi.getBoundsHeight()));
                }
                if(object==viewer.getSelectedObject()&&viewer.getROIEditor().hasROI()) {
                    graphics.setStroke(new BasicStroke((float)(PathPrefs.annotationStrokeThicknessProperty().get()*view.downsample())));
                    PathObjectPainter.paintHandles(viewer.getROIEditor(),graphics,view.downsample(),viewer.getMaxROIHandleSize(),color,
                            new Color(color.getRed(),color.getGreen(),color.getBlue(),96));
                }
            }
        }finally{graphics.dispose();}
        // The stock JavaFX overview, scalebar and location controls remain above this image layer.
        var border=viewer.getBorderColor();
        if(border!=null) {
            var g=result.createGraphics();try {
                g.scale(view.scaleX(),view.scaleY());g.setColor(new Color((float)border.getRed(),(float)border.getGreen(),(float)border.getBlue(),(float)border.getOpacity()));
                g.setStroke(new BasicStroke(6));g.draw(new java.awt.geom.Rectangle2D.Double(0,0,view.logicalWidth(),view.logicalHeight()));
            }finally{g.dispose();}
        }
        return result;
    }
}
