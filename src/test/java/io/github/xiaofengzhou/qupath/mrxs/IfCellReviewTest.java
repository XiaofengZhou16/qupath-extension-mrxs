package io.github.xiaofengzhou.qupath.mrxs;

import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.roi.ROIs;
import qupath.lib.regions.ImagePlane;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class IfCellReviewTest {
    @Test void nativeEditReparentingRetainsOwnerAndDeletionStillRemovesCompartment() {
        var hierarchy=new qupath.lib.objects.hierarchy.PathObjectHierarchy();
        var group=IfCellReview.create(rect(0,0,16,8),List.of(new IfAnalysis.Cell("a",rect(0,0,4,4),rect(1,1,2,2))));
        hierarchy.addObject(group);
        var cell=(PathAnnotationObject)group.getChildObjects().stream().filter(o->"cell".equals(o.getMetadata().get(IfCellReview.COMPARTMENT))).findFirst().orElseThrow();
        cell.setROI(rect(0,0,8,4));
        hierarchy.getRootObject().addChildObject(cell);
        assertEquals(1,group.nDescendants());
        assertSame(group,IfCellReview.owningGroup(cell));
        var captured=IfCellReview.capture(group);
        assertEquals(1,captured.cells().size());assertEquals(32,captured.cells().getFirst().cell().getArea());
        assertNotNull(captured.cells().getFirst().nucleus());
        assertSame(hierarchy.getRootObject(),cell.getParent());
        hierarchy.getRootObject().removeChildObject(cell);
        assertNull(IfCellReview.snapshot(group).getFirst().cell());
    }

    @Test void conflictingEnclosureOrDuplicateOwnerFailsInsteadOfSilentlyLosingCells() {
        var root=new qupath.lib.objects.hierarchy.PathObjectHierarchy().getRootObject();
        var group=IfCellReview.create(rect(0,0,16,8),List.of(new IfAnalysis.Cell("a",rect(0,0,4,4),null)));
        var other=IfCellReview.create(group.getROI(),List.of());root.addChildObjects(List.of(group,other));
        var cell=group.getChildObjects().iterator().next();other.addChildObject(cell);
        assertThrows(IllegalArgumentException.class,()->IfCellReview.capture(group));
        root.addChildObject(cell);other.getMetadata().put(IfCellReview.GROUP,group.getMetadata().get(IfCellReview.GROUP));
        assertThrows(IllegalArgumentException.class,()->IfCellReview.capture(group));
        assertThrows(IllegalArgumentException.class,()->IfCellReview.owningGroup(cell));
    }

    @Test void owningGroupNavigationRespectsNestedBoundariesAndRejectsOwnerMismatch(){
        var group=IfCellReview.create(rect(0,0,16,8),List.of(new IfAnalysis.Cell("a",rect(0,0,4,4),null)));
        var cell=group.getChildObjects().iterator().next();
        IfReviewBatchExperiment.regroup(group,1);var batch=group.getChildObjects().iterator().next();
        assertSame(group,IfCellReview.owningGroup(group));assertSame(group,IfCellReview.owningGroup(batch));assertSame(group,IfCellReview.owningGroup(cell));
        var nested=IfCellReview.create(group.getROI(),List.of());group.addChildObject(nested);
        assertSame(nested,IfCellReview.owningGroup(nested));
        nested.addChildObject(cell);
        assertThrows(IllegalArgumentException.class,()->IfCellReview.owningGroup(cell));
        assertThrows(IllegalArgumentException.class,()->IfCellReview.owningGroup(null));
        assertThrows(IllegalArgumentException.class,()->IfCellReview.owningGroup(new PathAnnotationObject()));
    }
    @org.junit.jupiter.api.Test void largeCopyCooperativelyCancelsWithoutReturningPartialGroup() {
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();var progress=new java.util.ArrayList<Double>();
        var cells=java.util.stream.IntStream.range(0,5000).mapToObj(i->new IfAnalysis.Cell("cell-"+i,rect(0,0,4,4),rect(1,1,2,2))).toList();
        assertThrows(java.util.concurrent.CancellationException.class,()->IfCellReview.create(rect(0,0,16,8),cells,cancelled::get,p->{progress.add(p);cancelled.set(true);}));
        assertEquals(java.util.List.of(1024.0/5000),progress);assertEquals(5000,cells.size());
        assertThrows(java.util.concurrent.CancellationException.class,()->IfCellReview.create(rect(0,0,16,8),cells,()->true,p->fail("Already cancelled")));
        var completed=new java.util.ArrayList<Double>();var group=IfCellReview.create(rect(0,0,16,8),cells,()->false,completed::add);
        assertEquals(10000,group.getChildObjects().size());assertEquals(10000,group.nDescendants());
        assertTrue(group.getChildObjects().stream().allMatch(o->o.hasROI()));
        assertEquals(5000,IfCellReview.capture(group).cells().size());
        assertEquals(1.0,completed.getLast());assertNull(group.getParent());
    }
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    private static qupath.lib.roi.interfaces.ROI rect(double x,double y,double w,double h){return ROIs.createRectangleROI(x,y,w,h,ImagePlane.getDefaultPlane());}
    @Test void editCopiesAndRemeasureWithoutMutatingOriginalOrCountingOtherGroups() throws Exception {
        var original=new IfAnalysis.Cell("original",rect(0,0,4,4),rect(1,1,2,2));
        var group=IfCellReview.create(rect(0,0,16,8),List.of(original));
        var cell=(PathAnnotationObject)group.getChildObjects().stream().filter(o->"cell".equals(o.getMetadata().get(IfCellReview.COMPARTMENT))).findFirst().orElseThrow();
        cell.setROI(rect(0,0,8,4));
        var nested=IfCellReview.create(rect(0,0,16,8),List.of(original));group.addChildObject(nested);
        var edited=IfCellReview.snapshot(group);
        assertEquals(1,edited.size());assertEquals(32,edited.getFirst().cell().getArea());assertEquals(16,original.cell().getArea());
        assertNotEquals(original.id(),edited.getFirst().id());
        try(var server=new IfAnalysisTest.TruthServer(4)) {
            var profile=new IfAnalysisProfile(1,0,List.of(new IfAnalysisProfile.Marker(0,"A",100,100,100)));
            var result=IfAnalysis.analyze(server,group.getROI(),profile,edited,()->false,p->{});
            assertEquals(1,result.cells().size());assertEquals(28,result.cells().getFirst().cytoplasmPixels());
        }
    }
    @Test void duplicateCompartmentsFailInsteadOfDoubleCounting() {
        var group=IfCellReview.create(rect(0,0,16,8),List.of(new IfAnalysis.Cell("a",rect(0,0,4,4),null)));
        var first=group.getChildObjects().iterator().next();
        var duplicate=qupath.lib.objects.PathObjects.createAnnotationObject(first.getROI());duplicate.getMetadata().putAll(first.getMetadata());group.addChildObject(duplicate);
        assertThrows(IllegalArgumentException.class,()->IfCellReview.snapshot(group));
    }
    @Test void reviewProvenanceIsDetachedAndExcludesNestedGroupsAndUnrelatedMetadata() {
        var group=IfCellReview.create(rect(0,0,16,8),List.of(new IfAnalysis.Cell("upstream",rect(0,0,6,6),null)));
        var cell=group.getChildObjects().iterator().next();
        var nucleus=qupath.lib.objects.PathObjects.createAnnotationObject(rect(1,1,2,2));
        var repair=IfCellReview.prepareMissingNucleus(group,cell,nucleus);group.addChildObject(repair);
        cell.getMetadata().put("unrelated private note","not exported");
        group.addChildObject(IfCellReview.create(group.getROI(),List.of(new IfAnalysis.Cell("nested",rect(8,0,4,4),null))));
        var captured=IfCellReview.capture(group);
        String json=qupath.lib.io.GsonTools.getInstance().toJson(captured.provenance());
        var parsed=com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        assertEquals(2,parsed.getAsJsonArray("reviewCompartments").size());
        assertEquals(1,captured.cells().size());
        assertTrue(json.contains(nucleus.getID().toString()));assertTrue(json.contains("upstream"));
        assertTrue(json.contains("added previously missing nucleus"));
        assertFalse(json.contains("nested"));assertFalse(json.contains("private note"));
        repair.getMetadata().put("IF review action","later mutation");
        ((PathAnnotationObject)cell).setROI(rect(0,0,8,6));
        assertEquals(json,qupath.lib.io.GsonTools.getInstance().toJson(captured.provenance()));
        assertEquals(36,captured.cells().getFirst().cell().getArea());
        assertThrows(UnsupportedOperationException.class,()->captured.provenance().put("x",1));
    }
    @Test void manualPairUsesContainmentNotSelectionOrderAndPreventsDuplicateAddition() {
        var group=IfCellReview.create(rect(0,0,16,8),List.of());
        var outer=qupath.lib.objects.PathObjects.createAnnotationObject(rect(0,0,6,6));
        var inner=qupath.lib.objects.PathObjects.createAnnotationObject(rect(1,1,2,2));
        var prepared=IfCellReview.prepareManualPair(group,inner,outer);
        assertTrue(group.getChildObjects().isEmpty());assertNull(outer.getParent());
        group.addChildObjects(prepared);
        var result=IfCellReview.snapshot(group);assertEquals(1,result.size());
        assertEquals(36,result.getFirst().cell().getArea());assertEquals(4,result.getFirst().nucleus().getArea());
        assertThrows(IllegalArgumentException.class,()->IfCellReview.prepareManualPair(group,outer,inner));
        var apart=qupath.lib.objects.PathObjects.createAnnotationObject(rect(10,1,2,2));
        assertThrows(IllegalArgumentException.class,()->IfCellReview.prepareManualPair(group,outer,apart));
        assertThrows(IllegalArgumentException.class,()->IfCellReview.prepareManualPair(group,outer,outer));
    }
    @Test void missingNucleusRepairPreservesPairAndBecomesMeasurableWithoutOverwriting() throws Exception {
        var group=IfCellReview.create(rect(0,0,16,8),List.of(new IfAnalysis.Cell("missing",rect(0,0,6,6),null)));
        var cell=group.getChildObjects().iterator().next();String pair=cell.getMetadata().get(IfCellReview.PAIR);
        var nucleus=qupath.lib.objects.PathObjects.createAnnotationObject(rect(1,1,2,2));
        var copy=IfCellReview.prepareMissingNucleus(group,cell,nucleus);
        assertEquals(1,group.getChildObjects().size());assertNull(nucleus.getParent());
        group.addChildObject(copy);var repaired=IfCellReview.snapshot(group);
        assertEquals(1,repaired.size());assertEquals(pair,repaired.getFirst().id());
        assertThrows(IllegalArgumentException.class,()->IfCellReview.prepareMissingNucleus(group,cell,nucleus));
        try(var server=new IfAnalysisTest.TruthServer(4)) {
            var p=new IfAnalysisProfile(1,0,List.of(new IfAnalysisProfile.Marker(0,"A",100,100,100)));
            var result=IfAnalysis.analyze(server,group.getROI(),p,repaired,()->false,v->{});
            assertEquals(1,result.cells().size());assertEquals(4,result.cells().getFirst().nucleusPixels());
            assertEquals(32,result.cells().getFirst().cytoplasmPixels());
        }
    }
    @Test void addingReviewGroupDoesNotReparentOriginalCells() {
        var hierarchy=new qupath.lib.objects.hierarchy.PathObjectHierarchy();
        var parent=qupath.lib.objects.PathObjects.createAnnotationObject(rect(0,0,16,8));
        var original=qupath.lib.objects.PathObjects.createCellObject(rect(0,0,4,4),rect(1,1,2,2));
        parent.addChildObject(original);hierarchy.addObject(parent);
        var group=IfCellReview.create(parent.getROI(),List.of(IfAnalysis.Cell.snapshot((qupath.lib.objects.PathCellObject)original)));
        hierarchy.addObject(group);
        assertSame(parent,original.getParent());assertEquals(1,hierarchy.getDetectionObjects().size());
        assertSame(hierarchy.getRootObject(),group.getParent());
        assertEquals(1,IfCellReview.snapshot(group).size());
    }
    @Test void qpdataRoundtripPreservesEditsPairingAndReanalysis() throws Exception {
        try(var server=new IfAnalysisTest.TruthServer(4)) {
            var data=new qupath.lib.images.ImageData<>(server,qupath.lib.images.ImageData.ImageType.FLUORESCENCE);
            var group=IfCellReview.create(rect(0,0,16,8),List.of(
                    new IfAnalysis.Cell("original",rect(0,0,4,4),rect(1,1,2,2)),
                    new IfAnalysis.Cell("missing-nucleus",rect(8,0,4,4),null)));
            var editable=(PathAnnotationObject)group.getChildObjects().stream().filter(o->"original".equals(o.getMetadata().get("IF source cell ID"))
                    && "cell".equals(o.getMetadata().get(IfCellReview.COMPARTMENT))).findFirst().orElseThrow();
            editable.setROI(rect(0,0,8,4));data.getHierarchy().addObject(group);
            var before=IfCellReview.snapshot(group);
            var file=temporary.resolve("review.qpdata");qupath.lib.io.PathIO.writeImageData(file,data);
            var restored=qupath.lib.io.PathIO.readHierarchy(file);
            var restoredGroup=restored.getAnnotationObjects().stream().filter(o->o.getID().equals(group.getID())).findFirst().orElseThrow();
            assertEquals(new java.util.HashMap<>(group.getMetadata()),new java.util.HashMap<>(restoredGroup.getMetadata()));
            var after=IfCellReview.snapshot(restoredGroup);
            assertEquals(before.stream().map(IfAnalysis.Cell::id).toList(),after.stream().map(IfAnalysis.Cell::id).toList());
            for(int i=0;i<before.size();i++) {
                assertTrue(before.get(i).cell().getGeometry().equalsExact(after.get(i).cell().getGeometry()));
                if(before.get(i).nucleus()==null)assertNull(after.get(i).nucleus());
                else assertTrue(before.get(i).nucleus().getGeometry().equalsExact(after.get(i).nucleus().getGeometry()));
            }
            var profile=new IfAnalysisProfile(1,0,List.of(new IfAnalysisProfile.Marker(0,"A",100,100,100)));
            var a=IfAnalysis.analyze(server,group.getROI(),profile,before,()->false,p->{});
            var b=IfAnalysis.analyze(server,restoredGroup.getROI(),profile,after,()->false,p->{});
            assertEquals(a.cells(),b.cells());assertEquals(a.rejectedCells(),b.rejectedCells());assertEquals(a.combinations(),b.combinations());
            assertEquals(0,restored.getDetectionObjects().size());
        }
    }
}
