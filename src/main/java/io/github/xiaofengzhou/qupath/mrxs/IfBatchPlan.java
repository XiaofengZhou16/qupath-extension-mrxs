package io.github.xiaofengzhou.qupath.mrxs;

import com.google.gson.JsonObject;
import qupath.lib.io.GsonTools;
import qupath.lib.roi.interfaces.ROI;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/** Data-only frozen task list. Reading never loads a model or opens a slide. */
record IfBatchPlan(int schema, List<Entry> tasks) {
    record Entry(IfBatchQueue.Item item,String sourceUri,JsonObject roi,IfAnalysisProfile profile,
                 List<String> channels,String modelUri,String device,int threads,int tileSize,int padding,
                 List<Integer> inputs,boolean masks,IfDisplayProfile display) {
        Entry {
            Objects.requireNonNull(item);Objects.requireNonNull(roi);Objects.requireNonNull(profile);
            local(sourceUri);local(modelUri);channels=List.copyOf(channels);inputs=List.copyOf(inputs);roi=roi.deepCopy();
            if(channels.isEmpty()||channels.size()>64||channels.stream().anyMatch(c->c==null||c.isBlank()))
                throw new IllegalArgumentException("Invalid batch source channels");
            new IfInstanSeg.Config(local(modelUri),device,threads,tileSize,padding,inputs);
        }
        IfBatchAnalysis.Request request() throws IOException {
            try {
                var geometry=IfGeometryExport.read(roi,GsonTools.getInstance().fromJson(roi,ROI.class));
                return new IfBatchAnalysis.Request(item.id(),local(sourceUri),geometry,profile,channels,
                        new IfInstanSeg.Config(local(modelUri),device,threads,tileSize,padding,inputs),masks,display);
            } catch(RuntimeException e){throw new IOException("Invalid saved batch ROI/settings",e);}
        }
    }
    IfBatchPlan {
        if(schema!=1||tasks==null||tasks.isEmpty()||tasks.size()>10000)throw new IllegalArgumentException("Invalid batch task list");
        tasks=List.copyOf(tasks);
        if(tasks.stream().map(e->e.item.id()).distinct().count()!=tasks.size())throw new IllegalArgumentException("Duplicate task IDs");
    }
    static Entry entry(IfBatchAnalysis.Request r,IfBatchQueue.Item item) {
        var ai=r.ai();return new Entry(item,r.source().toUri().toString(),IfGeometryExport.roi(r.roi()),r.profile(),r.channels(),
                ai.modelDirectory().toUri().toString(),ai.device(),ai.threads(),ai.tileSize(),ai.padding(),ai.inputChannels(),r.masks(),r.display());
    }
    List<IfBatchAnalysis.Request> requests()throws IOException {
        var result=new ArrayList<IfBatchAnalysis.Request>();for(var task:tasks)result.add(task.request());return List.copyOf(result);
    }
    List<IfBatchQueue.Item> items(){return tasks.stream().map(Entry::item).toList();}
    void writeOrCheck(Path file)throws IOException {
        String json=GsonTools.getInstance(true).toJson(this);
        try {Files.writeString(file,json,StandardOpenOption.CREATE_NEW);}
        catch(FileAlreadyExistsException e) {
            var old=read(file);
            if(!GsonTools.getInstance().toJsonTree(this).equals(GsonTools.getInstance().toJsonTree(old)))
                throw new IOException("Saved batch plan differs; choose a new batch directory");
        }
    }
    static IfBatchPlan read(Path file)throws IOException {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Batch plan must be a regular local file");
        try(var in=Files.newInputStream(file)) {
            byte[] bytes=in.readNBytes(16*1024*1024+1);
            if(bytes.length>16*1024*1024)throw new IOException("Batch plan exceeds 16 MiB");
            var plan=GsonTools.getInstance().fromJson(new String(bytes,java.nio.charset.StandardCharsets.UTF_8),IfBatchPlan.class);
            if(plan==null)throw new IOException("Empty batch plan");
            plan.requests();return plan;
        }catch(RuntimeException e){throw new IOException("Invalid batch plan",e);}
    }
    private static Path local(String value) {
        URI uri=URI.create(value);
        if(!"file".equalsIgnoreCase(uri.getScheme()))throw new IllegalArgumentException("Batch only supports local file URIs");
        return Path.of(uri).toAbsolutePath().normalize();
    }
}
