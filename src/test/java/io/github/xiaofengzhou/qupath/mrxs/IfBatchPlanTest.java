package io.github.xiaofengzhou.qupath.mrxs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import static org.junit.jupiter.api.Assertions.*;

class IfBatchPlanTest {
    @TempDir Path root;
    IfBatchPlan plan() {
        var request=new IfBatchAnalysis.Request("slide1-roi1",root.resolve("组织.mrxs"),
                ROIs.createRectangleROI(1.123456789,2.987654321,17.111111111,18.999999999,ImagePlane.getDefaultPlane()),
                new IfAnalysisProfile(1,0,List.of(new IfAnalysisProfile.Marker(1,"marker",30,31,32))),List.of("DAPI","marker"),
                new IfInstanSeg.Config(root.resolve("trusted-model"),"mps",1,512,32,List.of(0,1)),true,null);
        return new IfBatchPlan(1,List.of(IfBatchPlan.entry(request,new IfBatchQueue.Item(request.id(),"identity"))));
    }
    @Test void dataOnlyRoundTripPreservesExactGeometrySettingsAndDoesNotNeedSourceFiles()throws Exception {
        var plan=plan();var file=root.resolve("batch-plan.json");plan.writeOrCheck(file);
        byte[] original=Files.readAllBytes(file);var loaded=IfBatchPlan.read(file);loaded.writeOrCheck(file);
        assertArrayEquals(original,Files.readAllBytes(file));
        var before=plan.requests().getFirst();var after=loaded.requests().getFirst();
        assertEquals(before.roi().getBoundsX(),after.roi().getBoundsX(),0);
        assertEquals(before.roi().getBoundsY(),after.roi().getBoundsY(),0);
        assertEquals(before.roi().getBoundsWidth(),after.roi().getBoundsWidth(),0);
        assertEquals(before.roi().getBoundsHeight(),after.roi().getBoundsHeight(),0);
        assertEquals(before.profile(),after.profile());assertEquals(before.ai(),after.ai());
        assertEquals(before.source(),after.source());assertEquals(plan.items(),loaded.items());
        assertFalse(Files.exists(before.source()));assertFalse(Files.exists(before.ai().modelDirectory()));
    }
    @Test void changedIdentityCannotOverwritePlanAndDuplicateIdsRejected()throws Exception {
        var plan=plan();var file=root.resolve("batch-plan.json");plan.writeOrCheck(file);byte[] before=Files.readAllBytes(file);
        var r=plan.requests().getFirst();var changed=new IfBatchPlan(1,List.of(IfBatchPlan.entry(r,new IfBatchQueue.Item(r.id(),"changed"))));
        assertThrows(IOException.class,()->changed.writeOrCheck(file));assertArrayEquals(before,Files.readAllBytes(file));
        assertThrows(IllegalArgumentException.class,()->new IfBatchPlan(1,List.of(plan.tasks().getFirst(),plan.tasks().getFirst())));
    }
    @Test void malformedAndRemotePlansRejectedWithoutOpeningSource()throws Exception {
        var file=root.resolve("batch-plan.json");Files.writeString(file,"{");assertThrows(IOException.class,()->IfBatchPlan.read(file));
        String json=qupath.lib.io.GsonTools.getInstance().toJson(plan());
        json=json.replace(plan().tasks().getFirst().sourceUri(),"https://example.com/source.mrxs");
        Files.writeString(file,json);assertThrows(IOException.class,()->IfBatchPlan.read(file));
    }
}
