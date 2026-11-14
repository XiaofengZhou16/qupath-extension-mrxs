package io.github.xiaofengzhou.qupath.mrxs;

import org.junit.jupiter.api.Test;
import java.util.List;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import static org.junit.jupiter.api.Assertions.*;

class IfRejectedNavigationTest {
    @Test void constructionCanCancelBeforeOrDuringPreparation() {
        assertThrows(java.util.concurrent.CancellationException.class,
                ()->new IfRejectedNavigation(List.of(),List.of(),()->true));
        var roi=ROIs.createRectangleROI(0,0,10,10,ImagePlane.getDefaultPlane());
        var cell=new IfAnalysis.Cell("a",roi,null);
        var rejected=new IfAnalysis.RejectedCell("a","eligibility","missing_nucleus_roi");
        for(int cutoff:List.of(2,4)) {
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            assertThrows(java.util.concurrent.CancellationException.class,()->new IfRejectedNavigation(
                    List.of(cell,cell),List.of(rejected,rejected),()->calls.incrementAndGet()>=cutoff));
        }
    }
    @Test void indexedGroupsRetainSourceOrderAndDoNotRecheckGeometry() {
        var roi=ROIs.createRectangleROI(0,0,10,10,ImagePlane.getDefaultPlane());
        var cells=new java.util.ArrayList<IfAnalysis.Cell>();
        var rejected=new java.util.ArrayList<IfAnalysis.RejectedCell>();
        for(int i=0;i<10000;i++) {
            cells.add(new IfAnalysis.Cell("id"+i,roi,null));
            rejected.add(new IfAnalysis.RejectedCell("id"+i,"eligibility",i%2==0?"even":"odd"));
        }
        var checks=new java.util.concurrent.atomic.AtomicInteger();
        var navigation=new IfRejectedNavigation(cells,rejected,()->{checks.incrementAndGet();return false;});
        int preparedChecks=checks.get();
        cells.clear();rejected.clear();
        for(int repeat=0;repeat<100;repeat++) {
            navigation.filter("odd");assertEquals(5000,navigation.size());
            assertEquals("id1",navigation.move(1).id());assertEquals("id9999",navigation.move(-1).id());
            navigation.filter("even");assertEquals("id0",navigation.move(1).id());
            navigation.filter("unknown");assertNull(navigation.move(1));
            navigation.filter(null);assertEquals(10000,navigation.size());
        }
        assertEquals(preparedChecks,checks.get());
    }
    @Test void filtersWrapsAndResetsWithoutChangingCandidates() {
        var roi=ROIs.createRectangleROI(10,20,4,6,ImagePlane.getDefaultPlane());
        var cells=List.of(new IfAnalysis.Cell("a",roi,null),new IfAnalysis.Cell("b",roi,roi),
                new IfAnalysis.Cell("missing-geometry",null,null),new IfAnalysis.Cell("eligible",roi,roi));
        var rejected=List.of(new IfAnalysis.RejectedCell("a","eligibility","missing_nucleus_roi"),
                new IfAnalysis.RejectedCell("b","measurement","no_valid_cytoplasm_pixels"),
                new IfAnalysis.RejectedCell("missing-geometry","eligibility","missing_cell_roi"));
        var navigation=new IfRejectedNavigation(cells,rejected);
        assertEquals(2,navigation.size());assertEquals(0,navigation.position());
        assertEquals("a",navigation.move(1).id());assertEquals("b",navigation.move(1).id());
        assertEquals("a",navigation.move(1).id());assertEquals("b",navigation.move(-1).id());
        navigation.filter("missing_nucleus_roi");assertEquals(0,navigation.position());
        assertEquals(1,navigation.size());assertEquals("a",navigation.move(-1).id());
        assertSame(roi,navigation.move(1).roi());
        navigation.filter("missing_cell_roi");assertEquals(0,navigation.size());assertNull(navigation.move(1));
        navigation.filter(null);assertEquals("b",navigation.move(-1).id());
        assertEquals(4,cells.size());assertEquals(3,rejected.size());assertNull(cells.getFirst().nucleus());
        assertThrows(IllegalArgumentException.class,()->navigation.move(0));
    }
}
