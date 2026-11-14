package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.*;
import qupath.lib.io.GsonTools;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/** Opt-in one-factor comparison. Never selects the best-looking run or changes defaults. */
public class IfPaddingComparisonSmoke {
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args[2]);Files.createDirectory(output);
        var reports=new ArrayList<Map<String,Object>>();
        try(var server=new MrxsImageServer(Path.of(args[0]).toUri())){
            var roi=ROIs.createRectangleROI(32768,81920,4096,4096,ImagePlane.getDefaultPlane());
            var markers=java.util.stream.IntStream.range(1,server.nChannels()).mapToObj(c->new IfAnalysisProfile.Marker(c,server.getChannel(c).getName(),30,30,30)).toList();
            for(int padding:new int[]{32,80}){
                var segmentation=IfInstanSeg.detect(server,roi,new IfInstanSeg.Config(Path.of(args[1]),"mps",1,512,padding,List.of(0,1,2)),
                        IfPaddingComparisonSmoke.class.getClassLoader(),()->false,p->{});
                var result=IfAnalysisExport.analyzeAndWrite(output.resolve("padding-"+padding),server,roi,new IfAnalysisProfile(1,0,markers),
                        segmentation.cells(),segmentation.provenance(),false,()->false,p->{});
                var reasons=new TreeMap<String,Long>();result.rejectedCells().forEach(c->reasons.merge(c.reason(),1L,Long::sum));
                reports.add(Map.of("padding",padding,"proposed",segmentation.cells().size(),"measured",result.cells().size(),
                        "reasons",reasons,"officialOutputGeometry",segmentation.provenance().get("officialOutputGeometry"),
                        "tiles",segmentation.provenance().get("tilesProcessed"),"inferenceSeconds",segmentation.provenance().get("elapsedSeconds")));
                System.out.println("IF_PADDING_RUN "+GsonTools.getInstance().toJson(reports.getLast()));
            }
        }
        Files.writeString(output.resolve("comparison.json"),GsonTools.getInstance(true).toJson(Map.of("runs",reports,
                "limitations","One ROI; fixed order 32 then 80, cache/order effects confound timing. Only padding changes. Counts and coverage are not biological accuracy; no preferred setting selected, no screenshots or default changes.")));
    }
}
