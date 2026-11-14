package io.github.xiaofengzhou.qupath.mrxs;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import qupath.lib.io.GsonTools;

/** Per-session append-only phase observations; heap samples are not peak RSS or native GPU usage. */
final class IfBatchTelemetry {
    interface Operation<T>{T run()throws Exception;}
    private final Path path;
    IfBatchTelemetry(Path root)throws IOException {
        path=root.resolve("batch-session-"+UUID.randomUUID()+".jsonl");Files.createFile(path);
        record("session","","started",0,Map.of("software",IfSoftwareProvenance.snapshot(),
                "memoryScope","JVM heap sampled at phase boundaries; not peak RSS or GPU memory",
                "timingScope","Elapsed phase wall time; includes any internal initialization and cache effects; no controlled cold-cache claim"));
    }
    <T>T measure(String phase,String task,Operation<T> action)throws Exception {
        long start=System.nanoTime();T value;
        try{value=action.run();}
        catch(Exception e){
            try{record(phase,task,e instanceof java.util.concurrent.CancellationException?"cancelled":"failed",(System.nanoTime()-start)/1e9,Map.of("error",e.toString()));}
            catch(IOException logFailure){e.addSuppressed(logFailure);}throw e;
        }
        record(phase,task,"complete",(System.nanoTime()-start)/1e9,Map.of());return value;
    }
    void record(String phase,String task,String status,double seconds,Map<String,?> extra)throws IOException {
        var event=new LinkedHashMap<String,Object>();event.put("schema",1);event.put("time",Instant.now().toString());
        event.put("phase",phase);event.put("task",task);event.put("status",status);event.put("seconds",seconds);
        var memory=java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        event.put("heapUsedBytes",memory.getUsed());event.put("heapCommittedBytes",memory.getCommitted());
        event.putAll(extra);Files.writeString(path,GsonTools.getInstance().toJson(event)+"\n",StandardOpenOption.APPEND);
    }
}
