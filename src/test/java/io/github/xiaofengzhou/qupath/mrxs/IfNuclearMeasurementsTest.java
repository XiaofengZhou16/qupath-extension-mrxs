package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;
import static org.junit.jupiter.api.Assertions.*;

class IfNuclearMeasurementsTest {
    @TempDir Path temporary;
    static ROI rect(double x,double y,double w,double h){return ROIs.createRectangleROI(x,y,w,h,ImagePlane.getDefaultPlane());}
    static IfAnalysisProfile profile(){return new IfAnalysisProfile(1,0,List.of(
            new IfAnalysisProfile.Marker(0,"A",100,100,100),new IfAnalysisProfile.Marker(1,"B",100,100,100),
            new IfAnalysisProfile.Marker(2,"C",100,100,100)));}
    @Test void validNucleiSurviveMissingCytoplasmAndMissingCellWithoutChangingLegacyResults()throws Exception {
        var cells=List.of(new IfAnalysis.Cell("normal",rect(4,0,4,4),rect(5,1,2,2)),
                new IfAnalysis.Cell("no-cytoplasm",rect(12,0,2,2),rect(12,0,2,2)),
                new IfAnalysis.Cell("no-cell",null,rect(28,1,2,2)),
                new IfAnalysis.Cell("outside-cell",rect(-1,0,4,4),rect(0,1,2,2)),
                new IfAnalysis.Cell("missing-nucleus",rect(4,0,4,4),null));
        try(var server=new IfAnalysisTest.TruthServer(3)) {
            var r=IfAnalysis.analyze(server,rect(0,0,32,8),profile(),cells,()->false,p->{});
            assertEquals(1,r.cells().size());assertEquals("normal",r.cells().getFirst().id());
            var n=r.nuclearMeasurements();assertEquals(4,n.nuclei().size());assertEquals(1,n.rejected().size());
            assertEquals(List.of(1,3,7,0),n.nuclei().stream().map(IfNuclearMeasurements.Measurement::mask).toList());
            for(var value:n.nuclei())assertEquals(4,value.pixels());
            assertEquals(3,n.combinations().stream().filter(c->c.mask()==1).findFirst().orElseThrow().inclusive());
            assertEquals(1,n.combinations().stream().filter(c->c.mask()==1).findFirst().orElseThrow().exact());
            assertEquals(r.tilesRead(),server.reads); // supplemental sampling never calls readRegion
            Path out=temporary.resolve("run");IfAnalysisExport.write(out,"synthetic",rect(0,0,32,8),r,cells,Map.of(),()->false);
            String csv=Files.readString(out.resolve("nuclear-combinations.csv"));
            var rows=IfReportCsv.parse(csv,1000,10);
            var row=rows.stream().filter(v->v.get(0).equals("A")&&v.get(2).equals("inclusive")&&v.get(5).equals("all_measured_nuclei")).findFirst().orElseThrow();
            assertEquals(List.of("A","1","inclusive","NPN_NUCLEUS_ONLY","3","all_measured_nuclei","4"),row.subList(0,7));
            assertEquals(0.75,Double.parseDouble(row.get(7)));
            assertEquals(List.of("missing-nucleus","missing_nucleus_roi"),IfReportCsv.parse(Files.readString(out.resolve("excluded-nuclei.csv")),100,2).get(1));
            assertEquals(1,IfAnalysisHistory.scan(out,()->false).entries().size());
            assertDoesNotThrow(()->IfBatchReportInput.load(List.of(out),()->false));
        }
    }
    @Test void independentlyRejectsInvalidOutsideDifferentPlaneAndUnsampledNuclei()throws Exception {
        var bad=ROIs.createPolygonROI(new double[]{4,12,4,8},new double[]{0,4,4,0},ImagePlane.getDefaultPlane());
        var cells=List.of(new IfAnalysis.Cell("invalid",null,bad),
                new IfAnalysis.Cell("outside",null,rect(31,0,2,2)),
                new IfAnalysis.Cell("outside-roi",null,rect(20,0,2,2)),
                new IfAnalysis.Cell("plane",null,ROIs.createRectangleROI(1,1,2,2,ImagePlane.getPlane(1,0))),
                new IfAnalysis.Cell("tiny",null,rect(1.01,1.01,0.1,0.1)));
        try(var server=new IfAnalysisTest.TruthServer(3)) {
            var r=IfAnalysis.analyze(server,rect(0,0,16,8),profile(),cells,()->false,p->{});
            assertTrue(r.nuclearMeasurements().nuclei().isEmpty());
            assertEquals(List.of("invalid_nucleus_geometry","nucleus_outside_image","nucleus_not_fully_inside_roi","different_image_plane","no_valid_nucleus_pixels"),
                    r.nuclearMeasurements().rejected().stream().map(IfNuclearMeasurements.Rejection::reason).toList());
            Path out=temporary.resolve("empty");IfAnalysisExport.write(out,"synthetic",rect(0,0,16,8),r,cells,Map.of(),()->false);
            var rows=IfReportCsv.parse(Files.readString(out.resolve("nuclear-combinations.csv")),1000,10);
            for(var row:rows.subList(1,rows.size())){assertEquals("0",row.get(6));assertEquals("",row.get(7));}
        }
    }
    @Test void duplicateIdsAreRejectedBeforeAnyRead()throws Exception {
        try(var server=new IfAnalysisTest.TruthServer(3)) {
            var c=new IfAnalysis.Cell("same",null,rect(1,1,2,2));
            assertThrows(IllegalArgumentException.class,()->IfAnalysis.analyze(server,rect(0,0,32,8),profile(),List.of(c,c),()->false,p->{}));
            assertEquals(0,server.reads);
        }
    }
}
