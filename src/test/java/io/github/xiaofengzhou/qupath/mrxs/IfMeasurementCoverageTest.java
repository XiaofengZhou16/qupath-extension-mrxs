package io.github.xiaofengzhou.qupath.mrxs;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IfMeasurementCoverageTest {
    @Test void emptyIsUnavailableNotZeroAccuracy() {
        var value=IfMeasurementCoverage.of(0,0,0);
        assertNull(value.measuredFraction());
        assertEquals("no_cell_candidates",value.status());
        assertTrue(value.displayText().contains("N/A"));
    }
    @Test void allRejectedIsZeroMeasuredButNotNegative() {
        var value=IfMeasurementCoverage.of(5,0,5);
        assertEquals(0.0,value.measuredFraction());
        assertEquals("review_exclusions",value.status());
        assertTrue(value.guidance().contains("not negative cells"));
    }
    @Test void observedCandidateCountsReconcileWithoutImplyingAccuracy() {
        var value=IfMeasurementCoverage.of(4250,2582,1228+440);
        assertEquals(2582.0/4250,value.measuredFraction());
        assertTrue(value.displayText().contains("60.8%"));
        assertTrue(value.displayText().contains("Not AI accuracy"));
        assertTrue(value.displayText().contains("not all DAPI nuclei"));
        assertTrue(value.displayText().contains("including for NPN"));
        assertEquals("all_candidates_measurable",IfMeasurementCoverage.of(10,10,0).status());
    }
    @Test void rejectsInconsistentDenominators() {
        assertThrows(IllegalArgumentException.class,()->IfMeasurementCoverage.of(10,9,2));
        assertThrows(IllegalArgumentException.class,()->IfMeasurementCoverage.of(-1,0,-1));
        assertThrows(IllegalArgumentException.class,()->IfMeasurementCoverage.of(Integer.MAX_VALUE,Integer.MAX_VALUE,1));
    }
}
