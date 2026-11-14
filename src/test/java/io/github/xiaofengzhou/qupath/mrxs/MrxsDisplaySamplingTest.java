package io.github.xiaofengzhou.qupath.mrxs;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MrxsDisplaySamplingTest {
    private static final double[] LEVELS={1,2,4,8,16};
    @Test void retinaRequiresFinerSourceSamplingAndFourTimesRenderPixelsAtSameFieldOfView() {
        var normal=MrxsDisplaySampling.plan(LEVELS,4,1,1,800,600);
        var retina=MrxsDisplaySampling.plan(LEVELS,4,2,2,800,600);
        assertEquals(2,normal.logicalLevel());assertEquals(2,normal.detailLevel());
        assertEquals(2,retina.logicalLevel());assertEquals(1,retina.detailLevel());
        assertEquals(2,retina.sourcePixelsPerRenderPixel());
        assertEquals(480000,retina.logicalPixelCount());assertEquals(1920000,retina.renderPixelCount());
    }
    @Test void choosesNoCoarserNativeLevelAndNeverInventsDetailBelowLevelZero() {
        var fractional=MrxsDisplaySampling.plan(LEVELS,3,1.5,2,101,99);
        assertEquals(1,fractional.logicalLevel());assertEquals(0,fractional.detailLevel());
        assertEquals(152L*198,fractional.renderPixelCount());
        var magnified=MrxsDisplaySampling.plan(LEVELS,0.5,2,2,100,100);
        assertEquals(0,magnified.detailLevel());assertEquals(1,magnified.detailLevelDownsample());
    }
    @Test void rejectsUnknownOrInvalidSamplingGeometry() {
        assertThrows(IllegalArgumentException.class,()->MrxsDisplaySampling.plan(LEVELS,4,Double.NaN,2,100,100));
        assertThrows(IllegalArgumentException.class,()->MrxsDisplaySampling.plan(LEVELS,0,2,2,100,100));
        assertThrows(IllegalArgumentException.class,()->MrxsDisplaySampling.plan(new double[]{1,4,2},4,2,2,100,100));
        assertThrows(IllegalArgumentException.class,()->MrxsDisplaySampling.plan(LEVELS,4,2,2,0,100));
    }
}
