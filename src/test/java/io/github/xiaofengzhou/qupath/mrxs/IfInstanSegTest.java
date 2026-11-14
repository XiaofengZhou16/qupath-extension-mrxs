package io.github.xiaofengzhou.qupath.mrxs;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class IfInstanSegTest {
    @org.junit.jupiter.api.Test void outputAuditDistinguishesMissingAndIdenticalCompartments(){
        var roi=qupath.lib.roi.ROIs.createRectangleROI(0,0,8,8,qupath.lib.regions.ImagePlane.getDefaultPlane());
        var nucleus=qupath.lib.roi.ROIs.createRectangleROI(2,2,2,2,qupath.lib.regions.ImagePlane.getDefaultPlane());
        var objects=java.util.List.of(qupath.lib.objects.PathObjects.createCellObject(roi,null),
                qupath.lib.objects.PathObjects.createCellObject(roi,roi),qupath.lib.objects.PathObjects.createCellObject(roi,nucleus));
        var audit=IfInstanSeg.auditOutput(objects,()->false);
        org.junit.jupiter.api.Assertions.assertEquals(1L,audit.get("missingNucleusROI"));
        org.junit.jupiter.api.Assertions.assertEquals(1L,audit.get("cellEqualsNucleusTopologically"));
        org.junit.jupiter.api.Assertions.assertEquals(3L,audit.get("cellObjects"));
        org.junit.jupiter.api.Assertions.assertEquals(true,audit.get("snapshotPreservesIdentityAndROIReferences"));
        org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.CancellationException.class,()->IfInstanSeg.auditOutput(objects,()->true));
    }
    @Test void rejectsEmptyFailedOrInterruptedInferenceInsteadOfReportingNegativeCells() {
        assertThrows(IOException.class, () -> IfInstanSeg.validateOutcome(0,0,false));
        assertThrows(IOException.class, () -> IfInstanSeg.validateOutcome(10,1,false));
        assertThrows(CancellationException.class, () -> IfInstanSeg.validateOutcome(10,0,true));
        assertDoesNotThrow(() -> IfInstanSeg.validateOutcome(10,0,false));
        assertThrows(IOException.class, () -> IfInstanSeg.validateTileCoverage(10,9));
        assertThrows(IOException.class, () -> IfInstanSeg.validateTileCoverage(0,0));
        assertDoesNotThrow(() -> IfInstanSeg.validateTileCoverage(10,10));
    }
    @Test void validatesBoundedResourcesAndExplicitChannelSelection() {
        assertThrows(IllegalArgumentException.class, () -> new IfInstanSeg.Config(Path.of("model"),"cpu",16,512,32,List.of(0)));
        assertThrows(IllegalArgumentException.class, () -> new IfInstanSeg.Config(Path.of("model"),"cpu",2,512,256,List.of(0)));
        assertThrows(IllegalArgumentException.class, () -> new IfInstanSeg.Config(Path.of("model"),"cpu",2,512,32,List.of()));
        assertThrows(IllegalArgumentException.class, () -> new IfInstanSeg.Config(Path.of("model"),"cpu",2,512,32,List.of(0,0)));
        assertDoesNotThrow(() -> new IfInstanSeg.Config(Path.of("model"),"mps",2,512,32,List.of(0,1)));
    }
    @Test void optionalExtensionAbsenceDoesNotPreventReaderLoading() {
        assertFalse(IfInstanSeg.isAvailable(new ClassLoader(null) {}));
    }
}
