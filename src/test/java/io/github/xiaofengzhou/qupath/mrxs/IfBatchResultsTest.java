package io.github.xiaofengzhou.qupath.mrxs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.io.IOException;
import qupath.lib.io.GsonTools;
import qupath.lib.roi.ROIs;
import qupath.lib.regions.ImagePlane;
import static org.junit.jupiter.api.Assertions.*;
class IfBatchResultsTest {
 @TempDir Path root;
 IfBatchPlan prepare()throws Exception {
  var entries=new ArrayList<IfBatchPlan.Entry>();
  for(int i=0;i<3;i++) {
   var r=new IfBatchAnalysis.Request("roi"+i,root.resolve("source.mrxs"),ROIs.createRectangleROI(0,0,32,8,ImagePlane.getDefaultPlane()),
     new IfAnalysisProfile(1,0,List.of(new IfAnalysisProfile.Marker(0,"A",30,30,30))),List.of("A"),
     new IfInstanSeg.Config(root.resolve("model"),"cpu",1,512,32,List.of(0)),false,null);
   entries.add(IfBatchPlan.entry(r,new IfBatchQueue.Item(r.id(),"fingerprint-"+i)));
  }
  var plan=new IfBatchPlan(1,entries);plan.writeOrCheck(root.resolve("batch-plan.json"));return plan;
 }
 String complete(IfBatchQueue.Item item)throws Exception {
  String dir="attempt-"+UUID.randomUUID();
  try(var server=new IfAnalysisTest.TruthServer(4)) {
   var p=new IfAnalysisProfile(1,0,List.of(new IfAnalysisProfile.Marker(0,"A",30,30,30)));
   IfAnalysisExport.analyzeAndWrite(root.resolve(dir),server,ROIs.createRectangleROI(0,0,32,8,ImagePlane.getDefaultPlane()),p,List.of(),Map.of("method","test"),false,()->false,v->{});
  }
  IfBatchOutput.seal(root.resolve(dir),item,()->false);return dir;
 }
 void journal(IfBatchPlan p,List<IfBatchQueue.Attempt> a)throws Exception {
  Files.writeString(root.resolve("queue.json"),GsonTools.getInstance().toJson(new IfBatchQueue.Journal(1,p.items(),a)));
 }
 @Test void partialBatchIncludesOnlyVerifiedLatestAndAccountsForPending()throws Exception {
  var p=prepare();var dir=complete(p.items().getFirst());
  journal(p,List.of(new IfBatchQueue.Attempt("roi0","attempt-"+UUID.randomUUID(),"cancelled","test"),
    new IfBatchQueue.Attempt("roi0",dir,"complete",""),new IfBatchQueue.Attempt("roi1","attempt-"+UUID.randomUUID(),"failed","source error")));
  byte[] original=Files.readAllBytes(root.resolve("queue.json"));var output=root.resolve("report");
  var report=IfBatchResults.export(root,output,new IfOfficeReport.Formats(true,false),()->false);
  assertEquals(3,report.planned());assertEquals(1,report.included());
  assertEquals(List.of("complete","failed","pending"),report.tasks().stream().map(IfBatchResults.Row::status).toList());
  assertTrue(Files.isRegularFile(output.resolve("office/results.xlsx")));assertFalse(Files.exists(output.resolve("office/summary.docx")));
  assertArrayEquals(original,Files.readAllBytes(root.resolve("queue.json")));assertFalse(Files.exists(output.resolve("INCOMPLETE")));
 }
 @Test void corruptLatestResultExcludedAndNoStaleFallback()throws Exception {
  var p=prepare();String old=complete(p.items().getFirst()),latest=complete(p.items().getFirst());
  Files.writeString(root.resolve(latest).resolve("cells.csv"),"corrupt");
  journal(p,List.of(new IfBatchQueue.Attempt("roi0",old,"complete",""),new IfBatchQueue.Attempt("roi0",latest,"complete","")));
  var output=root.resolve("report");var report=IfBatchResults.export(root,output,new IfOfficeReport.Formats(true,false),()->false);
  assertEquals(0,report.included());assertEquals("invalid",report.tasks().getFirst().status());
  assertFalse(Files.exists(output.resolve("office")));assertTrue(Files.exists(output.resolve("batch-status.json")));
 }
 @Test void rejectsActiveWriterAndCancelledExportWithoutSuccess()throws Exception {
  var p=prepare();journal(p,List.of());
  try(var channel=java.nio.channels.FileChannel.open(root.resolve("queue.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()) {
   assertThrows(IOException.class,()->IfBatchResults.export(root,root.resolve("locked"),new IfOfficeReport.Formats(true,false),()->false));
  }
  assertThrows(java.util.concurrent.CancellationException.class,()->IfBatchResults.export(root,root.resolve("cancel"),new IfOfficeReport.Formats(true,false),()->true));
  assertFalse(Files.exists(root.resolve("cancel/manifest.json")));
 }
}
