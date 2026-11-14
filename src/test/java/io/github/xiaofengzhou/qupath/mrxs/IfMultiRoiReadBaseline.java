package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import qupath.lib.regions.RegionRequest;
import qupath.lib.io.GsonTools;

/** Bounded decoded-cache baseline; never claims an OS-cold cache. */
public final class IfMultiRoiReadBaseline {
    public static void main(String[] args)throws Exception {
        var output=Path.of(args[1]);if(Files.exists(output))throw new IllegalArgumentException("New output required");
        int[][] bounds={{32768,81920,2048,2048},{36864,81920,2048,2048},{32768,79872,2048,2048}};
        var hashes=new HashMap<Integer,String>();var trials=new ArrayList<Map<String,Object>>();
        for(int session=0;session<3;session++) {
            var server=new MrxsImageServer(Path.of(args[0]).toUri());
            try {
                for(int pass=0;pass<3;pass++)for(int step=0;step<bounds.length;step++) {
                    int id=(step+session)%bounds.length;var b=bounds[id];var before=server.cacheStats();
                    long started=System.nanoTime();var raw=server.readRegion(RegionRequest.createInstance(server.getPath(),1,b[0],b[1],b[2],b[3]));
                    double seconds=(System.nanoTime()-started)/1e9;
                    var digest=MessageDigest.getInstance("SHA-256");var raster=raw.getRaster();int[] row=null;
                    for(int y=0;y<raw.getHeight();y++) {
                        row=raster.getPixels(0,y,raw.getWidth(),1,row);
                        for(int value:row){digest.update((byte)(value>>>24));digest.update((byte)(value>>>16));digest.update((byte)(value>>>8));digest.update((byte)value);}
                    }
                    String hash=HexFormat.of().formatHex(digest.digest());var original=hashes.putIfAbsent(id,hash);
                    if(original!=null&&!original.equals(hash))throw new AssertionError("Pixels changed for ROI "+id);
                    trials.add(Map.of("session",session,"pass",pass,"roiIndex",id,"bounds",b,"readSeconds",seconds,"cacheBefore",before,"cacheAfter",server.cacheStats(),"openDataFiles",server.openDataFileCount(),"pixelSha256",hash));
                }
            } finally {server.close();}
            if(server.openDataFileCount()!=0||server.cacheStats().cachedBytes()!=0)throw new AssertionError("Close did not release reader cache/files");
        }
        var report=new LinkedHashMap<String,Object>();report.put("software",IfSoftwareProvenance.snapshot());report.put("trials",trials);
        report.put("source",Path.of(args[0]).toAbsolutePath().toString());report.put("allPixelsStable",true);report.put("threeReaderClosesReleasedCacheAndFiles",true);
        report.put("java",System.getProperty("java.version"));report.put("os",System.getProperty("os.name"));report.put("arch",System.getProperty("os.arch"));report.put("availableProcessors",Runtime.getRuntime().availableProcessors());
        report.put("limitations","Three fixed ROIs and three sequential reader instances; first pass uses empty/new decoded caches but OS cache is uncontrolled. Read timings exclude checksum work. No AI, UI, long-term soak, disk-cold or representative tissue-density claim.");
        Files.writeString(output,GsonTools.getInstance(true).toJson(report),StandardOpenOption.CREATE_NEW);System.out.println("MULTI_ROI_BASELINE_OK reads="+trials.size());
    }
}
