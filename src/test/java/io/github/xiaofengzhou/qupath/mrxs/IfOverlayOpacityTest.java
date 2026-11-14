package io.github.xiaofengzhou.qupath.mrxs;

import java.awt.AlphaComposite;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qupath.lib.images.ImageData;
import qupath.lib.regions.ImageRegion;
import static org.junit.jupiter.api.Assertions.*;

class IfOverlayOpacityTest {
    @Test void opacityChangesOnlySyntheticDrawingAndPreservesCallerGraphics() throws Exception {
        try(var server=new IfAnalysisTest.TruthServer(4)) {
            var data=new ImageData<BufferedImage>(server);
            var cells=List.of(new IfAnalysis.Cell("a",IfNuclearMeasurementsTest.rect(4,1,4,4),null));
            var masks=Map.of("a",1);
            var overlay=new IfCellOverlay(data,cells,masks);
            for(double bad:new double[]{-1,1.01,Double.NaN,Double.POSITIVE_INFINITY})
                assertThrows(IllegalArgumentException.class,()->overlay.setOpacity(bad));
            // Synthetic buffer only, never a screen or viewer capture.
            int[] peaks=new int[3];int mode=0;
            for(double opacity:new double[]{0,0.5,1}) {
                overlay.setOpacity(opacity);
                var buffer=new BufferedImage(32,8,BufferedImage.TYPE_INT_ARGB);
                var g=buffer.createGraphics();g.setClip(0,0,32,8);
                var composite=AlphaComposite.getInstance(AlphaComposite.SRC_OVER,0.75f);g.setComposite(composite);
                try {
                    overlay.paintOverlay(g,ImageRegion.createInstance(0,0,32,8,0,0),1,data,true);
                    assertSame(composite,g.getComposite());
                } finally {g.dispose();}
                for(int y=0;y<8;y++)for(int x=0;x<32;x++)peaks[mode]=Math.max(peaks[mode],buffer.getRGB(x,y)>>>24);
                mode++;
            }
            assertEquals(0,peaks[0]);assertEquals(128,peaks[1]);assertEquals(255,peaks[2]);
            assertEquals(Map.of("a",1),masks);
            assertTrue(data.getHierarchy().getAnnotationObjects().isEmpty());
            assertTrue(data.getHierarchy().getDetectionObjects().isEmpty());
        }
    }
}
