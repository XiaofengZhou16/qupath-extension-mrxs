package io.github.xiaofengzhou.qupath.mrxs;

import java.awt.Graphics2D;
import org.locationtech.jts.geom.Envelope;
import qupath.lib.regions.ImageRegion;

/** QuPath 0.7 may pass full server bounds to overlays; the graphics clip is the viewport. */
final class IfOverlayViewport {
    private IfOverlayViewport() {}
    static Envelope bounds(Graphics2D graphics,ImageRegion region) {
        var bounds=new Envelope(region.getX(),region.getX()+(double)region.getWidth(),
                region.getY(),region.getY()+(double)region.getHeight());
        var clip=graphics.getClip();
        if(clip!=null) {
            var b=clip.getBounds2D();
            bounds=bounds.intersection(new Envelope(b.getMinX(),b.getMaxX(),b.getMinY(),b.getMaxY()));
        }
        return bounds;
    }
}
