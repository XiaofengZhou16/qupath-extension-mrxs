package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.*;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.io.GsonTools;

/** Same-run source figure smoke, not a screenshot or biological validation. */
public class IfReportFigureSmoke {
    public static void main(String[] args) throws Exception {
        try(var server=new MrxsImageServer(Path.of(args[0]).toUri())) {
            var channels=new ArrayList<IfDisplayProfile.Channel>();var markers=new ArrayList<IfAnalysisProfile.Marker>();
            for(int c=0;c<server.nChannels();c++) {
                var ch=server.getChannel(c);channels.add(new IfDisplayProfile.Channel(c,ch.getName(),ch.getColor(),0,255,true));
                markers.add(new IfAnalysisProfile.Marker(c,ch.getName(),30,30,30));
            }
            Path out=Path.of(args[1]);var roi=ROIs.createRectangleROI(32768,81920,4096,4096,ImagePlane.getDefaultPlane());
            IfAnalysisExport.analyzeAndWrite(out,server,roi,new IfAnalysisProfile(1,2,markers),List.of(),
                    Map.of("method","PA only; demonstration thresholds"),false,new IfDisplayProfile(1,1,false,false,channels),()->false,v->{});
            var root=com.google.gson.JsonParser.parseString(Files.readString(out.resolve("manifest.json"))).getAsJsonObject();
            var ref=root.getAsJsonObject("source").getAsJsonObject("reportFigure");
            if(!ref.get("status").getAsString().equals("exported"))throw new AssertionError(ref);
            Path manifest=out.resolve(ref.get("manifest").getAsString());
            String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(manifest)));
            if(!hash.equals(ref.get("manifestSha256").getAsString()))throw new AssertionError("Figure manifest hash mismatch");
            var figure=com.google.gson.JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
            if((long)figure.get("rasterWidth").getAsInt()*figure.get("rasterHeight").getAsInt()>1_048_576)throw new AssertionError("Unbounded preview");
            var figureRoi=GsonTools.getInstance().fromJson(Files.readString(out.resolve("report-figure/roi.json")),qupath.lib.roi.interfaces.ROI.class);
            if(!roi.getGeometry().equalsExact(figureRoi.getGeometry())||figure.getAsJsonArray("outputs").size()!=1)throw new AssertionError("Wrong ROI or composites");
            if(Files.exists(out.resolve("INCOMPLETE")))throw new AssertionError("Incomplete analysis");
            System.out.println("IF_REPORT_FIGURE_OK oneComposite=true boundedPixels=true sameROI=true manifestHash=true sourceLevel="+ref.get("resolutionLevel"));
        }
    }
}
