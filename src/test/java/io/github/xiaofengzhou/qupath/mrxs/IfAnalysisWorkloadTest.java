package io.github.xiaofengzhou.qupath.mrxs;

import org.junit.jupiter.api.Test;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RoiTools;
import qupath.lib.regions.ImagePlane;
import static org.junit.jupiter.api.Assertions.*;

class IfAnalysisWorkloadTest {
    private static qupath.lib.roi.interfaces.ROI rect(double x,double y,double w,double h) {
        return ROIs.createRectangleROI(x,y,w,h,ImagePlane.getDefaultPlane());
    }
    @Test void countMatchesPixelCentersWithClippingAndFractionalBounds() {
        for(double ds:new double[]{1,2,3.7,64})for(double offset:new double[]{-3,0,0.5,2.7}) {
            var roi=rect(offset,offset,19.7,11.5);long expected=0;
            for(int y=0;y<20;y++)for(int x=0;x<32;x++) {
                double sx=(x+0.5)*ds,sy=(y+0.5)*ds;
                if(sx<32 && sy<20 && roi.contains(sx,sy))expected++;
            }
            assertEquals(expected,IfAnalysisWorkload.estimate(32,20,roi,ds,3).measurementBoundingPixels());
        }
    }
    @Test void wholeSlideEstimateAndSegmentationWarningAreIndependentOfMeasurementLevel() {
        var estimate=IfAnalysisWorkload.estimate(85280,190235,rect(0,0,85280,190235),64,4);
        assertEquals(3958704,estimate.measurementBoundingPixels());
        assertEquals(85280L*190235,estimate.nativeBoundingPixels());
        assertFalse(estimate.requiresConfirmation(false));assertTrue(estimate.requiresConfirmation(true));
        assertTrue(estimate.description(true).contains("does not reduce AI inference work"));
    }
    @Test void holesAreExplicitlyNotSubtractedAndOutsideRegionHasZeroCoverage() {
        var roi=RoiTools.difference(rect(0,0,32,20),rect(4,4,8,8));
        assertEquals(640,IfAnalysisWorkload.estimate(32,20,roi,1,1).measurementBoundingPixels());
        assertEquals(0,IfAnalysisWorkload.estimate(32,20,rect(40,40,5,5),1,1).measurementBoundingPixels());
        assertThrows(IllegalArgumentException.class,()->IfAnalysisWorkload.estimate(32,20,null,1,1));
    }
}
