package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.*;
import qupath.lib.io.GsonTools;
import qupath.lib.roi.ROIs;
import qupath.lib.regions.ImagePlane;

/** Fixed-source mask generation benchmark; excludes mask PNG compression and GUI. */
public class IfMaskRasterBenchmark {
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("slide new-result.json");
        Path out=Path.of(args[1]);if(Files.exists(out))throw new IllegalArgumentException("Output exists");
        try(var server=new MrxsImageServer(Path.of(args[0]).toUri())) {
            var profile=new IfAnalysisProfile(1,0,java.util.stream.IntStream.range(0,server.nChannels())
                    .mapToObj(c->new IfAnalysisProfile.Marker(c,server.getChannel(c).getName(),30,30,30)).toList());
            var roi=ROIs.createRectangleROI(32768,81920,4096,4096,ImagePlane.getDefaultPlane());
            var trials=new ArrayList<Map<String,Object>>();
            for(int i=0;i<4;i++) {
                var digest=java.security.MessageDigest.getInstance("SHA-256");long start=System.nanoTime();
                var result=IfAnalysis.analyze(server,roi,profile,List.of(),()->false,p->{},(x,y,image)->{
                    digest.update(java.nio.ByteBuffer.allocate(16).putInt(x).putInt(y).putInt(image.getWidth()).putInt(image.getHeight()).array());
                    // Explicit two-byte values, independent of raster storage implementation.
                    byte[] row=new byte[image.getWidth()*2];
                    for(int ry=0;ry<image.getHeight();ry++) {
                        for(int rx=0;rx<image.getWidth();rx++) {int v=image.getRaster().getSample(rx,ry,0);row[2*rx]=(byte)(v>>>8);row[2*rx+1]=(byte)v;}
                        digest.update(row);
                    }
                });
                var trial=new LinkedHashMap<String,Object>();
                trial.put("seconds",(System.nanoTime()-start)/1e9);trial.put("maskSha256",HexFormat.of().formatHex(digest.digest()));
                trial.put("roiPixels",result.roiPixels());trial.put("combinations",result.combinations());trial.put("tilesRead",result.tilesRead());
                if(i>0)trials.add(trial);
            }
            var report=new LinkedHashMap<String,Object>();report.put("software",IfSoftwareProvenance.snapshot());report.put("source",args[0]);
            report.put("roi",List.of(32768,81920,4096,4096));report.put("trials",trials);
            report.put("limitations","One warm-up then three sequential warm-cache trials; includes source reads, statistics and full mask hashing, excludes file compression/GUI. No whole-slide speedup or accuracy claim.");
            Files.writeString(out,GsonTools.getInstance(true).toJson(report),StandardOpenOption.CREATE_NEW);
            System.out.println("IF_MASK_BENCHMARK_OK "+out);
        }
    }
}
