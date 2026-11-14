package io.github.xiaofengzhou.qupath.mrxs;

import qupath.lib.io.GsonTools;
import qupath.lib.roi.interfaces.ROI;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/** Background-only AI batch adapter. Owns its reader; never borrows the active viewer's server. */
final class IfBatchAnalysis implements IfBatchQueue.Work, AutoCloseable {
    record Request(String id,Path source,ROI roi,IfAnalysisProfile profile,List<String> channels,
                   IfInstanSeg.Config ai,boolean masks,IfDisplayProfile display) {
        Request {
            if(source==null||roi==null||!roi.isArea()||roi.isEmpty()||profile==null||ai==null)
                throw new IllegalArgumentException("Batch requires an explicit area ROI and complete settings");
            source=source.toAbsolutePath().normalize();channels=List.copyOf(channels);
        }
    }
    record Prepared(Request request,String roiJson,IfSourceFingerprint.Snapshot sourceStamp,
                    Map<String,String> modelHashes,IfBatchQueue.Item item) {}
    private final Map<String,Prepared> jobs=new LinkedHashMap<>();
    private final ClassLoader loader;
    private final BooleanSupplier cancelled;
    private final DoubleConsumer progress;
    private MrxsImageServer server;
    private Path openSource;
    private final double preparationSeconds;
    private final Map<String,String> runtimeSettings=IfInstanSeg.runtimeSettings();
    private IfBatchTelemetry telemetry;
    private java.util.function.Consumer<String> status=s->{};
    void statusListener(java.util.function.Consumer<String> listener){status=java.util.Objects.requireNonNull(listener);}

    IfBatchAnalysis(List<Request> requests,ClassLoader loader,BooleanSupplier cancelled,DoubleConsumer progress)throws IOException {
        long preparationStart=System.nanoTime();
        this.loader=loader;this.cancelled=cancelled;this.progress=progress;
        var software=new TreeMap<>(IfSoftwareProvenance.snapshot());
        if(!"available".equals(software.get("artifactSha256Status")))
            throw new IOException("Batch requires an identifiable built extension JAR");
        for(var request:requests) {
            if(jobs.containsKey(request.id))throw new IllegalArgumentException("Duplicate batch task ID");
            String roi=IfGeometryExport.roi(request.roi).toString();
            var stamp=IfSourceFingerprint.capture(List.of(request.source.toUri().toString()),cancelled);
            var model=modelHashes(request.ai);
            var identity=new TreeMap<String,Object>();
            identity.put("schema",1);identity.put("source",stamp);identity.put("roi",roi);
            identity.put("profile",request.profile);identity.put("channels",request.channels);
            identity.put("model",model);identity.put("device",request.ai.device());
            identity.put("threads",request.ai.threads());identity.put("tileSize",request.ai.tileSize());
            identity.put("padding",request.ai.padding());identity.put("inputs",request.ai.inputChannels());
            identity.put("runtimeSettings",runtimeSettings);identity.put("masks",request.masks);identity.put("display",request.display);identity.put("software",software);
            var item=new IfBatchQueue.Item(request.id,IfBatchOutput.hashText(GsonTools.getInstance().toJson(identity)));
            jobs.put(request.id,new Prepared(request,roi,stamp,model,item));
        }
        if(jobs.isEmpty())throw new IllegalArgumentException("Select at least one ROI");
        preparationSeconds=(System.nanoTime()-preparationStart)/1e9;
    }
    List<IfBatchQueue.Item> items(){return jobs.values().stream().map(Prepared::item).toList();}
    IfBatchQueue.Journal run(Path root)throws IOException {
        Files.createDirectories(root);
        var entries=jobs.values().stream().map(j->IfBatchPlan.entry(j.request,j.item)).toList();
        new IfBatchPlan(1,entries).writeOrCheck(root.resolve("batch-plan.json"));
        telemetry=new IfBatchTelemetry(root);
        telemetry.record("prepareInputs","","complete",preparationSeconds,Map.of("tasks",jobs.size()));
        long start=System.nanoTime();
        try {
            var result=IfBatchQueue.run(root,items(),this,cancelled);
            telemetry.record("queue","",cancelled.getAsBoolean()?"stopped":"returned",(System.nanoTime()-start)/1e9,
                    Map.of("attempts",result.attempts().size(),"note","Returned does not imply every task succeeded; inspect queue.json"));
            return result;
        }catch(IOException|RuntimeException e){
            try{telemetry.record("queue","","failed",(System.nanoTime()-start)/1e9,Map.of("error",e.toString()));}
            catch(IOException logError){e.addSuppressed(logError);}throw e;
        }
    }
    /** Caller must explicitly trust the selected model before invoking; a saved path is not approval. */
    static IfBatchAnalysis reopen(Path root,ClassLoader loader,BooleanSupplier cancelled,DoubleConsumer progress)throws IOException {
        var plan=IfBatchPlan.read(root.resolve("batch-plan.json"));
        var work=new IfBatchAnalysis(plan.requests(),loader,cancelled,progress);
        if(!plan.items().equals(work.items()))throw new IOException("Saved batch source, model, settings or plugin changed; use a new batch");
        return work;
    }
    private Prepared job(IfBatchQueue.Item item)throws IOException {
        var job=jobs.get(item.id());if(job==null||!job.item.equals(item))throw new IOException("Unknown batch task identity");return job;
    }
    private Map<String,String> modelHashes(IfInstanSeg.Config config)throws IOException {
        var result=new TreeMap<String,String>();
        for(var name:List.of("instanseg.pt","rdf.yaml"))result.put(name,IfBatchOutput.hash(config.modelDirectory().resolve(name),cancelled));
        return result;
    }
    private void unchanged(Prepared job)throws IOException {
        if(!runtimeSettings.equals(IfInstanSeg.runtimeSettings()))throw new IOException("InstanSeg runtime settings changed since batch preparation");
        if(!job.sourceStamp.equals(IfSourceFingerprint.capture(List.of(job.request.source.toUri().toString()),cancelled))
                ||!job.modelHashes.equals(modelHashes(job.request.ai)))throw new IOException("Source or model changed since batch preparation");
    }
    @Override public void execute(IfBatchQueue.Item item,Path output,BooleanSupplier stop)throws Exception {
        status.accept("分析 ROI: "+item.id());
        var job=job(item);var r=job.request;
        measured("validateInputsBefore",item.id(),()->{unchanged(job);return null;});
        if(!r.source.equals(openSource)) {
            close();server=measured("openReader",item.id(),()->new MrxsImageServer(r.source.toUri()));openSource=r.source;
        }
        if(!r.channels.equals(server.getMetadata().getChannels().stream().map(c->c.getName()).toList()))
            throw new IOException("Batch channel names/order differ from source");
        var roi=IfGeometryExport.read(com.google.gson.JsonParser.parseString(job.roiJson).getAsJsonObject(),
                GsonTools.getInstance().fromJson(job.roiJson,ROI.class));
        if(roi.getBoundsX()<0||roi.getBoundsY()<0||roi.getBoundsX()+roi.getBoundsWidth()>server.getWidth()
                ||roi.getBoundsY()+roi.getBoundsHeight()>server.getHeight()||roi.getZ()!=0||roi.getT()!=0)
            throw new IOException("Batch ROI outside supported source plane or image bounds");
        if(r.profile.resolutionLevel()>=server.nResolutions()||r.profile.markers().stream().anyMatch(m->m.channel()>=server.nChannels()))
            throw new IOException("Batch analysis settings outside source metadata");
        var segmentation=measured("inference",item.id(),()->IfInstanSeg.detect(server,roi,r.ai,loader,stop,progress));
        var provenance=new LinkedHashMap<String,Object>(segmentation.provenance());
        provenance.put("batchTaskId",item.id());provenance.put("batchTaskFingerprint",item.fingerprint());
        measured("measureAndExport",item.id(),()->IfAnalysisExport.analyzeAndWrite(output,server,roi,r.profile,segmentation.cells(),provenance,r.masks,r.display,stop,progress));
        measured("validateInputsAfter",item.id(),()->{unchanged(job);return null;});
        measured("sealOutput",item.id(),()->{IfBatchOutput.seal(output,item,stop);return null;});
        if(telemetry!=null)telemetry.record("readerSample",item.id(),"observed",0,Map.of("readerCacheBytes",server.cacheStats().cachedBytes(),"readerOpenFiles",server.openDataFileCount()));
    }
    @Override public void verify(IfBatchQueue.Item item,Path output)throws Exception {
        status.accept("校验已完成 ROI: "+item.id());
        measured("verifyCompleted",item.id(),()->{unchanged(job(item));IfBatchOutput.verify(output,item,cancelled);return null;});
    }
    private <T>T measured(String phase,String task,IfBatchTelemetry.Operation<T> operation)throws Exception {
        return telemetry==null?operation.run():telemetry.measure(phase,task,operation);
    }
    @Override public void close()throws IOException {
        var previous=server;server=null;openSource=null;
        if(previous!=null) {
            long start=System.nanoTime();previous.close();
            if(telemetry!=null)telemetry.record("closeReader","","complete",(System.nanoTime()-start)/1e9,
                    Map.of("readerOpenFiles",previous.openDataFileCount(),"readerCacheBytes",previous.cacheStats().cachedBytes(),
                    "scope","Reader-owned files/cache only, not model/GPU allocator release"));
        }
    }
}
