package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.Path;
import java.util.List;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/** Explicit opt-in local inference smoke. Not run by normal unit tests; no network or model assumption in CI. */
public class IfInstanSegSmoke {
    public static void main(String[] args) throws Exception {
        if(args.length != 4 && args.length != 5) throw new IllegalArgumentException("slide.mrxs model-folder cpu|mps output-folder [cancel|ROI-size]");
        int roiSize=args.length==5 && !args[4].equals("cancel")?Integer.parseInt(args[4]):1024;
        if(roiSize<512 || roiSize>8192)throw new IllegalArgumentException("Smoke ROI must be 512..8192 pixels");
        try(var server=new MrxsImageServer(Path.of(args[0]).toUri())) {
            if(args.length==5 && args[4].equals("cancel")) {
                var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
                boolean rejected=false;
                try {
                    IfInstanSeg.detect(server,ROIs.createRectangleROI(32768,81920,2048,2048,ImagePlane.getDefaultPlane()),
                            new IfInstanSeg.Config(Path.of(args[1]),args[2],2,512,32,List.of(0,1,2)),
                            IfInstanSegSmoke.class.getClassLoader(),cancelled::get,p->{if(p>0)cancelled.set(true);});
                } catch(java.util.concurrent.CancellationException expected) {rejected=true;}
                if(!cancelled.get() || !rejected)throw new IllegalStateException("Real inference cancellation not observed");
                if(Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.isAlive()&&t.getName().equals("if-inference-tile")))
                    throw new IllegalStateException("Inference threads survived cancellation");
                if(java.nio.file.Files.exists(Path.of(args[3])))throw new IllegalStateException("Cancelled inference created output");
                System.out.println("INSTANSEG_CANCEL_OK partialResultRejected=true inferenceThreadsExited=true outputAbsent=true");
            }
            var roi=ROIs.createRectangleROI(32768,81920,roiSize,roiSize,ImagePlane.getDefaultPlane());
            var segmentation=IfInstanSeg.detect(server,roi,new IfInstanSeg.Config(Path.of(args[1]),args[2],2,512,32,
                    List.of(0,1,2)),IfInstanSegSmoke.class.getClassLoader(),()->false,p->{});
            if(segmentation.cells().isEmpty()) throw new IllegalStateException("No cells in smoke-test tissue ROI");
            var markers=java.util.stream.IntStream.range(1,server.nChannels()).mapToObj(c->new IfAnalysisProfile.Marker(
                    c,server.getMetadata().getChannels().get(c).getName(),30,30,30)).toList();
            var result=IfAnalysis.analyze(server,roi,new IfAnalysisProfile(1,0,markers),segmentation.cells(),()->false,p->{});
            IfAnalysisExport.write(Path.of(args[3]),server.getMetadata().getName(),roi,result,segmentation.cells(),segmentation.provenance(),()->false);
            System.out.println("INSTANSEG_SMOKE_OK detected="+segmentation.cells().size()+" eligible="+result.cells().size()
                    +" roiSize="+roiSize+" device="+args[2]+" inferenceSeconds="+segmentation.provenance().get("elapsedSeconds")+" analysisSeconds="+result.elapsedSeconds());
        }
    }
}
