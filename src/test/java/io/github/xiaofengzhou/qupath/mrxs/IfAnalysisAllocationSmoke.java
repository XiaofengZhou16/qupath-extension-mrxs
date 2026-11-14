package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.*;
import qupath.lib.io.GsonTools;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/** Real-fixture allocation measurement; not an accuracy or full-slide benchmark. */
public class IfAnalysisAllocationSmoke {
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args[1]);Files.createDirectory(output);
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        if(!bean.isThreadAllocatedMemorySupported())throw new IllegalStateException("Allocation counters unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        try(var server=new MrxsImageServer(Path.of(args[0]).toUri())) {
            var profile=new IfAnalysisProfile(1,0,java.util.stream.IntStream.range(0,server.nChannels())
                    .mapToObj(c->new IfAnalysisProfile.Marker(c,server.getChannel(c).getName(),30,30,30)).toList());
            var roi=ROIs.createRectangleROI(32768,81920,4096,4096,ImagePlane.getDefaultPlane());
            var cells=List.of(new IfAnalysis.Cell("fixture",
                    ROIs.createRectangleROI(33000,82200,100,100,ImagePlane.getDefaultPlane()),
                    ROIs.createRectangleROI(33020,82220,20,20,ImagePlane.getDefaultPlane())));
            long thread=Thread.currentThread().threadId(),before=bean.getThreadAllocatedBytes(thread),start=System.nanoTime();
            var result=IfAnalysis.analyze(server,roi,profile,cells,()->false,p->{});
            long allocated=bean.getThreadAllocatedBytes(thread)-before;double seconds=(System.nanoTime()-start)/1e9;
            Files.writeString(output.resolve("results.json"),GsonTools.getInstance(true).toJson(Map.of(
                    "cells",result.cells(),"combinations",result.combinations(),"rejected",result.rejectedCells(),
                    "roiPixels",result.roiPixels(),"tilesRead",result.tilesRead())));
            Files.writeString(output.resolve("allocation.json"),GsonTools.getInstance(true).toJson(Map.of(
                    "callingThreadAllocatedBytes",allocated,"seconds",seconds,
                    "meaning","Cumulative allocation on calling Java thread; not retained heap, total-process allocation or peak memory")));
            System.out.println("IF_ALLOCATION_OK bytes="+allocated+" seconds="+seconds);
        }
    }
}
