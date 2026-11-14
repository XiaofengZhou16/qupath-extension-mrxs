package io.github.xiaofengzhou.qupath.mrxs;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MrxsSubtileGeometryTest {
    @Test void fractionalCropPreservesPixelCentersAndSourceBounds() {
        for (double dx : new double[]{-2.7, 0, 0.5, 7.9})
            for (double width : new double[]{0.25, 1.1953125, 19.125, 306}) {
                var g = MrxsSubtileGeometry.create(dx, dx, 12.25, 9.125, width, width);
                for (int x=-4;x<320;x++) {
                    boolean expected=x+0.5>=dx && x+0.5<dx+width;
                    assertEquals(expected,x>=g.x() && x<g.x()+g.width());
                    if(expected)assertEquals((int)Math.floor(12.25+x+0.5-dx),
                            (int)Math.floor(g.sourceCenterX()+x-g.x()));
                }
            }
    }
    @Test void adjacentFractionalRegionsDoNotDuplicatePixelCenters() {
        var a=MrxsSubtileGeometry.create(0,0,0,0,1.1953125,1);
        var b=MrxsSubtileGeometry.create(1.1953125,0,1.1953125,0,1.1953125,1);
        assertEquals(a.x()+a.width(),b.x());
        assertEquals(2,a.width()+b.width());
    }
}
