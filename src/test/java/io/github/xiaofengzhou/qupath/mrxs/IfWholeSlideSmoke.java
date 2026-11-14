package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.*;
import qupath.lib.io.GsonTools;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;

/** Entire slide extent, bounded overview-level validation; never a native full-slide benchmark. */
public class IfWholeSlideSmoke {
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args[1]);Files.createDirectory(output);
        try(var server=new MrxsImageServer(Path.of(args[0]).toUri())) {
            int level=0;
            while(level<server.nResolutions()-1 && (server.getDownsampleForResolution(level)<32
                    || Math.ceil(server.getWidth()/server.getDownsampleForResolution(level))*
                    Math.ceil(server.getHeight()/server.getDownsampleForResolution(level))>4_194_304))level++;
            double ds=server.getDownsampleForResolution(level);
            if(Math.ceil(server.getWidth()/ds)*Math.ceil(server.getHeight()/ds)>4_194_304)
                throw new IllegalStateException("No bounded overview level for independent reference read");
            var markers=java.util.stream.IntStream.range(0,server.nChannels()).mapToObj(c->
                    new IfAnalysisProfile.Marker(c,server.getChannel(c).getName(),30,30,30)).toList();
            var profile=new IfAnalysisProfile(1,level,markers);
            var roi=ROIs.createRectangleROI(0,0,server.getWidth(),server.getHeight(),ImagePlane.getDefaultPlane());
            long start=System.nanoTime();
            var full=IfAnalysis.analyze(server,roi,profile,List.of(),()->false,p->{});
            long nx=(long)Math.ceil(server.getWidth()/ds-0.5),ny=(long)Math.ceil(server.getHeight()/ds-0.5);
            if(full.roiPixels()!=nx*ny)throw new IllegalStateException("Full extent coverage mismatch");
            double split=Math.floor(server.getWidth()/ds/2)*ds;
            var left=IfAnalysis.analyze(server,ROIs.createRectangleROI(0,0,split,server.getHeight(),ImagePlane.getDefaultPlane()),profile,List.of(),()->false,p->{});
            var right=IfAnalysis.analyze(server,ROIs.createRectangleROI(split,0,server.getWidth()-split,server.getHeight(),ImagePlane.getDefaultPlane()),profile,List.of(),()->false,p->{});
            if(left.roiPixels()+right.roiPixels()!=full.roiPixels())throw new IllegalStateException("Partition coverage mismatch");
            var raw=server.readRegion(RegionRequest.createInstance(server.getPath(),ds,roi));
            long[] histogram=new long[1<<markers.size()];long checked=0;
            for(int y=0;y<raw.getHeight();y++)for(int x=0;x<raw.getWidth();x++) {
                if((x+0.5)*ds>=server.getWidth() || (y+0.5)*ds>=server.getHeight())continue;
                int bits=0;for(int c=0;c<markers.size();c++)if(raw.getRaster().getSampleDouble(x,y,c)>=30)bits|=1<<c;
                histogram[bits]++;checked++;
            }
            if(checked!=full.roiPixels())throw new IllegalStateException("Reference read coverage mismatch");
            var inclusive=IfAnalysis.inclusive(histogram);
            for(int i=0;i<full.combinations().size();i++) {
                var f=full.combinations().get(i);var l=left.combinations().get(i);var r=right.combinations().get(i);
                if(f.paPixels()!=l.paPixels()+r.paPixels() || f.paExactPixels()!=l.paExactPixels()+r.paExactPixels()
                        || f.paPixels()!=inclusive[f.bits()] || f.paExactPixels()!=histogram[f.bits()])
                    throw new IllegalStateException("Whole slide combination mismatch");
            }
            var report=new LinkedHashMap<String,Object>();
            report.put("sourceWidth",server.getWidth());report.put("sourceHeight",server.getHeight());
            report.put("resolutionLevel",level);report.put("downsample",ds);report.put("sampledPixels",full.roiPixels());
            report.put("wholeExtentNativeTiles",full.tilesRead());report.put("fullAnalysisSeconds",full.elapsedSeconds());
            report.put("totalValidationSeconds",(System.nanoTime()-start)/1e9);
            report.put("partitionCountsMatch",true);report.put("independentRasterEnumerationMatches",true);
            report.put("limitations","Whole slide extent at overview resolution, no segmentation or tissue mask. Thresholds demonstration-only; includes blank slide area. Not original-resolution full-slide performance or accuracy validation.");
            Files.writeString(output.resolve("verification.json"),GsonTools.getInstance(true).toJson(report));
            Files.writeString(output.resolve("combinations.json"),GsonTools.getInstance(true).toJson(full.combinations()));
            System.out.println("IF_WHOLE_EXTENT_OK "+GsonTools.getInstance().toJson(report));
        }
    }
}
