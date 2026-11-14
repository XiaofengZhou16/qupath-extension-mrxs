package io.github.xiaofengzhou.qupath.mrxs;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import qupath.lib.io.GsonTools;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/** Opt-in shared-server workload test. Source rendering only, never screen capture. */
public class IfConcurrentReadSmoke {
    public static void main(String[] args)throws Exception {
        if(args.length!=2 && args.length!=3 && args.length!=5)throw new IllegalArgumentException("four-channel-slide new-output-directory [trusted-model-folder [native-x native-y]]");
        Path output=Path.of(args[1]);Files.createDirectory(output);
        try(var server=new MrxsImageServer(Path.of(args[0]).toUri())) {
            var markers=new ArrayList<IfAnalysisProfile.Marker>();
            var channels=new ArrayList<IfDisplayProfile.Channel>();
            for(int c=0;c<server.nChannels();c++) {
                var channel=server.getChannel(c);
                markers.add(new IfAnalysisProfile.Marker(c,channel.getName(),30,30,30));
                channels.add(new IfDisplayProfile.Channel(c,channel.getName(),channel.getColor(),0,255,true));
            }
            var analysisProfile=new IfAnalysisProfile(1,0,markers);
            var displayProfile=new IfDisplayProfile(1,1,false,false,channels);
            boolean ai=args.length>=3;
            int originX=args.length==5?Integer.parseInt(args[3]):32768;
            int originY=args.length==5?Integer.parseInt(args[4]):81920;
            int analysisSize=ai?1024:8192;
            if(originX<0 || originY<0 || (long)originX+analysisSize>server.getWidth()
                    || (long)originY+analysisSize>server.getHeight())throw new IllegalArgumentException("ROI outside slide");
            var aiConfig=ai?new IfInstanSeg.Config(Path.of(args[2]),"mps",1,512,32,List.of(0,1,2)):null;
            var roi=ROIs.createRectangleROI(originX,originY,analysisSize,analysisSize,ImagePlane.getDefaultPlane());
            var exportROI=ROIs.createRectangleROI(originX,originY,1024,1024,ImagePlane.getDefaultPlane());
            var cells=new ArrayList<IfAnalysis.Cell>();
            for(int y=0;y<32;y++)for(int x=0;x<32;x++) {
                int px=originX+x*256,py=originY+y*256;
                cells.add(new IfAnalysis.Cell(x+"-"+y,
                        ROIs.createRectangleROI(px,py,32,32,ImagePlane.getDefaultPlane()),
                        ROIs.createRectangleROI(px+8,py+8,16,16,ImagePlane.getDefaultPlane())));
            }
            var views=new ArrayList<IfHiDpiRenderer.View>();
            for(int i=0;i<6;i++)views.add(new IfHiDpiRenderer.View(593,689,2,2,
                    originX+732+i*128,originY+780+i*96,i%2==0?2:4,i*0.03,0,0,0,true));
            var options=new IfPublicationExport.Options(0,300,8,true,true,50);
            long start=System.nanoTime();
            List<IfAnalysis.Cell> baselineCells=cells;
            if(ai) {
                var segmentation=IfInstanSeg.detect(server,roi,aiConfig,IfConcurrentReadSmoke.class.getClassLoader(),()->false,p->{});
                baselineCells=canonical(segmentation.cells());
                Files.writeString(output.resolve("serial-ai-provenance.json"),GsonTools.getInstance(true).toJson(segmentation.provenance()));
                if(baselineCells.isEmpty())throw new IllegalStateException("AI fixture detected no cells");
            }
            var baseline=IfAnalysis.analyze(server,roi,analysisProfile,baselineCells,()->false,p->{});
            var expectedGeometry=baselineCells.stream().map(IfConcurrentReadSmoke::geometryKey).toList();
            var referenceFrames=new ArrayList<String>();
            for(var view:views)referenceFrames.add(digest(IfHiDpiRenderer.render(server,displayProfile,view,()->false).image()));
            IfPublicationExport.export(server,exportROI,displayProfile,options,output.resolve("serial-export"),()->false,p->{});
            double serialSeconds=(System.nanoTime()-start)/1e9;
            var gate=new CountDownLatch(1);
            var active=new java.util.concurrent.atomic.AtomicInteger();
            var maxActive=new java.util.concurrent.atomic.AtomicInteger();
            var timings=new ConcurrentHashMap<String,Double>();
            start=System.nanoTime();
            try(var pool=Executors.newFixedThreadPool(3)) {
                var analysis=pool.submit(()->run(gate,active,maxActive,timings,"analysis",()->{
                    List<IfAnalysis.Cell> measuredCells=cells;
                    if(ai) {
                        var segmentation=IfInstanSeg.detect(server,roi,aiConfig,IfConcurrentReadSmoke.class.getClassLoader(),()->false,p->{});
                        measuredCells=canonical(segmentation.cells());
                        Files.writeString(output.resolve("concurrent-ai-provenance.json"),GsonTools.getInstance(true).toJson(segmentation.provenance()));
                        if(!expectedGeometry.equals(measuredCells.stream().map(IfConcurrentReadSmoke::geometryKey).toList()))
                            throw new IllegalStateException("AI compartment geometry differs under concurrent load");
                    }
                    return IfAnalysis.analyze(server,roi,analysisProfile,measuredCells,()->false,p->{});
                }));
                var frames=pool.submit(()->run(gate,active,maxActive,timings,"frames",()->{
                    var digests=new ArrayList<String>();
                    try(var cache=new IfHiDpiRenderer.TileCache(64L*1024*1024)) {
                        for(var view:views)digests.add(digest(IfHiDpiRenderer.render(server,displayProfile,view,()->false,cache).image()));
                    }
                    return digests;
                }));
                var exported=pool.submit(()->run(gate,active,maxActive,timings,"publication",()->
                        IfPublicationExport.export(server,exportROI,displayProfile,options,output.resolve("concurrent-export"),()->false,p->{})));
                gate.countDown();
                var actual=analysis.get(90,TimeUnit.SECONDS);
                if(!baseline.cells().equals(actual.cells()) || !baseline.combinations().equals(actual.combinations())
                        || !baseline.rejectedCells().equals(actual.rejectedCells()) || baseline.roiPixels()!=actual.roiPixels()
                        || baseline.tilesRead()!=actual.tilesRead())throw new IllegalStateException("Concurrent analysis differs");
                if(!referenceFrames.equals(frames.get(90,TimeUnit.SECONDS)))throw new IllegalStateException("Concurrent frame pixels differ");
                exported.get(90,TimeUnit.SECONDS);
            }
            double concurrentSeconds=(System.nanoTime()-start)/1e9;
            int pngs=0;
            try(var files=Files.walk(output.resolve("serial-export"))) {
                for(var file:files.filter(p->p.toString().endsWith(".png")).toList()) {
                    var target=output.resolve("concurrent-export").resolve(output.resolve("serial-export").relativize(file));
                    if(Files.mismatch(file,target)!=-1)throw new IllegalStateException("Export PNG differs: "+file);
                    pngs++;
                }
            }
            if(maxActive.get()!=3 || pngs!=30)throw new IllegalStateException("Expected three overlapping workloads and 30 PNGs");
            var report=new LinkedHashMap<String,Object>();
            report.put("roiPixels",baseline.roiPixels());report.put("nativeTiles",baseline.tilesRead());
            report.put("aiInference",ai);report.put("inputCells",baselineCells.size());report.put("eligibleCells",baseline.cells().size());
            report.put("overlappingWorkloads",maxActive.get());report.put("matchingDeviceFrames",views.size());
            report.put("byteIdenticalPublicationPNGs",pngs);report.put("serialSeconds",serialSeconds);
            report.put("concurrentSeconds",concurrentSeconds);report.put("workloadSeconds",timings);
            report.put("analysisResultsIdentical",true);report.put("screenshots",false);
            report.put("limitations","One shared MRXS server, "+(ai?"AI proposals (not reference contours)":"synthetic cell rectangles")+", demonstration thresholds; no UI responsiveness, whole-slide or biological accuracy validation. Serial-first and shared caches preclude causal speedup claims.");
            Files.writeString(output.resolve("verification.json"),GsonTools.getInstance(true).toJson(report));
            System.out.println("IF_CONCURRENT_READ_OK "+GsonTools.getInstance().toJson(report));
        }
    }
    // Inference assigns fresh UUIDs; normalize test IDs by exact geometry, never by proximity.
    private static List<IfAnalysis.Cell> canonical(List<IfAnalysis.Cell> cells) {
        var sorted=cells.stream().sorted(Comparator.comparing(IfConcurrentReadSmoke::geometryKey)).toList();
        var normalized=new ArrayList<IfAnalysis.Cell>();
        for(int i=0;i<sorted.size();i++)normalized.add(new IfAnalysis.Cell("geometry-"+i,sorted.get(i).cell(),sorted.get(i).nucleus()));
        return normalized;
    }
    private static String geometryKey(IfAnalysis.Cell cell) {
        return (cell.cell()==null?"missing":cell.cell().getImagePlane()+":"+cell.cell().getGeometry().toText())+"|"
                +(cell.nucleus()==null?"missing":cell.nucleus().getImagePlane()+":"+cell.nucleus().getGeometry().toText());
    }
    private static <T>T run(CountDownLatch gate,java.util.concurrent.atomic.AtomicInteger active,
            java.util.concurrent.atomic.AtomicInteger max,Map<String,Double> timings,String name,Callable<T> task)throws Exception {
        gate.await();int n=active.incrementAndGet();max.accumulateAndGet(n,Math::max);long start=System.nanoTime();
        try{return task.call();}finally{timings.put(name,(System.nanoTime()-start)/1e9);active.decrementAndGet();}
    }
    private static String digest(BufferedImage image)throws Exception {
        var hash=MessageDigest.getInstance("SHA-256");byte[] row=new byte[image.getWidth()*4];
        for(int y=0;y<image.getHeight();y++) {
            for(int x=0;x<image.getWidth();x++){int rgb=image.getRGB(x,y),i=x*4;row[i]=(byte)(rgb>>>24);row[i+1]=(byte)(rgb>>>16);row[i+2]=(byte)(rgb>>>8);row[i+3]=(byte)rgb;}
            hash.update(row);
        }
        return HexFormat.of().formatHex(hash.digest());
    }
}
