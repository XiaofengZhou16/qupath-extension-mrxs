package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.Path;
import java.util.ArrayList;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;

/** Opt-in, fixture-specific source-pixel export. Demonstration display settings are not assay thresholds. */
public class IfPublicationSmoke {
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("four-channel-test-slide.mrxs output-folder");
        try(var server=new MrxsImageServer(Path.of(args[0]).toUri())) {
            var roi=ROIs.createRectangleROI(32768,81920,1024,1024,ImagePlane.getDefaultPlane());
            var raw=server.readRegion(RegionRequest.createInstance(server.getPath(),1,roi));
            var channels=new ArrayList<IfDisplayProfile.Channel>();
            for(int c=0;c<server.nChannels();c++) {
                long[] histogram=new long[256];
                for(int y=0;y<raw.getHeight();y++)for(int x=0;x<raw.getWidth();x++)histogram[raw.getRaster().getSample(x,y,c)]++;
                long total=(long)raw.getWidth()*raw.getHeight(),sum=0;int upper=255;
                for(int i=0;i<256;i++){sum+=histogram[i];if(sum>=total*0.995){upper=Math.max(1,i);break;}}
                var channel=server.getChannel(c);
                channels.add(new IfDisplayProfile.Channel(c,channel.getName(),channel.getColor(),0,upper,true));
            }
            var profile=new IfDisplayProfile(1,1,false,false,channels);
            var start=System.nanoTime();
            var manifest=IfPublicationExport.export(server,roi,profile,new IfPublicationExport.Options(0,300,8,true,true,50),
                    Path.of(args[1]),()->false,p->{});
            System.out.println("IF_PUBLICATION_SMOKE_OK manifest="+manifest+" seconds="+(System.nanoTime()-start)/1e9);
        }
    }
}
