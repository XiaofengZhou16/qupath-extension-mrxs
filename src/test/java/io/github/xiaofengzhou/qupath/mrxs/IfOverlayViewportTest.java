package io.github.xiaofengzhou.qupath.mrxs;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;
import qupath.lib.regions.ImageRegion;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

class IfOverlayViewportTest {
    @Test void clipIsMeasuredInSourceCoordinatesAfterGraphicsTransform() {
        var g=new BufferedImage(100,100,BufferedImage.TYPE_INT_ARGB).createGraphics();
        try {
            g.setClip(0,0,100,100);g.translate(-20,-40);g.scale(2,2);
            assertEquals(new Envelope(10,60,20,70),IfOverlayViewport.bounds(g,ImageRegion.createInstance(0,0,1000,1000,0,0)));
            assertEquals(new Envelope(10,50,20,50),IfOverlayViewport.bounds(g,ImageRegion.createInstance(0,0,50,50,0,0)));
        }finally{g.dispose();}
    }
    @Test void absentClipFallsBackToRegionAndDisjointClipIsEmpty() {
        var g=new BufferedImage(100,100,BufferedImage.TYPE_INT_ARGB).createGraphics();
        try {
            var region=ImageRegion.createInstance(0,0,50,50,0,0);
            assertEquals(new Envelope(0,50,0,50),IfOverlayViewport.bounds(g,region));
            g.setClip(60,60,20,20);assertTrue(IfOverlayViewport.bounds(g,region).isNull());
        }finally{g.dispose();}
    }
}
