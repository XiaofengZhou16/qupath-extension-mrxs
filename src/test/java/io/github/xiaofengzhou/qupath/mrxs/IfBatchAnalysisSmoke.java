package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.*;
import qupath.lib.io.GsonTools;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/** Three real ROI jobs across two sources; resume must not create another attempt. */
public class IfBatchAnalysisSmoke {
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args[3]);Files.createDirectory(output);
        var config=new IfInstanSeg.Config(Path.of(args[2]),"mps",Integer.getInteger("if.batch.workers",1),512,32,List.of(0,1,2));
        var requests=new ArrayList<IfBatchAnalysis.Request>();
        for(int source=0;source<2;source++) {
            Path slide=Path.of(args[source]);List<String> channels;
            try(var server=new MrxsImageServer(slide.toUri())){channels=server.getMetadata().getChannels().stream().map(c->c.getName()).toList();}
            var profile=new IfAnalysisProfile(1,0,List.of(new IfAnalysisProfile.Marker(1,channels.get(1),30,30,30)));
            for(int roi=0;roi<(source==0?2:1);roi++) {
                int x=source==0?32768+roi*4096:50176, y=source==0?81920:145920;
                requests.add(new IfBatchAnalysis.Request("slide"+source+"-roi"+roi,slide,
                        ROIs.createRectangleROI(x,y,2048,2048,ImagePlane.getDefaultPlane()),profile,channels,config,false,null));
            }
        }
        int cycles=Integer.getInteger("if.batch.cycles",1);
        if(cycles<1||cycles>50)throw new IllegalArgumentException("Cycles must be 1..50");
        if(cycles>1) {
            var original=List.copyOf(requests);requests.clear();
            for(int cycle=0;cycle<cycles;cycle++)for(var r:original)
                requests.add(new IfBatchAnalysis.Request("cycle"+cycle+"-"+r.id(),r.source(),r.roi(),r.profile(),r.channels(),r.ai(),r.masks(),r.display()));
        }
        int expectedTasks=requests.size();
        long start=System.nanoTime();
        IfBatchQueue.Journal first;
        double preparationSeconds,runSeconds;
        try(var work=new IfBatchAnalysis(requests,IfBatchAnalysisSmoke.class.getClassLoader(),()->false,p->{})) {
            preparationSeconds=(System.nanoTime()-start)/1e9;long runStart=System.nanoTime();
            first=work.run(output.resolve("batch"));
            runSeconds=(System.nanoTime()-runStart)/1e9;
        }
        if(first.attempts().size()!=expectedTasks||first.attempts().stream().anyMatch(a->!a.status().equals("complete")))
            throw new AssertionError(GsonTools.getInstance().toJson(first));
        String previousPredictors=System.getProperty("instanseg.numPredictors");
        try {
            System.setProperty("instanseg.numPredictors","3".equals(previousPredictors)?"2":"3");
            try(var unexpected=IfBatchAnalysis.reopen(output.resolve("batch"),IfBatchAnalysisSmoke.class.getClassLoader(),()->false,p->{})) {
                throw new AssertionError("Changed runtime configuration incorrectly accepted");
            }catch(java.io.IOException expected) {
                if(!expected.getMessage().contains("Saved batch source, model, settings or plugin changed"))throw expected;
            }
        }finally{if(previousPredictors==null)System.clearProperty("instanseg.numPredictors");else System.setProperty("instanseg.numPredictors",previousPredictors);}
        var before=new TreeMap<String,String>();
        try(var files=Files.walk(output.resolve("batch"))){for(var p:files.filter(Files::isRegularFile).toList())
            if(!p.getFileName().toString().equals("queue.lock")&&!p.getFileName().toString().equals("queue.json"))
                before.put(p.toString(),IfBatchOutput.hash(p,()->false));}
        start=System.nanoTime();
        try(var work=IfBatchAnalysis.reopen(output.resolve("batch"),IfBatchAnalysisSmoke.class.getClassLoader(),()->false,p->{})) {
            var resumed=work.run(output.resolve("batch"));
            if(!resumed.equals(first))throw new AssertionError("Resume changed completed attempts");
        }
        double resumeSeconds=(System.nanoTime()-start)/1e9;
        for(var e:before.entrySet())if(!e.getValue().equals(IfBatchOutput.hash(Path.of(e.getKey()),()->false)))throw new AssertionError("Resume changed output");
        var report=new LinkedHashMap<String,Object>();
        report.put("software",IfSoftwareProvenance.snapshot());report.put("inferenceWorkers",config.threads());report.put("completedTasks",expectedTasks);report.put("repeatCycles",cycles);report.put("sourceSlides",2);
        report.put("roiPixelsEach",2048*2048);report.put("preparationSeconds",preparationSeconds);report.put("runSeconds",runSeconds);
        report.put("resumeIncludingPreparationSeconds",resumeSeconds);report.put("runtimeSettingChangeRejected",true);report.put("resumeAddedAttempts",0);report.put("allOutputHashesUnchanged",true);
        report.put("limitations","Repeated engineering ROIs when cycles > 1; not 30 distinct slides, representative 150 ROI workload or biological accuracy. Headless batch adapter, not project GUI. No process RSS or queue latency measured here. Source fingerprints sample bytes; not full source hashes.");
        Files.writeString(output.resolve("verification.json"),GsonTools.getInstance(true).toJson(report));
        System.out.println("IF_BATCH_ANALYSIS_OK");
    }
}
