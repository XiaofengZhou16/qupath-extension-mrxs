package io.github.xiaofengzhou.qupath.mrxs;

import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import qupath.lib.io.GsonTools;
import qupath.lib.regions.RegionRequest;

/** Opt-in same-raster comparison, not a GUI or end-to-end export benchmark. */
public class IfRenderPreparationBenchmark {
    private static volatile long sink;
    record Trial(String mode,double seconds,Long currentThreadAllocatedBytes,long checksum) {}
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("slide.mrxs new-result.json");
        Path output=Path.of(args[1]);
        if(Files.exists(output))throw new IllegalArgumentException("Output already exists");
        var bean=ManagementFactory.getThreadMXBean();
        var allocation=bean instanceof com.sun.management.ThreadMXBean extended
                && extended.isThreadAllocatedMemorySupported()?extended:null;
        if(allocation!=null&&!allocation.isThreadAllocatedMemoryEnabled())allocation.setThreadAllocatedMemoryEnabled(true);
        try(var server=new MrxsImageServer(Path.of(args[0]).toUri())) {
            if(server.nChannels()!=4 || server.getWidth()<33792 || server.getHeight()<82944)
                throw new IllegalArgumentException("This fixture benchmark expects a four-channel slide covering the fixed ROI");
            var raw=server.readRegion(RegionRequest.createInstance(server.getPath(),1,32768,81920,1024,1024));
            var channels=new ArrayList<IfDisplayProfile.Channel>();
            for(int i=0;i<server.nChannels();i++)channels.add(new IfDisplayProfile.Channel(i,
                    server.getChannel(i).getName(),server.getChannel(i).getColor(),0,255,true));
            var profile=new IfDisplayProfile(1,1.4,false,false,channels);
            var combinations=new ArrayList<List<Integer>>();
            for(int mask=1;mask<16;mask++) {
                var selected=new ArrayList<Integer>();
                for(int c=0;c<4;c++)if((mask&(1<<c))!=0)selected.add(c);
                combinations.add(List.copyOf(selected));
            }
            var modes=List.of("fresh","prepared","striped");
            // Warm all paths, then rotate and reverse measured order to reduce order bias.
            for(int warm=0;warm<2;warm++)for(String mode:modes)
                trial(server,raw,profile,combinations,mode,allocation);
            var trials=new ArrayList<Trial>();
            for(int pair=0;pair<6;pair++) {
                Long checksum=null;
                for(int j=0;j<3;j++) {
                    String mode=modes.get(Math.floorMod(pair+(pair<3?j:-j),3));
                    var result=trial(server,raw,profile,combinations,mode,allocation);
                    if(checksum!=null && checksum.longValue()!=result.checksum())throw new AssertionError("Sampled render checksum differs");
                    checksum=result.checksum();trials.add(result);
                }
            }
            var report=new LinkedHashMap<String,Object>();
            report.put("source",Path.of(args[0]).toAbsolutePath().toString());
            report.put("roi",List.of(32768,81920,1024,1024));report.put("profile",profile);
            report.put("combinationsPerTrial",15);report.put("trials",trials);
            for(String mode:modes) {
                double[] times=trials.stream().filter(t->t.mode().equals(mode)).mapToDouble(Trial::seconds).sorted().toArray();
                report.put(mode+"MedianSeconds",(times[2]+times[3])/2);
            }
            report.put("java",System.getProperty("java.version"));
            report.put("limitations",List.of("Single fixed ROI and JVM; order alternates but thermal, GC and JIT effects remain",
                    "Timing includes renderer preparation and 15 transforms, excludes source reads, PNG compression, annotation and UI",
                    "Allocation counts cover the calling Java thread only, not native memory, worker allocations or peak live memory",
                    "Sampled checksums consume results but are not full pixel equivalence; separate tests establish bounded pixel equivalence",
                    "No screenshots, biological validation or end-to-end speedup claim"));
            Files.writeString(output,GsonTools.getInstance(true).toJson(report),java.nio.file.StandardOpenOption.CREATE_NEW);
            System.out.println(GsonTools.getInstance(true).toJson(report));
        }
    }
    private static Trial trial(MrxsImageServer server,BufferedImage raw,IfDisplayProfile profile,
                               List<List<Integer>> combinations,String mode,
                               com.sun.management.ThreadMXBean allocation) {
        long id=Thread.currentThread().threadId();
        long before=allocation==null?0:allocation.getThreadAllocatedBytes(id);
        long start=System.nanoTime(),checksum=0;
        boolean prepared=!mode.equals("fresh");
        var renderer=prepared?IfDisplaySettings.prepare(server,profile):null;
        BufferedImage reusable=null;
        for(var selected:combinations) {
            var image=mode.equals("striped")?renderer.renderCancellable(raw,selected,reusable,()->false)
                    :prepared?renderer.renderInto(raw,selected,reusable):IfPreparedRendererTest.freshReference(server,raw,profile,selected);
            if(prepared)reusable=image;
            for(int y=0;y<image.getHeight();y+=31)for(int x=0;x<image.getWidth();x+=31)
                checksum=31*checksum+image.getRGB(x,y);
        }
        double seconds=(System.nanoTime()-start)/1e9;
        Long bytes=allocation==null?null:allocation.getThreadAllocatedBytes(id)-before;
        sink=checksum;
        return new Trial(mode,seconds,bytes,checksum);
    }
}
