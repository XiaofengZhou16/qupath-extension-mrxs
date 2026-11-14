package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.*;
import qupath.lib.io.*;

/** Saved real pointer edit + real source pixels; synthetic contours, no accuracy claim. */
public final class IfNativeEditRemeasureSmoke {
    public static void main(String[] args)throws Exception {
        Path saved=Path.of(args[0]),output=Path.of(args[3]);
        byte[] before=Files.readAllBytes(saved);
        var hierarchy=PathIO.readHierarchy(saved);
        var group=hierarchy.getAnnotationObjects().stream().filter(o->o.getMetadata().containsKey(IfCellReview.GROUP)).findFirst().orElseThrow();
        var edited=hierarchy.getAnnotationObjects().stream().filter(o->o.getID().toString().equals("4e129d95-c70d-4d63-9a37-b9ed0b280366")).findFirst().orElseThrow();
        if(edited.getParent()!=hierarchy.getRootObject()||edited.getROI().getArea()!=1320)throw new AssertionError("Expected saved native edit not present");
        if(IfCellReview.owningGroup(edited)!=group)throw new AssertionError("Owner navigation failed");
        var snapshot=IfCellReview.capture(group);
        var profile=GsonTools.getInstance().fromJson(Files.readString(Path.of(args[2])),IfAnalysisProfile.class);
        try(var server=new MrxsImageServer(Path.of(args[1]).toUri())) {
            var result=IfAnalysisExport.analyzeAndWrite(output,server,group.getROI(),profile,snapshot.cells(),snapshot.provenance(),false,()->false,p->{});
            String pair=edited.getMetadata().get(IfCellReview.PAIR);
            boolean moved=args.length>4 && args[4].equals("user-moved");
            IfAnalysis.CellResult measured=null;
            if(moved) {
                if(edited.getROI().getBoundsX()!=32863.0)throw new AssertionError("Expected user translation not saved");
                if(result.inputCells()!=5||result.cells().size()!=3||result.rejectedCells().size()!=2)throw new AssertionError("Moved cell denominator incorrect");
                if(result.rejectedCells().stream().noneMatch(c->c.id().equals(pair)&&c.reason().equals("nucleus_not_inside_cell")))throw new AssertionError("Moved cell exclusion missing");
                if(result.nuclearMeasurements().nuclei().size()!=5)throw new AssertionError("Independent nucleus population changed");
            } else {
            measured=result.cells().stream().filter(c->c.id().equals(pair)).findFirst().orElseThrow(()->new AssertionError("Edited cell omitted from measured cells"));
            if(measured.nucleusPixels()!=100||measured.cytoplasmPixels()!=1220)throw new AssertionError("Edited compartment pixel accounting incorrect");
            if(result.inputCells()!=5||result.cells().size()!=4||result.rejectedCells().size()!=1)throw new AssertionError("Candidate/denominator accounting changed");
            }
            if(!Arrays.equals(before,Files.readAllBytes(saved)))throw new AssertionError("Saved source project changed");
            var report=new LinkedHashMap<String,Object>();report.put("software",IfSoftwareProvenance.snapshot());
            report.put("nativeEditedObject",edited.getID().toString());report.put("pair",pair);
            report.put("nativeEditedCell",measured);report.put("userTranslationMode",moved);report.put("inputCandidates",result.inputCells());
            report.put("eligibleCells",result.cells().size());report.put("excluded",result.rejectedCells());
            report.put("sourceQpdataUnchanged",true);report.put("analysisSeconds",result.elapsedSeconds());
            report.put("limitations","Real saved pointer edit and real MRXS pixels; synthetic cell geometry. Headless remeasurement, not new-application GUI acceptance or biological accuracy.");
            Files.writeString(output.resolve("native-edit-verification.json"),GsonTools.getInstance(true).toJson(report));
            System.out.println("NATIVE_EDIT_REMEASURE_OK "+GsonTools.getInstance().toJson(report));
        }
    }
}
