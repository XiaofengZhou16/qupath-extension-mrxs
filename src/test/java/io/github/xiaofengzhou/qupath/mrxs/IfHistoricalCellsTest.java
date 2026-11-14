package io.github.xiaofengzhou.qupath.mrxs;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import static org.junit.jupiter.api.Assertions.*;
class IfHistoricalCellsTest {
    @TempDir Path temp;
    private Path make()throws Exception {
        return make(false);
    }
    private Path make(boolean fractional)throws Exception {
        Path path=temp.resolve("run");var plane=ImagePlane.getDefaultPlane();
        try(var server=new IfAnalysisTest.TruthServer(4)) {
            var nucleus=fractional?ROIs.createPolygonROI(new double[]{4.123456,7.456789,7.876543,4.765432},new double[]{0.123456,0.456789,3.876543,3.765432},plane):ROIs.createRectangleROI(4,0,4,4,plane);
            var cell=new IfAnalysis.Cell("cell-1",ROIs.createRectangleROI(4,0,8,4,plane),nucleus);
            IfAnalysisExport.analyzeAndWrite(path,server,ROIs.createRectangleROI(0,0,32,8,plane),new IfAnalysisProfile(1,0,List.of(new IfAnalysisProfile.Marker(0,"A",100,100,100),new IfAnalysisProfile.Marker(1,"B",100,100,100))),List.of(cell),Map.of(),false,()->false,v->{});
        }return path;
    }
    @Test void fractionalPolygonRoundtripPreservesCentroid()throws Exception {
        Path p=make(true);var entry=IfAnalysisHistory.scan(p,()->false).entries().getFirst();
        var loaded=IfHistoricalCells.load(entry,()->false);
        assertEquals(1,loaded.cells().size());
        assertTrue(Files.readString(p.resolve("cells.geojson")).contains("4.12345"));
    }
    @Test void restoresMeasuredGeometryAndPnWithoutMutatingSources()throws Exception {
        Path p=make();var entry=IfAnalysisHistory.scan(p,()->false).entries().getFirst();byte[] original=Files.readAllBytes(p.resolve("cells.geojson"));
        var cells=IfHistoricalCells.load(entry,()->false);assertEquals(1,cells.cells().size());assertEquals(3,cells.pnMasks().get("cell-1"));assertEquals(32,cells.cells().getFirst().cell().getArea());
        assertArrayEquals(original,Files.readAllBytes(p.resolve("cells.geojson")));
    }
    @Test void rejectsPhenotypeAndIdMismatchAndCancelledRead()throws Exception {
        Path p=make();var entry=IfAnalysisHistory.scan(p,()->false).entries().getFirst();Path csv=p.resolve("cells.csv");String original=Files.readString(csv);
        String changed=original.replace("\"cell-1\"","\"different-id\"");assertNotEquals(original,changed);Files.writeString(csv,changed);
        assertThrows(java.io.IOException.class,()->IfHistoricalCells.load(entry,()->false));Files.writeString(csv,original);
        Path geo=p.resolve("cells.geojson");String text=Files.readString(geo);var json=com.google.gson.JsonParser.parseString(text).getAsJsonObject();
        var features=json.getAsJsonArray("features");
        features.get(0).getAsJsonObject().getAsJsonObject("properties").getAsJsonObject("measurements").addProperty("IF: PN mask",0);
        Files.writeString(geo,json.toString());
        assertThrows(java.io.IOException.class,()->IfHistoricalCells.load(entry,()->false));
        features.remove(0);Files.writeString(geo,json.toString());
        assertThrows(java.io.IOException.class,()->IfHistoricalCells.load(entry,()->false));
        assertThrows(java.util.concurrent.CancellationException.class,()->IfHistoricalCells.load(entry,()->true));
    }
}
