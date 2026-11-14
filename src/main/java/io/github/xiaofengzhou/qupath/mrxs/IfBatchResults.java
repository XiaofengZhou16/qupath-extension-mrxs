package io.github.xiaofengzhou.qupath.mrxs;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import qupath.lib.io.GsonTools;

/** Exports one verified latest result per task plus explicit accounting for every planned ROI. */
final class IfBatchResults {
    record Row(String task,String source,String status,String directory,String detail) {}
    record Summary(int planned,int included,List<Row> tasks) {}
    static Summary export(Path root,Path output,IfOfficeReport.Formats formats,BooleanSupplier cancelled)throws IOException {
        if(!Files.isDirectory(root))throw new IOException("Batch directory missing");
        try(var channel=FileChannel.open(root.resolve("queue.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            var lock=channel.tryLock()) {
            if(lock==null)throw new IOException("Batch is still running; wait before exporting");
            var plan=IfBatchPlan.read(root.resolve("batch-plan.json"));
            var journalPath=root.resolve("queue.json");
            if(!Files.isRegularFile(journalPath,LinkOption.NOFOLLOW_LINKS)||Files.size(journalPath)>16*1024*1024)throw new IOException("Missing or oversized queue journal");
            var journal=GsonTools.getInstance().fromJson(Files.readString(journalPath),IfBatchQueue.Journal.class);
            if(journal==null||journal.schema()!=1||!plan.items().equals(journal.items())||journal.attempts()==null)throw new IOException("Queue/plan mismatch");
            var latest=new LinkedHashMap<String,IfBatchQueue.Attempt>();var ids=new HashSet<String>();
            plan.items().forEach(i->ids.add(i.id()));
            for(var a:journal.attempts()) {
                if(a==null||!ids.contains(a.task())||a.directory()==null||!a.directory().matches("attempt-[a-f0-9-]{36}")
                        ||!Set.of("running","interrupted","complete","failed","cancelled","invalid").contains(a.status()))throw new IOException("Invalid attempt record");
                latest.put(a.task(),a);
            }
            var rows=new ArrayList<Row>();var selected=new ArrayList<Path>();
            for(var task:plan.tasks()) {
                check(cancelled);var a=latest.get(task.item().id());
                String status=a==null?"pending":a.status(),directory=a==null?"":a.directory(),detail=a==null?"Not started":a.error();
                if(status.equals("running")){status="interrupted";detail="No active writer; previous task completion was not recorded";}
                if(status.equals("complete")) {
                    try{IfBatchOutput.verify(root.resolve(directory),task.item(),cancelled);selected.add(root.resolve(directory));}
                    catch(IOException|RuntimeException e){if(e instanceof java.util.concurrent.CancellationException)throw e;status="invalid";detail=e.toString();}
                }
                rows.add(new Row(task.item().id(),task.sourceUri(),status,directory,detail));
            }
            var summary=new Summary(plan.tasks().size(),selected.size(),List.copyOf(rows));
            check(cancelled);Files.createDirectory(output);
            Files.writeString(output.resolve("INCOMPLETE"),"Batch report export unfinished",StandardOpenOption.CREATE_NEW);
            Files.writeString(output.resolve("batch-status.json"),GsonTools.getInstance(true).toJson(summary),StandardOpenOption.CREATE_NEW);
            Files.copy(root.resolve("batch-plan.json"),output.resolve("batch-plan.json"));
            Files.copy(journalPath,output.resolve("queue-snapshot.json"));
            Files.writeString(output.resolve("README.txt"),"Included verified ROI results: "+selected.size()+" / "+plan.tasks().size()
                    +"\nSee batch-status.json for every planned ROI, including failed, cancelled, pending and invalid tasks.\n"
                    +"Only the latest completed, integrity-verified attempt per task is included. ROI values are not pooled as biological replicates.\n"
                    +"Report completion does not mean all batch tasks completed, source bytes were fully authenticated, or biological accuracy was validated.\n"
                    +(selected.isEmpty()?"No verified completed results; no Office tables generated.\n":"Office tables are in office/.\n"));
            if(!selected.isEmpty())IfOfficeReport.write(selected,output.resolve("office"),formats,cancelled);
            check(cancelled);
            var hashes=new TreeMap<String,String>();
            try(var files=Files.walk(output)){for(var p:files.filter(Files::isRegularFile).toList()) {
                if(!p.getFileName().toString().equals("INCOMPLETE"))hashes.put(output.relativize(p).toString(),IfBatchOutput.hash(p,cancelled));
            }}
            Files.writeString(output.resolve("manifest.json"),GsonTools.getInstance(true).toJson(Map.of("schema",1,"status","complete",
                    "kind","batch-results-export","planned",summary.planned(),"included",summary.included(),"files",hashes)),StandardOpenOption.CREATE_NEW);
            Files.delete(output.resolve("INCOMPLETE"));return summary;
        }catch(java.nio.channels.OverlappingFileLockException e){throw new IOException("Batch is still running",e);}
        catch(com.google.gson.JsonParseException e){throw new IOException("Invalid queue journal",e);}
    }
    private static void check(BooleanSupplier c){if(c.getAsBoolean())throw new java.util.concurrent.CancellationException();}
}
