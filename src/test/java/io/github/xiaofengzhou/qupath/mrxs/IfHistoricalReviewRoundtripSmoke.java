package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.*;
import qupath.lib.io.PathIO;
import qupath.lib.io.GsonTools;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.roi.ROIs;

/** Real MRXS pixels, synthetic review geometry, native qpdata serialization. No screenshots. */
public class IfHistoricalReviewRoundtripSmoke {
    public static void main(String[] args)throws Exception {
        // Match normal QuPath startup: initialize its server-builder JSON adapters before saving.
        Class.forName("qupath.lib.scripting.QP");
        Path historical=Path.of(args[1]),output=Path.of(args[2]);Files.createDirectory(output);
        var originalBytes=new HashMap<Path,byte[]>();
        try(var files=Files.list(historical)){for(var p:files.toList())if(Files.isRegularFile(p))originalBytes.put(p,Files.readAllBytes(p));}
        try(var server=new MrxsImageServer(Path.of(args[0]).toUri())){
            var prepared=IfHistoricalReview.prepare(historical,IfHistorySource.capture(server),()->false);var group=prepared.group();
            boolean batched=args.length>3&&args[3].equals("batch-experiment");
            int batches=batched?IfReviewBatchExperiment.regroup(group,4):0;
            var missing=group.getDescendantObjects(null).stream().filter(o->"test-cell-only".equals(o.getMetadata().get("IF source cell ID"))).findFirst().orElseThrow();
            var hand=PathObjects.createAnnotationObject(ROIs.createRectangleROI(32855,82015,5,5,group.getROI().getImagePlane()));
            group.addChildObject(IfCellReview.prepareMissingNucleus(group,missing,hand));
            var edited=(PathAnnotationObject)group.getDescendantObjects(null).stream().filter(o->"test-geometry-0".equals(o.getMetadata().get("IF source cell ID"))&&"cell".equals(o.getMetadata().get(IfCellReview.COMPARTMENT))).findFirst().orElseThrow();
            edited.setROI(ROIs.createRectangleROI(32800,81960,31,30,group.getROI().getImagePlane()));
            var before=IfCellReview.capture(group);var data=new ImageData<>(server,ImageData.ImageType.FLUORESCENCE);
            data.getHierarchy().getRootObject().addChildObject(group);Path file=output.resolve("edited-history.qpdata");PathIO.writeImageData(file,data);
            Path projectDirectory=Files.createDirectory(output.resolve("project"));
            var project=qupath.lib.projects.Projects.createProject(projectDirectory.toFile(),java.awt.image.BufferedImage.class);
            var entry=project.addImage(server.getBuilder());entry.setImageName("IF historical review persistence fixture");
            entry.getMetadata().put("IF fixture","Real MRXS; synthetic cell geometries, not biological truth");
            entry.saveImageData(data);project.syncChanges();
            var reopened=qupath.lib.projects.ProjectIO.loadProject(project.getURI(),java.awt.image.BufferedImage.class);
            if(reopened.getImageList().size()!=1)throw new AssertionError("Project entry count changed");
            var reopenedEntry=reopened.getImageList().getFirst();
            if(!entry.getID().equals(reopenedEntry.getID())||!entry.getImageName().equals(reopenedEntry.getImageName())||!Map.copyOf(entry.getMetadata()).equals(Map.copyOf(reopenedEntry.getMetadata())))throw new AssertionError("Project identity/metadata changed");
            var restoredData=reopenedEntry.readImageData();
            try(var restoredServer=restoredData.getServer()){
            if(restoredServer==server||!IfHistorySource.capture(server).equals(IfHistorySource.capture(restoredServer)))throw new AssertionError("Stored server builder did not restore matching metadata");
            var hierarchy=restoredData.getHierarchy();var loaded=hierarchy.getAnnotationObjects().stream().filter(o->o.getID().equals(group.getID())).findFirst().orElseThrow();
            var after=IfCellReview.capture(loaded);
            if(loaded.getChildObjects().stream().filter(o->!o.hasROI()).count()!=batches)throw new AssertionError("Experimental containers changed on reload");
            if(!before.provenance().equals(after.provenance())||before.cells().size()!=after.cells().size())throw new AssertionError("Lineage or count changed on reload");
            for(int i=0;i<before.cells().size();i++){
                var a=before.cells().get(i);var b=after.cells().get(i);if(!a.id().equals(b.id()))throw new AssertionError("Pair changed");
                for(int part=0;part<2;part++){
                    var ar=part==0?a.cell():a.nucleus();var br=part==0?b.cell():b.nucleus();
                    if(ar==null?br!=null:br==null||!ar.getImagePlane().equals(br.getImagePlane())||!ar.getGeometry().equalsExact(br.getGeometry()))throw new AssertionError("Geometry changed");
                }
            }
            var first=IfAnalysisExport.analyzeAndWrite(output.resolve("before-reload"),server,group.getROI(),prepared.entry().profile(),before.cells(),before.provenance(),false,()->false,p->{});
            var second=IfAnalysisExport.analyzeAndWrite(output.resolve("after-reload"),restoredServer,loaded.getROI(),prepared.entry().profile(),after.cells(),after.provenance(),false,()->false,p->{});
            if(first.cells().size()!=4||second.cells().size()!=4)throw new AssertionError("Repair not measured");
            for(String name:List.of("cells.csv","combinations.csv","excluded-cells.csv"))if(!Arrays.equals(Files.readAllBytes(output.resolve("before-reload").resolve(name)),Files.readAllBytes(output.resolve("after-reload").resolve(name))))throw new AssertionError("Measurement changed: "+name);
            for(var e:originalBytes.entrySet())if(!Arrays.equals(e.getValue(),Files.readAllBytes(e.getKey())))throw new AssertionError("Original history changed");
            var report=new LinkedHashMap<String,Object>(Map.of("pairedCandidates",after.cells().size(),"eligibleAfterRepair",second.cells().size(),"geometrylessHistoricalRecords",prepared.geometrylessCandidates(),
                    "projectAndQpdataRestored",true,"storedServerBuilderRestored",true,"measurementCsvBytesMatch",true,"originalHistoryUnchanged",true,"screenshots",false,
                    "limitations","Real reader with synthetic cell geometry; no AI accuracy. Full qpproj entry and qpdata restoration through APIs, not GUI pointer editing."));
            report.put("experimentalContainersRestored",batches);
            Files.writeString(output.resolve("verification.json"),GsonTools.getInstance(true).toJson(report));System.out.println("IF_HISTORICAL_REVIEW_ROUNDTRIP_OK "+GsonTools.getInstance().toJson(report));
            }
        }
    }
}
