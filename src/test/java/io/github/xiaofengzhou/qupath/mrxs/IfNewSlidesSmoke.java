package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.*;
import qupath.lib.io.GsonTools;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;

/** Opt-in private-slide survey. Bounded native sampling, not biological validation. */
public class IfNewSlidesSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("new-output-directory slide...");
        Path root = Path.of(args[0]);
        Files.createDirectory(root);
        var summaries = new ArrayList<Map<String, Object>>();
        int failures = 0;
        for (int i = 1; i < args.length; i++) {
            Path out = root.resolve(String.format(Locale.ROOT, "%02d", i));
            Files.createDirectory(out);
            var report = new LinkedHashMap<String, Object>();
            report.put("source", Path.of(args[i]).toAbsolutePath().toString());
            try (var server = new MrxsImageServer(Path.of(args[i]).toUri())) {
                report.put("width", server.getWidth());
                report.put("height", server.getHeight());
                report.put("channelQuality", server.channelQualityReport());
                int level = 0;
                while (level < server.nResolutions() - 1 &&
                        Math.ceil(server.getWidth()/server.getDownsampleForResolution(level)) *
                        Math.ceil(server.getHeight()/server.getDownsampleForResolution(level)) > 1_048_576) level++;
                double ds = server.getDownsampleForResolution(level);
                if (Math.ceil(server.getWidth()/ds)*Math.ceil(server.getHeight()/ds)>1_048_576)
                    throw new IllegalStateException("No bounded overview");
                var whole = ROIs.createRectangleROI(0, 0, server.getWidth(), server.getHeight(), ImagePlane.getDefaultPlane());
                var overview = server.readRegion(RegionRequest.createInstance(server.getPath(), ds, whole));
                int selected = 0;
                for (int c = 0; c < server.nChannels(); c++)
                    if (server.getChannel(c).getName().equalsIgnoreCase("CY5")) selected = c;
                int bx = 0, by = 0;
                double peak = -1;
                for (int y = 0; y < overview.getHeight(); y++) for (int x = 0; x < overview.getWidth(); x++) {
                    double value = overview.getRaster().getSampleDouble(x, y, selected);
                    if (value > peak) { peak = value; bx = x; by = y; }
                }
                int size = Math.min(512, Math.min(server.getWidth(), server.getHeight()));
                int rx = Math.max(0, Math.min(server.getWidth()-size, (int)((bx+0.5)*ds)-size/2));
                int ry = Math.max(0, Math.min(server.getHeight()-size, (int)((by+0.5)*ds)-size/2));
                // Overview placeholders can have identical nonzero values in every channel.
                // Choose among up to 64 evenly spaced index-backed native tiles instead.
                var stored = new ArrayList<qupath.lib.images.servers.TileRequest>();
                for(var tile:server.getTileRequestManager().getTileRequests(
                        RegionRequest.createInstance(server.getPath(),1,whole)))
                    if(server.hasStoredImage(tile))stored.add(tile);
                int probes=Math.min(64,stored.size());
                long bestScore=-1;
                for(int p=0;p<probes;p++) {
                    var tile=stored.get(probes==1?0:(int)((long)p*(stored.size()-1)/(probes-1)));
                    int tx=Math.max(0,Math.min(server.getWidth()-size,tile.getImageX()));
                    int ty=Math.max(0,Math.min(server.getHeight()-size,tile.getImageY()));
                    var sample=server.readRegion(RegionRequest.createInstance(server.getPath(),1,tx,ty,size,size));
                    long score=0;
                    for(int c=0;c<server.nChannels();c++) {
                        int low=255,high=0;
                        for(int y=0;y<size;y+=4)for(int x=0;x<size;x+=4) {
                            int v=sample.getRaster().getSample(x,y,c);low=Math.min(low,v);high=Math.max(high,v);
                        }
                        score+=high-low;
                    }
                    if(score>bestScore){bestScore=score;rx=tx;ry=ty;}
                }
                report.put("nativeStoredTiles",stored.size());report.put("nativeProbedTiles",probes);
                report.put("nativeSelectionDynamicRangeScore",bestScore);
                if(bestScore<=0)throw new IllegalStateException("No varying native ROI found in bounded survey");
                var roi = ROIs.createRectangleROI(rx, ry, size, size, ImagePlane.getDefaultPlane());
                var request = RegionRequest.createInstance(server.getPath(), 1, roi);
                var raw = server.readRegion(request);
                var repeat = server.readRegion(request);
                var channels = new ArrayList<IfDisplayProfile.Channel>();
                var markers = new ArrayList<IfAnalysisProfile.Marker>();
                int[] maxima = new int[server.nChannels()];
                long[] nonzero = new long[server.nChannels()];
                for (int c=0;c<server.nChannels();c++) {
                    var channel=server.getChannel(c);
                    channels.add(new IfDisplayProfile.Channel(c,channel.getName(),channel.getColor(),0,255,true));
                    markers.add(new IfAnalysisProfile.Marker(c,channel.getName(),30,30,30));
                    for(int y=0;y<raw.getHeight();y++)for(int x=0;x<raw.getWidth();x++) {
                        int v=raw.getRaster().getSample(x,y,c);
                        if(v!=repeat.getRaster().getSample(x,y,c))throw new IllegalStateException("Repeat read mismatch");
                        maxima[c]=Math.max(maxima[c],v);if(v!=0)nonzero[c]++;
                    }
                }
                var measured=IfAnalysis.analyze(server,roi,new IfAnalysisProfile(1,0,markers),List.of(),()->false,p->{});
                if(measured.roiPixels()!=(long)size*size)throw new IllegalStateException("Native ROI coverage mismatch");
                IfPublicationExport.export(server,roi,new IfDisplayProfile(1,1,false,false,channels),
                        new IfPublicationExport.Options(0,300,8,true,true,50),out.resolve("publication"),()->false,p->{});
                long pngCount;
                try(var files=Files.list(out.resolve("publication"))) {
                    pngCount=files.filter(p->p.toString().endsWith(".png")).count();
                }
                if(pngCount!=2*((1<<server.nChannels())-1))throw new IllegalStateException("Combination export count mismatch");
                report.put("overviewSurveyChannel",server.getChannel(selected).getName());
                report.put("roiSelection","Highest sum of per-channel ranges among at most 64 index-backed native tiles; stride-4 sample");
                report.put("selectionOverviewLevel",level);report.put("selectionOverviewPeak",peak);
                report.put("nativeROI",List.of(rx,ry,size,size));
                report.put("nativeMaxima",maxima);report.put("nativeNonZeroPixels",nonzero);
                report.put("publicationPNGCount",pngCount);report.put("repeatNativeReadMatches",true);
                IfWholeSlideSmoke.main(new String[]{args[i],out.resolve("whole-extent").toString()});
                report.put("passed",true);
            } catch(Exception e) {
                failures++;report.put("passed",false);report.put("error",e.toString());
            }
            report.put("limitations","Bounded native-tile selection is biased and not representative. Nonzero CY5 pixels do not prove specific staining. Overview nonzero values may include placeholders. No biological reference, AI accuracy or vendor-matched clarity comparison. No screenshots.");
            Files.writeString(out.resolve("verification.json"),GsonTools.getInstance(true).toJson(report));
            summaries.add(report);
            System.out.println("NEW_SLIDE_CHECK "+GsonTools.getInstance().toJson(report));
        }
        Files.writeString(root.resolve("summary.json"),GsonTools.getInstance(true).toJson(summaries));
        if(failures>0)throw new IllegalStateException(failures+" slide checks failed; see summary.json");
    }
}
