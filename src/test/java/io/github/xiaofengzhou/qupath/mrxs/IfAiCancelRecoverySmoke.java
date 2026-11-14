package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
import qupath.lib.io.GsonTools;
import qupath.lib.roi.ROIs;
import qupath.lib.regions.ImagePlane;

/** Real inference cancellation between tile completions, followed by same-reader recovery. */
public class IfAiCancelRecoverySmoke {
 public static void main(String[] args) throws Exception {
  Path output=Path.of(args[2]);Files.createDirectory(output);
  var cancelled=new AtomicBoolean();var requested=new AtomicLong();var callbacks=new AtomicInteger();
  var server=new MrxsImageServer(Path.of(args[0]).toUri());
  var report=new LinkedHashMap<String,Object>();
  try {
   var roi=ROIs.createRectangleROI(32768,81920,2048,2048,ImagePlane.getDefaultPlane());
   var config=new IfInstanSeg.Config(Path.of(args[1]),"mps",1,512,32,List.of(0,1,2));
   boolean observed=false;
   try {
    IfInstanSeg.detect(server,roi,config,IfAiCancelRecoverySmoke.class.getClassLoader(),cancelled::get,p->{
     callbacks.incrementAndGet();if(p>0&&p<1&&cancelled.compareAndSet(false,true))requested.set(System.nanoTime());
    });
   } catch(CancellationException expected){observed=true;}
   if(!observed||requested.get()==0)throw new AssertionError("Cancellation did not occur during tile sequence");
   report.put("cancelReturnMillis",(System.nanoTime()-requested.get())/1e6);
   report.put("completedTileProgressCallbacks",callbacks.get());
   report.put("cancelledOutcomeObserved",true);
   cancelled.set(false);
   long start=System.nanoTime();
   var recovered=IfInstanSeg.detect(server,roi,config,IfAiCancelRecoverySmoke.class.getClassLoader(),cancelled::get,p->{});
   if(recovered.cells().isEmpty())throw new AssertionError("Recovery produced no cells");
   report.put("recoverySeconds",(System.nanoTime()-start)/1e9);
   report.put("recoverySegmentation",recovered.provenance());
   report.put("software",IfSoftwareProvenance.snapshot());
  } finally {server.close();}
  if(server.openDataFileCount()!=0||server.cacheStats().cachedBytes()!=0)throw new AssertionError("Reader cleanup failed");
  report.put("readerFilesAfterClose",server.openDataFileCount());report.put("readerCacheBytesAfterClose",server.cacheStats().cachedBytes());
  report.put("limitations","One MPS run; cancellation requested after a completed tile with work remaining, not during a stuck native call or via GUI. Same reader successfully reused. No bounded worst-case cancellation or native/GPU allocator-release claim.");
  Files.writeString(output.resolve("verification.json"),GsonTools.getInstance(true).toJson(report));
  System.out.println("IF_AI_CANCEL_RECOVERY_OK");
 }
}
