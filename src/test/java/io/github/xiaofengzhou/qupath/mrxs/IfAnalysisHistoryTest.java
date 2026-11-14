package io.github.xiaofengzhou.qupath.mrxs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import static org.junit.jupiter.api.Assertions.*;

class IfAnalysisHistoryTest {
    @TempDir Path temp;
    private Path makeRun(String name) throws Exception {
        try(var server=new IfAnalysisTest.TruthServer(4)) {
            var profile=new IfAnalysisProfile(1,0,List.of(new IfAnalysisProfile.Marker(0,"A",30,30,30)));
            Path run=temp.resolve(name);
            IfAnalysisExport.analyzeAndWrite(run,server,ROIs.createRectangleROI(0,0,32,8,ImagePlane.getDefaultPlane()),
                    profile,List.of(),Map.of("method","test"),false,()->false,p->{});
            return run;
        }
    }
    @Test void completeRunReadableDirectlyAndFromParentWithoutWriting() throws Exception {
        Path run=makeRun("good");byte[] before=Files.readAllBytes(run.resolve("manifest.json"));
        var catalog=IfAnalysisHistory.scan(temp,()->false);
        assertEquals(1,catalog.entries().size());assertTrue(catalog.rejected().isEmpty());
        var entry=catalog.entries().getFirst();assertEquals(0,entry.proposedCells());
        assertEquals("locator_metadata_only",entry.sourceStatus());assertEquals(256,entry.roi().getArea());
        assertEquals(1,IfAnalysisHistory.scan(run,()->false).entries().size());
        assertArrayEquals(before,Files.readAllBytes(run.resolve("manifest.json")));
    }
    @Test void incompleteMissingAndMalformedRunsAreListedAsSkipped() throws Exception {
        Path incomplete=makeRun("incomplete");Files.writeString(incomplete.resolve("INCOMPLETE"),"cancelled");
        Path missing=makeRun("missing");Files.delete(missing.resolve("cells.csv"));
        Path malformed=makeRun("malformed");Files.writeString(malformed.resolve("manifest.json"),"{");
        makeRun("valid");var catalog=IfAnalysisHistory.scan(temp,()->false);
        assertEquals(1,catalog.entries().size());assertEquals(3,catalog.rejected().size());
        assertTrue(IfAnalysisHistory.format(catalog).contains("SKIPPED"));
    }
    @Test void cancellationAndLegacyIdentityAreExplicit() throws Exception {
        Path run=makeRun("legacy");
        var json=com.google.gson.JsonParser.parseString(Files.readString(run.resolve("manifest.json"))).getAsJsonObject();
        json.remove("source");Files.writeString(run.resolve("manifest.json"),json.toString());
        assertEquals("legacy_source_unknown",IfAnalysisHistory.scan(run,()->false).entries().getFirst().sourceStatus());
        assertThrows(java.util.concurrent.CancellationException.class,()->IfAnalysisHistory.scan(temp,()->true));
    }
    @Test void inconsistentProfileAndLinkedManifestAreRejected() throws Exception {
        Path run=makeRun("mismatch");
        Files.writeString(run.resolve("profile.json"),"{\"schemaVersion\":1,\"resolutionLevel\":1,\"markers\":[{\"channel\":0,\"name\":\"A\",\"pixelThreshold\":30,\"nucleusThreshold\":30,\"cytoplasmThreshold\":30}]}");
        assertEquals(1,IfAnalysisHistory.scan(run,()->false).rejected().size());
        Path linked=makeRun("linked"),manifest=linked.resolve("manifest.json");
        Files.move(manifest,temp.resolve("saved.json"));Files.createSymbolicLink(manifest,temp.resolve("saved.json"));
        assertEquals(1,IfAnalysisHistory.scan(linked,()->false).rejected().size());
    }
}
