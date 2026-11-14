package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.objects.PathAnnotationObject;
import static org.junit.jupiter.api.Assertions.*;

class IfHistoricalReviewTest {
    @TempDir Path temp;
    @Test void largeHistoricalCopyCarriesExclusionReasonsWithoutUnsafeContainers()throws Exception {
        try(var s=new IfAnalysisTest.TruthServer(4)){
            var plane=ImagePlane.getDefaultPlane();var roi=ROIs.createRectangleROI(0,0,32,8,plane);
            var cell=ROIs.createRectangleROI(1,1,6,6,plane);
            int count=4097;
            var cells=java.util.stream.IntStream.range(0,count).mapToObj(i->new IfAnalysis.Cell("missing-"+i,cell,null)).toList();
            var original=temp.resolve("large-history");
            IfAnalysisExport.analyzeAndWrite(original,s,roi,IfAreaMasksTest.profile(0),cells,Map.of(),false,()->false,p->{});
            var prepared=IfHistoricalReview.prepare(original,attachTestLocator(original,s),()->false);
            assertEquals(count,prepared.editableCandidates());assertEquals(count,prepared.group().nChildObjects());
            var data=new qupath.lib.images.ImageData<>(s,qupath.lib.images.ImageData.ImageType.FLUORESCENCE);
            data.getHierarchy().getRootObject().addChildObject(prepared.group());
            Path saved=temp.resolve("large-grouped.qpdata");qupath.lib.io.PathIO.writeImageData(saved,data);
            var restored=qupath.lib.io.PathIO.readHierarchy(saved).getRootObject().getChildObjects().iterator().next();
            assertEquals(count,restored.nChildObjects());assertEquals(count,IfCellReview.capture(restored).cells().size());
            var compartments=restored.getDescendantObjects(null).stream().filter(o->o.hasROI()).toList();
            assertEquals(count,compartments.size());
            assertTrue(compartments.stream().allMatch(o->"missing_nucleus_roi".equals(o.getMetadata().get("IF historical exclusion reason"))));
            assertEquals(IfCellReview.capture(prepared.group()).provenance(),IfCellReview.capture(restored).provenance());
        }
    }
    @Test void independentCopyRemeasuresAndRetainsHistoricalExclusionProvenance()throws Exception {
        checkRoundtrip(false);
    }
    @Test void experimentalContainersRetainRepairsProvenanceAndMeasurementsAfterReload()throws Exception {
        checkRoundtrip(true);
    }
    private void checkRoundtrip(boolean batched)throws Exception {
        try(var s=new IfAnalysisTest.TruthServer(4)){
            var plane=ImagePlane.getDefaultPlane();var roi=ROIs.createRectangleROI(0,0,32,8,plane);
            var cell=ROIs.createRectangleROI(1,1,6,6,plane);var nucleus=ROIs.createRectangleROI(2,2,2,2,plane);
            var candidates=List.of(new IfAnalysis.Cell("measured",cell,nucleus),new IfAnalysis.Cell("missing-nucleus",cell,null),new IfAnalysis.Cell("no-geometry",null,null));
            var profile=IfAreaMasksTest.profile(0);Path original=temp.resolve("original");
            IfAnalysisExport.analyzeAndWrite(original,s,roi,profile,candidates,Map.of(),false,()->false,p->{});
            var source=attachTestLocator(original,s);
            var bytes=new HashMap<Path,byte[]>();try(var files=Files.list(original)){for(var f:files.toList())if(Files.isRegularFile(f))bytes.put(f,Files.readAllBytes(f));}
            int reads=s.reads;var prepared=IfHistoricalReview.prepare(original,source,()->false);
            assertEquals(reads,s.reads,"Preparation must not reread original image pixels");
            assertEquals(2,prepared.editableCandidates());assertEquals(1,prepared.geometrylessCandidates());assertNull(prepared.group().getParent());
            var snapshot=IfCellReview.capture(prepared.group());assertEquals(2,snapshot.cells().size());
            if(batched){
                assertEquals(2,IfReviewBatchExperiment.regroup(prepared.group(),2));
                assertEquals(snapshot.provenance(),IfCellReview.capture(prepared.group()).provenance());
            }
            var lineage=(com.google.gson.JsonObject)snapshot.provenance().get("historicalReview");
            assertEquals("no-geometry",lineage.getAsJsonArray("geometrylessCandidateIds").get(0).getAsString());
            assertEquals(5,lineage.getAsJsonObject("sourceFilesSha256").size());
            var missing=prepared.group().getDescendantObjects(null).stream().filter(o->"missing-nucleus".equals(o.getMetadata().get("IF source cell ID"))).findFirst().orElseThrow();
            assertEquals("missing_nucleus_roi",missing.getMetadata().get("IF historical exclusion reason"));
            var manual=qupath.lib.objects.PathObjects.createAnnotationObject(nucleus);
            var repaired=IfCellReview.prepareMissingNucleus(prepared.group(),missing,manual);prepared.group().addChildObject(repaired);
            var measured=prepared.group().getDescendantObjects(null).stream().filter(o->"measured".equals(o.getMetadata().get("IF source cell ID"))&&"cell".equals(o.getMetadata().get(IfCellReview.COMPARTMENT))).findFirst().orElseThrow();
            ((PathAnnotationObject)measured).setROI(ROIs.createRectangleROI(1,1,7,6,plane));
            assertEquals(36,cell.getArea()); // Editing a copy must not mutate the input ROI.
            var revised=IfCellReview.capture(prepared.group());Path output=temp.resolve("remeasured");
            var result=IfAnalysisExport.analyzeAndWrite(output,s,prepared.group().getROI(),profile,revised.cells(),revised.provenance(),false,()->false,p->{});
            assertEquals(2,result.cells().size());assertFalse(Files.exists(output.resolve("INCOMPLETE")));
            var manifest=com.google.gson.JsonParser.parseString(Files.readString(output.resolve("manifest.json"))).getAsJsonObject();
            assertEquals(3,manifest.getAsJsonObject("segmentation").getAsJsonObject("historicalReview").get("originalProposedCells").getAsInt());
            var data=new qupath.lib.images.ImageData<>(s,qupath.lib.images.ImageData.ImageType.FLUORESCENCE);
            data.getHierarchy().getRootObject().addChildObject(prepared.group());
            Path saved=temp.resolve("edited-history.qpdata");qupath.lib.io.PathIO.writeImageData(saved,data);
            var hierarchy=qupath.lib.io.PathIO.readHierarchy(saved);
            var restoredGroup=hierarchy.getAnnotationObjects().stream().filter(o->o.getID().equals(prepared.group().getID())).findFirst().orElseThrow();
            if(batched){
                assertEquals(2,restoredGroup.getChildObjects().stream().filter(o->!o.hasROI()).count());
                var restoredMissing=restoredGroup.getDescendantObjects(null).stream().filter(o->"missing-nucleus".equals(o.getMetadata().get("IF source cell ID"))&&"cell".equals(o.getMetadata().get(IfCellReview.COMPARTMENT))).findFirst().orElseThrow();
                assertEquals("missing_nucleus_roi",restoredMissing.getMetadata().get("IF historical exclusion reason"));
                assertThrows(IllegalArgumentException.class,()->IfCellReview.prepareMissingNucleus(restoredGroup,restoredMissing,manual));
            }
            assertEquals(new HashMap<>(prepared.group().getMetadata()),new HashMap<>(restoredGroup.getMetadata()));
            var restored=IfCellReview.capture(restoredGroup);
            assertEquals(revised.provenance(),restored.provenance());
            assertEquals(revised.cells().size(),restored.cells().size());
            for(int i=0;i<revised.cells().size();i++){
                var a=revised.cells().get(i);var b=restored.cells().get(i);assertEquals(a.id(),b.id());
                assertTrue(a.cell().getGeometry().equalsExact(b.cell().getGeometry()));
                assertTrue(a.nucleus().getGeometry().equalsExact(b.nucleus().getGeometry()));
            }
            Path reloadedOutput=temp.resolve("after-reload");
            IfAnalysisExport.analyzeAndWrite(reloadedOutput,s,restoredGroup.getROI(),profile,restored.cells(),restored.provenance(),false,()->false,p->{});
            for(String name:List.of("cells.csv","combinations.csv"))assertArrayEquals(Files.readAllBytes(output.resolve(name)),Files.readAllBytes(reloadedOutput.resolve(name)),name);
            if(batched){
                var newCell=qupath.lib.objects.PathObjects.createAnnotationObject(ROIs.createRectangleROI(12,1,6,6,plane));
                var newNucleus=qupath.lib.objects.PathObjects.createAnnotationObject(ROIs.createRectangleROI(13,2,2,2,plane));
                var added=IfCellReview.prepareManualPair(restoredGroup,newCell,newNucleus);
                var folder=restoredGroup.getChildObjects().stream().filter(o->!o.hasROI()).findFirst().orElseThrow();
                folder.addChildObjects(added);
                assertThrows(IllegalArgumentException.class,()->IfCellReview.prepareManualPair(restoredGroup,newCell,newNucleus));
                assertNull(newCell.getParent());assertNull(newNucleus.getParent());
                var withManual=IfCellReview.capture(restoredGroup);
                assertEquals(3,withManual.cells().size());
                var measuredManual=IfAnalysis.analyze(s,roi,profile,withManual.cells(),()->false,p->{});
                assertEquals(3,measuredManual.cells().size());
            }
            for(var e:bytes.entrySet())assertArrayEquals(e.getValue(),Files.readAllBytes(e.getKey()));
            assertThrows(java.util.concurrent.CancellationException.class,()->IfHistoricalReview.prepare(original,source,()->true));
        }
    }
    private static IfHistorySource.Current attachTestLocator(Path run,IfAnalysisTest.TruthServer s)throws Exception {
        var c=IfHistorySource.capture(s);var uris=List.of("file:///explicit-synthetic-test.mrxs");
        Path p=run.resolve("manifest.json");var m=com.google.gson.JsonParser.parseString(Files.readString(p)).getAsJsonObject();var a=new com.google.gson.JsonArray();uris.forEach(a::add);m.getAsJsonObject("source").add("uris",a);Files.writeString(p,m.toString());
        return new IfHistorySource.Current(uris,c.serverClass(),c.width(),c.height(),c.zSlices(),c.timepoints(),c.channels(),c.pixelWidth(),c.pixelHeight(),c.downsamples());
    }
}
