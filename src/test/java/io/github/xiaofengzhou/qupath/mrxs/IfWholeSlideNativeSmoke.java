package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.*;
import qupath.lib.io.GsonTools;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/** Explicit opt-in native whole-extent PA benchmark, without masks or AI. */
public class IfWholeSlideNativeSmoke {
    public static void main(String[] args)throws Exception {
        if(args.length!=2 && !(args.length==3 && args[2].equals("masks")))throw new IllegalArgumentException("slide new-output-directory [masks]");
        boolean withMasks=args.length==3;
        Path output=Path.of(args[1]);Files.createDirectory(output);
        Files.writeString(output.resolve("INCOMPLETE"),"Benchmark incomplete; no usable result until manifest and marker removal.\n");
        try(var server=new MrxsImageServer(Path.of(args[0]).toUri())) {
            var sourceUris=server.getURIs().stream().map(Object::toString).sorted().toList();
            var sourceFingerprint=IfSourceFingerprint.capture(sourceUris,()->false);
            var markers=java.util.stream.IntStream.range(0,server.nChannels()).mapToObj(c->
                    new IfAnalysisProfile.Marker(c,server.getChannel(c).getName(),30,30,30)).toList();
            var profile=new IfAnalysisProfile(1,0,markers);
            var roi=ROIs.createRectangleROI(0,0,server.getWidth(),server.getHeight(),ImagePlane.getDefaultPlane());
            long start=System.nanoTime();long[] last={start};
            java.util.function.BooleanSupplier cancelled=()->(System.nanoTime()-start)/1e9>1800;
            IfAnalysis.Result result;Map<String,Object> maskSummary=Map.of("exported",false);
            try(var writer=withMasks?new IfAreaMasks(output.resolve("area-masks"),markers.size(),cancelled):null) {
            result=IfAnalysis.analyze(server,roi,profile,List.of(),cancelled,p->{
                long now=System.nanoTime();if(now-last[0]>java.util.concurrent.TimeUnit.SECONDS.toNanos(20) || p==1) {
                    System.out.printf(Locale.ROOT,"NATIVE_PA_PROGRESS fraction=%.5f elapsedSeconds=%.1f%n",p,(now-start)/1e9);last[0]=now;
                }
            },writer);
            if(writer!=null)maskSummary=writer.finish(result,0,0);
            }
            if(result.roiPixels()!=(long)server.getWidth()*server.getHeight())throw new IllegalStateException("Native extent coverage mismatch");
            long positiveExact=result.combinations().stream().mapToLong(IfAnalysis.Combination::paExactPixels).sum();
            if(positiveExact>result.roiPixels())throw new IllegalStateException("Exact positivity counts exceed ROI");
            var report=new LinkedHashMap<String,Object>();
            if(!Objects.equals(sourceFingerprint,IfSourceFingerprint.capture(sourceUris,()->false)))
                throw new IllegalStateException("Source changed during whole-extent benchmark");
            report.put("software",IfSoftwareProvenance.snapshot());
            report.put("sourceUris",sourceUris);report.put("sourceFileFingerprint",sourceFingerprint);
            report.put("areaMasks",maskSummary);
            report.put("sourceWidth",server.getWidth());report.put("sourceHeight",server.getHeight());
            report.put("nativePixels",result.roiPixels());report.put("tilesRead",result.tilesRead());
            report.put("indexCertifiedZeroTilesCountedWithoutRead",result.zeroTilesCounted());
            report.put("elapsedSeconds",result.elapsedSeconds());report.put("downsample",result.downsample());
            report.put("allSelectedMarkersNegativePixels",result.roiPixels()-positiveExact);
            report.put("heapCapBytes",Runtime.getRuntime().maxMemory());
            report.put("limitations","Native full bounding extent PA, demonstration thresholds; includes blank area. Optional masks are benchmark artifacts, not a standard history run. No segmentation, biological accuracy, independent native raster reference or GUI responsiveness claim.");
            Files.writeString(output.resolve("combinations.json"),GsonTools.getInstance(true).toJson(result.combinations()));
            Files.writeString(output.resolve("profile.json"),GsonTools.getInstance(true).toJson(profile));
            Files.writeString(output.resolve("manifest.json"),GsonTools.getInstance(true).toJson(report));
            Files.delete(output.resolve("INCOMPLETE"));
            System.out.println("IF_NATIVE_WHOLE_PA_OK "+GsonTools.getInstance().toJson(report));
        }
    }
}
