package io.github.xiaofengzhou.qupath.mrxs;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.roi.ROIs;
import qupath.lib.regions.ImagePlane;
import qupath.lib.images.ImageData;
import static org.junit.jupiter.api.Assertions.*;
class IfHistoricalExclusionsTest {
    @TempDir Path temp;
    @Test void indexedFiltersPreserveOrderResetAndExcludeUnlocatableTargets()throws Exception {
        try(var s=new IfAnalysisTest.TruthServer(4)) {
            var data=new ImageData<>(s,ImageData.ImageType.FLUORESCENCE);
            var shape=ROIs.createRectangleROI(4,1,4,4,ImagePlane.getDefaultPlane());
            var outside=ROIs.createRectangleROI(50,1,4,4,ImagePlane.getDefaultPlane());
            var wrongPlane=ROIs.createRectangleROI(4,1,4,4,ImagePlane.getPlane(1,0));
            var items=new ArrayList<IfHistoricalExclusions.Item>();
            for(int i=0;i<20000;i++)items.add(new IfHistoricalExclusions.Item("id-"+i,"test",i%2==0?"even":"odd",shape,null));
            items.add(new IfHistoricalExclusions.Item("missing","test","missing",null,null));
            items.add(new IfHistoricalExclusions.Item("outside","test","outside",outside,null));
            items.add(new IfHistoricalExclusions.Item("plane","test","plane",wrongPlane,null));
            var review=new IfHistoricalExclusions(items,data);
            assertEquals(20003,review.total());assertEquals(20002,review.drawable());
            assertEquals(List.of("even","missing","odd","outside","plane"),review.reasons());
            for(int i=0;i<100;i++) {
                review.filter("odd");assertEquals("id-1",review.move(1).id());assertEquals("id-3",review.move(1).id());
                review.filter("even");assertEquals("id-19998",review.move(-1).id());assertEquals("id-0",review.move(1).id());
                review.filter(null);assertEquals("id-0",review.move(1).id());assertEquals("id-19999",review.move(-1).id());
                for(String reason:List.of("missing","outside","plane","unknown")){review.filter(reason);assertNull(review.move(1));}
            }
            assertThrows(IllegalArgumentException.class,()->review.move(0));
            var polls=new java.util.concurrent.atomic.AtomicInteger();
            assertThrows(java.util.concurrent.CancellationException.class,
                    ()->new IfHistoricalExclusions(items,data,()->polls.incrementAndGet()>=100));
            assertEquals(100,polls.get());
            assertTrue(data.getHierarchy().getDetectionObjects().isEmpty());
        }
    }
    @Test void preservesMissingCompartmentsAndFiltersNavigationWithoutMeasurements()throws Exception {
        try(var s=new IfAnalysisTest.TruthServer(4)){
            var roi=ROIs.createRectangleROI(0,0,32,8,ImagePlane.getDefaultPlane());var shape=ROIs.createRectangleROI(4,1,4,4,ImagePlane.getDefaultPlane());
            var cells=List.of(new IfAnalysis.Cell("nucleus-only",null,shape),new IfAnalysis.Cell("cell-only",shape,null),new IfAnalysis.Cell("no-shapes",null,null),new IfAnalysis.Cell("no-cytoplasm",shape,shape));
            Path p=temp.resolve("run");IfAnalysisExport.analyzeAndWrite(p,s,roi,IfAreaMasksTest.profile(0),cells,Map.of(),false,()->false,v->{});
            var entry=IfAnalysisHistory.scan(p,()->false).entries().getFirst();var loaded=IfHistoricalExclusions.load(entry,()->false);assertEquals(4,loaded.size());
            var data=new ImageData<>(s,ImageData.ImageType.FLUORESCENCE);var review=new IfHistoricalExclusions(loaded,data);
            assertEquals(4,review.total());assertEquals(3,review.drawable());review.filter("missing_cell_roi");var target=review.move(1);assertEquals("nucleus-only",target.id());assertNull(target.cell());assertNotNull(target.nucleus());assertEquals(target,review.move(1));
            review.filter("missing_nucleus_roi");assertEquals("cell-only",review.move(-1).id());review.filter("absent-reason");assertNull(review.move(1));
            assertTrue(data.getHierarchy().getDetectionObjects().isEmpty());assertEquals(0,entry.measuredCells());
            var original=Files.readString(p.resolve("excluded-cells.geojson"));var json=com.google.gson.JsonParser.parseString(original).getAsJsonObject();json.getAsJsonArray("features").remove(0);Files.writeString(p.resolve("excluded-cells.geojson"),json.toString());
            assertThrows(java.io.IOException.class,()->IfHistoricalExclusions.load(entry,()->false));assertThrows(java.util.concurrent.CancellationException.class,()->IfHistoricalExclusions.load(entry,()->true));
        }
    }
}
