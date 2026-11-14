package io.github.xiaofengzhou.qupath.mrxs;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.STRtree;
import qupath.lib.gui.viewer.overlays.PathOverlay;
import qupath.lib.images.ImageData;
import qupath.lib.regions.ImageRegion;
import qupath.lib.roi.RoiTools;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;

/** Viewport-indexed result outlines, independent of the user's stored classifications. */
final class IfCellOverlay implements PathOverlay {
    private final ImageData<BufferedImage> source;
    private final STRtree index = new STRtree();
    private volatile boolean visible = true;
    private volatile float opacity = 1f;
    private volatile int requiredMask;
    private volatile boolean exact;
    private volatile boolean rejectedOnly;
    private volatile String rejectedReason;

    IfCellOverlay(ImageData<BufferedImage> source, List<IfAnalysis.Cell> cells, IfAnalysis.Result result) {
        this(source,cells,result,()->false);
    }

    IfCellOverlay(ImageData<BufferedImage> source, List<IfAnalysis.Cell> cells, IfAnalysis.Result result,java.util.function.BooleanSupplier cancelled) {
        this(source,cells,result.cells().stream().collect(java.util.stream.Collectors.toMap(IfAnalysis.CellResult::id,IfAnalysis.CellResult::pn)),
                result.rejectedCells().stream().collect(java.util.stream.Collectors.toMap(IfAnalysis.RejectedCell::id,IfAnalysis.RejectedCell::reason)),cancelled);
    }

    IfCellOverlay(ImageData<BufferedImage> source,List<IfAnalysis.Cell> cells,java.util.Map<String,Integer> pnMasks) {
        this(source,cells,pnMasks,()->false);
    }

    IfCellOverlay(ImageData<BufferedImage> source,List<IfAnalysis.Cell> cells,java.util.Map<String,Integer> pnMasks,java.util.function.BooleanSupplier cancelled) {
        this(source,cells,pnMasks,java.util.Map.of(),cancelled);
    }

    private IfCellOverlay(ImageData<BufferedImage> source,List<IfAnalysis.Cell> cells,java.util.Map<String,Integer> pnMasks,java.util.Map<String,String> rejectedReasons,java.util.function.BooleanSupplier cancelled) {
        this.source = source;
        checkCancelled(cancelled);
        for (var cell : cells) {
            checkCancelled(cancelled);
            var measured = pnMasks.get(cell.id());
            if (measured == null && !rejectedReasons.containsKey(cell.id())) continue;
            var roi = cell.cell();
            if(roi==null || roi.isEmpty())continue;
            int mask = measured==null ? -1 : measured;
            var color = mask<0 ? Color.ORANGE : mask == 0 ? Color.GRAY : Color.getHSBColor((float) ((mask * 0.61803398875) % 1), 0.75f, 1);
            index.insert(roi.getGeometry().getEnvelopeInternal(),
                    new Outline(RoiTools.getShape(roi), cell.nucleus()==null?null:RoiTools.getShape(cell.nucleus()), mask, roi.getZ(), roi.getT(), color,rejectedReasons.get(cell.id())));
        }
        checkCancelled(cancelled);index.build();checkCancelled(cancelled);
    }

    private static void checkCancelled(java.util.function.BooleanSupplier cancelled){
        if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
    }

    void setVisible(boolean visible) { this.visible = visible; }
    float opacity() { return opacity; }
    void setOpacity(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1)
            throw new IllegalArgumentException("Opacity must be 0..1");
        opacity = (float)value;
    }
    void setFilter(int requiredMask, boolean exact) { this.requiredMask = requiredMask; this.exact = exact; }
    void setRejectedOnly(boolean rejectedOnly) { this.rejectedOnly=rejectedOnly; }
    void setRejectedReason(String reason) { this.rejectedReason=reason; }

    @Override public void paintOverlay(Graphics2D graphics, ImageRegion region, double downsample,
                                       ImageData<BufferedImage> imageData, boolean paintCompletely) {
        float alpha = opacity;
        if (!visible || alpha == 0 || imageData != source) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, alpha));
            float width=(float)Math.max(0.5,downsample);
            boolean reviewRejected=rejectedOnly;
            String reason=rejectedReason;
            g.setStroke(reviewRejected ? new BasicStroke(width,BasicStroke.CAP_BUTT,BasicStroke.JOIN_ROUND,10,
                    new float[]{4*width,3*width},0) : new BasicStroke(width));
            for (Object item : index.query(IfOverlayViewport.bounds(graphics,region))) {
                var outline = (Outline) item;
                if (outline.z != region.getZ() || outline.t != region.getT()) continue;
                if((outline.mask<0)!=reviewRejected)continue;
                if(reviewRejected && reason!=null && !reason.equals(outline.reason))continue;
                if (!reviewRejected && requiredMask != 0 && (exact ? outline.mask != requiredMask : (outline.mask & requiredMask) != requiredMask)) continue;
                g.setColor(outline.color); g.draw(outline.cell); if(outline.nucleus!=null)g.draw(outline.nucleus);
            }
        } finally { g.dispose(); }
    }

    private record Outline(Shape cell, Shape nucleus, int mask, int z, int t, Color color,String reason) {}
}
