package io.github.xiaofengzhou.qupath.mrxs;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.PathObject;

/** Detached historical review copy. No AI rerun, hierarchy mutation or writes to the source run. */
final class IfHistoricalReview {
    static final String LINEAGE="IF historical review provenance";
    record Prepared(IfAnalysisHistory.Entry entry,PathObject group,int editableCandidates,int geometrylessCandidates) {}
    static Prepared prepare(Path directory,IfHistorySource.Current source,BooleanSupplier cancelled)throws IOException {
        var entry=IfHistorySource.validate(directory,source,cancelled);
        var before=hashes(directory,cancelled);
        var measured=IfHistoricalCells.load(entry,cancelled);
        var excluded=IfHistoricalExclusions.load(entry,cancelled);
        var cells=new ArrayList<>(measured.cells());var ids=new HashSet<String>();
        for(var c:cells)if(!ids.add(c.id()))throw new IOException("Duplicate historical identity");
        var geometryless=new ArrayList<String>();var reasons=new HashMap<String,IfHistoricalExclusions.Item>();
        for(var item:excluded){
            check(cancelled);if(!ids.add(item.id()))throw new IOException("Measured/excluded identity overlap");
            reasons.put(item.id(),item);
            if(item.cell()==null&&item.nucleus()==null)geometryless.add(item.id());
            else cells.add(new IfAnalysis.Cell(item.id(),item.cell(),item.nucleus()));
        }
        if(ids.size()!=entry.proposedCells())throw new IOException("Historical candidate accounting mismatch");
        check(cancelled);var group=IfCellReview.create(entry.roi(),cells,cancelled,p->{});
        group.setName("IF historical editable copy — "+directory.getFileName()+" — not ground truth");
        var pending=new ArrayDeque<PathObject>(group.getChildObjects());
        while(!pending.isEmpty()){
            var object=pending.removeFirst();pending.addAll(object.getChildObjects());
            check(cancelled);var reason=reasons.get(object.getMetadata().get("IF source cell ID"));
            if(reason!=null){object.getMetadata().put("IF historical exclusion stage",reason.stage());object.getMetadata().put("IF historical exclusion reason",reason.reason());}
        }
        var lineage=new LinkedHashMap<String,Object>();
        lineage.put("schemaVersion",1);lineage.put("sourceRun",directory.toAbsolutePath().normalize().toString());
        lineage.put("sourceRunCreatedAt",entry.createdAt());lineage.put("sourceFilesSha256",before);
        lineage.put("originalProfile",entry.profile());lineage.put("originalProposedCells",entry.proposedCells());
        lineage.put("originalMeasuredCells",entry.measuredCells());lineage.put("editableCandidates",cells.size());
        lineage.put("geometrylessCandidateIds",List.copyOf(geometryless));
        lineage.put("limitation","Locator/metadata match, not original-pixel authentication. Geometryless records cannot be edited and are not transferred as negative cells. Original exclusions are historical reasons, not current eligibility. Copy creation is not human validation.");
        group.getMetadata().put(LINEAGE,GsonTools.getInstance().toJson(lineage));
        if(!before.equals(hashes(directory,cancelled)))throw new IOException("Historical files changed during review preparation");
        IfHistorySource.validate(directory,source,cancelled);
        check(cancelled);return new Prepared(entry,group,cells.size(),geometryless.size());
    }
    private static Map<String,String> hashes(Path directory,BooleanSupplier cancelled)throws IOException {
        var values=new LinkedHashMap<String,String>();
        for(String name:List.of("manifest.json","cells.csv","cells.geojson","excluded-cells.csv","excluded-cells.geojson")){
            check(cancelled);Path file=directory.resolve(name);
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>32L*1024*1024)throw new IOException("Missing/oversized historical file: "+name);
            try{
                var digest=java.security.MessageDigest.getInstance("SHA-256");long bytes=0;byte[] buffer=new byte[65536];
                try(var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){int n;while((n=in.read(buffer))!=-1){check(cancelled);bytes+=n;if(bytes>32L*1024*1024)throw new IOException("Historical file grew beyond limit");digest.update(buffer,0,n);}}
                values.put(name,HexFormat.of().formatHex(digest.digest()));
            }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        }
        return Map.copyOf(values);
    }
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();}
}
