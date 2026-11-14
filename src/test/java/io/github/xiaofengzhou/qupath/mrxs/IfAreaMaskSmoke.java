package io.github.xiaofengzhou.qupath.mrxs;

import qupath.lib.io.GsonTools;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Opt-in four-channel fixture smoke; no biological ground truth or screen capture. */
public class IfAreaMaskSmoke {
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("four-channel-test-slide.mrxs new-output-folder");
        Path output=Path.of(args[1]);Files.createDirectory(output);
        try(var server=new MrxsImageServer(Path.of(args[0]).toUri())) {
            var markers=java.util.stream.IntStream.range(0,server.nChannels()).mapToObj(c->
                    new IfAnalysisProfile.Marker(c,server.getChannel(c).getName(),30,30,30)).toList();
            var profile=new IfAnalysisProfile(1,0,markers);var measurements=new ArrayList<Object>();
            for(int size:List.of(1024,4096)) {
                var roi=ROIs.createRectangleROI(32768,81920,size,size,ImagePlane.getDefaultPlane());
                Path directory=output.resolve("roi-"+size);long start=System.nanoTime();
                var result=IfAnalysisExport.analyzeAndWrite(directory,server,roi,profile,List.of(),
                        Map.of("method","PA only; no segmentation; thresholds are demonstration values"),true,()->false,v->{});
                double seconds=(System.nanoTime()-start)/1e9;
                var index=IfAreaMaskOverlay.readIndex(directory,result,0,0,()->false);
                long[] histogram=new long[1<<markers.size()];long encodedPixels=0;int tiles=0;
                var reference=size==1024 ? server.readRegion(RegionRequest.createInstance(server.getPath(),1,roi)):null;
                long checkedSourcePixels=0;
                try(var lines=Files.newBufferedReader(directory.resolve("area-masks/tiles.jsonl"))) {
                    for(String line;(line=lines.readLine())!=null;) {
                        var tile=GsonTools.getInstance().fromJson(line,IfAreaMasks.Tile.class);tiles++;
                        Path file=directory.resolve("area-masks").resolve(tile.file());
                        if(!tile.sha256().equals(IfAreaMasks.digest(file)))throw new IllegalStateException("Tile checksum mismatch");
                        var mask=ImageIO.read(file.toFile());
                        for(int y=0;y<mask.getHeight();y++)for(int x=0;x<mask.getWidth();x++) {
                            int code=mask.getRaster().getSample(x,y,0);
                            if(code>0){encodedPixels++;histogram[code-1]++;}
                            if(reference!=null) {
                                int rx=tile.levelX()+x-32768,ry=tile.levelY()+y-81920,expected=0;
                                if(rx>=0&&ry>=0&&rx<reference.getWidth()&&ry<reference.getHeight()) {
                                    int bits=0;for(int c=0;c<markers.size();c++)if(reference.getRaster().getSampleDouble(rx,ry,c)>=30)bits|=1<<c;
                                    expected=bits+1;checkedSourcePixels++;
                                }
                                if(code!=expected)throw new IllegalStateException("Mask differs from independent raw ROI read");
                            }
                        }
                    }
                }
                if(encodedPixels!=result.roiPixels()||tiles!=result.tilesRead()+result.zeroTilesCounted())throw new IllegalStateException("Coverage mismatch");
                var inclusive=IfAnalysis.inclusive(histogram);
                for(var c:result.combinations())if(histogram[c.bits()]!=c.paExactPixels()||inclusive[c.bits()]!=c.paPixels())
                    throw new IllegalStateException("Decoded PNG counts differ from statistics");
                long disk;try(var files=Files.walk(directory)){disk=files.filter(Files::isRegularFile).mapToLong(p->{try{return Files.size(p);}catch(Exception e){throw new RuntimeException(e);}}).sum();}
                var run=new LinkedHashMap<String,Object>();run.put("size",size);run.put("roiPixels",result.roiPixels());run.put("tiles",tiles);
                run.put("analysisAndExportSeconds",seconds);run.put("outputBytes",disk);run.put("decodedCountsMatchStatistics",true);
                run.put("independentlyCheckedSourcePixels",checkedSourcePixels);measurements.add(run);
                System.out.println("IF_PA_MASK_SMOKE_OK "+GsonTools.getInstance().toJson(run));
            }
            Files.writeString(output.resolve("verification.json"),GsonTools.getInstance(true).toJson(Map.of(
                    "runs",measurements,"thresholds","30 in every raw channel, demonstration only; not calibrated staining positivity",
                    "limitations","No ground-truth segmentation, no whole-slide benchmark, no causally attributed speed comparison; source reread is validation-only",
                    "screenshots",false)));
        }
    }
}
