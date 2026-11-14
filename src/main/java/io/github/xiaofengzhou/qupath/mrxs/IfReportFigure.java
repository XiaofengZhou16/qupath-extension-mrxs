package io.github.xiaofengzhou.qupath.mrxs;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.roi.interfaces.ROI;

/** Same-invocation, bounded source-derived composite; no filename-based pairing or screen capture. */
final class IfReportFigure {
    static Map<String,Object> write(Path analysis,ImageServer<BufferedImage> server,ROI roi,
                                    IfDisplayProfile display,BooleanSupplier cancelled) throws IOException {
        if(display==null)return Map.of("status","not_requested");
        display.validateFor(server);
        if(display.selectedChannels().isEmpty())return Map.of("status","unavailable","reason","No selected display channels");
        IfPublicationExport.Options chosen=null;
        // Never request a huge level-0 preview or upscale a coarse pyramid into fake detail.
        for(int level=0;level<server.nResolutions();level++) {
            double ds=server.getDownsampleForResolution(level);
            double width=Math.ceil((Math.min(server.getWidth(),Math.ceil(roi.getBoundsX()+roi.getBoundsWidth()))-Math.max(0,Math.floor(roi.getBoundsX())))/ds);
            double height=Math.ceil((Math.min(server.getHeight(),Math.ceil(roi.getBoundsY()+roi.getBoundsHeight()))-Math.max(0,Math.floor(roi.getBoundsY())))/ds);
            if(width*height>1_048_576)continue;
            var options=new IfPublicationExport.Options(level,150,8,false,
                    server.getPixelCalibration().hasPixelSizeMicrons(),0);
            try {
                var plan=IfPublicationExport.plan(server,roi,display,options);
                if((long)plan.estimatedWidth()*plan.estimatedHeight()<=1_048_576){chosen=options;break;}
            }catch(IllegalArgumentException ignored){/* Try another native level; no source read yet. */}
            catch(Exception e){throw new IOException("Could not plan report figure",e);}
        }
        if(chosen==null)return Map.of("status","unavailable","reason","No native level fits bounded preview and legible labels");
        try {
            Path manifest=IfPublicationExport.export(server,roi,display,chosen,analysis.resolve("report-figure"),cancelled,v->{});
            var digest=MessageDigest.getInstance("SHA-256");
            String hash=HexFormat.of().formatHex(digest.digest(Files.readAllBytes(manifest)));
            return Map.of("status","exported","manifest","report-figure/manifest.json","manifestSha256",hash,
                    "roiSha256",HexFormat.of().formatHex(digest.digest(Files.readAllBytes(analysis.resolve("report-figure/roi.json")))),
                    "association","Same analysis invocation, ImageServer and ROI; not authenticated slide content",
                    "purpose","Bounded native-level source composite for report context, not a full-resolution publication figure",
                    "resolutionLevel",chosen.level());
        }catch(java.util.concurrent.CancellationException e){throw e;}
        catch(Exception e){throw new IOException("Report figure export failed; analysis remains incomplete",e);}
    }
}
