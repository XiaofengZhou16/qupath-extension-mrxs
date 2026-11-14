package io.github.xiaofengzhou.qupath.mrxs;

import qupath.lib.io.GsonTools;
import qupath.lib.regions.RegionRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Direct source rendering validation, not screen or viewer capture. */
public class IfHiDpiSmoke {
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("slide.mrxs new-output-directory");
        Path output=Path.of(args[1]);Files.createDirectory(output);
        try(var server=new MrxsImageServer(Path.of(args[0]).toUri())) {
            var channels=java.util.stream.IntStream.range(0,server.nChannels()).mapToObj(c->
                    new IfDisplayProfile.Channel(c,server.getChannel(c).getName(),server.getChannel(c).getColor(),0,255,true)).toList();
            var profile=new IfDisplayProfile(1,1,false,false,channels);var observations=new ArrayList<Object>();
            var cacheObservations=new ArrayList<Object>();
            for(double viewerDownsample:List.of(2.0,4.0)) {
                var view=new IfHiDpiRenderer.View(512,512,2,2,33280,82432,viewerDownsample,0,0,0,0,false);
                var frame=IfHiDpiRenderer.render(server,profile,view,()->false);
                int span=(int)(512*viewerDownsample),x=33280-span/2,y=82432-span/2;
                var raw=server.readRegion(RegionRequest.createInstance(server.getPath(),frame.nativeDownsample(),x,y,span,span));
                var reference=IfDisplaySettings.render(server,raw,profile,channels.stream().map(IfDisplayProfile.Channel::index).toList());
                if(reference.getWidth()!=frame.image().getWidth()||reference.getHeight()!=frame.image().getHeight())throw new IllegalStateException("Reference size mismatch");
                long matched=0;
                for(int py=0;py<reference.getHeight();py++)for(int px=0;px<reference.getWidth();px++) {
                    if(reference.getRGB(px,py)!=frame.image().getRGB(px,py))throw new IllegalStateException("Device frame does not match native reference at "+px+","+py);
                    matched++;
                }
                var result=Map.of("viewerDownsample",viewerDownsample,"renderScale",2,"nativeLevel",frame.nativeLevel(),"nativeDownsample",frame.nativeDownsample(),
                        "frameWidth",frame.image().getWidth(),"frameHeight",frame.image().getHeight(),"tilesRead",frame.tilesRead(),
                        "renderSeconds",frame.seconds(),"matchedNativeReferencePixels",matched);
                observations.add(result);System.out.println("IF_HIDPI_SOURCE_RENDER_OK "+GsonTools.getInstance().toJson(result));
                try(var cache=new IfHiDpiRenderer.TileCache(64L*1024*1024)) {
                    var first=IfHiDpiRenderer.render(server,profile,view,()->false,cache);
                    var changedProfile=new IfDisplayProfile(1,0.7,false,false,channels);
                    var changed=IfHiDpiRenderer.render(server,changedProfile,view,()->false,cache);
                    var fresh=IfHiDpiRenderer.render(server,changedProfile,view,()->false);
                    if(changed.tilesRead()!=0||changed.rawCachedTiles()!=first.tilesRead()||changed.cachedTiles()!=0)
                        throw new IllegalStateException("Display change failed to reuse raw tiles");
                    int w=fresh.image().getWidth(),h=fresh.image().getHeight();
                    if(!java.util.Arrays.equals(fresh.image().getRGB(0,0,w,h,null,0,w),changed.image().getRGB(0,0,w,h,null,0,w)))
                        throw new IllegalStateException("Raw-cache recoloring differs from fresh source reads");
                    var detail=Map.of("viewerDownsample",viewerDownsample,"sourceReadsAfterGammaChange",changed.tilesRead(),
                            "rawCacheHits",changed.rawCachedTiles(),"displayCacheHits",changed.cachedTiles(),"cachedPixelStorageBytes",cache.bytes(),
                            "freshReferencePixelsMatched",(long)w*h);
                    cacheObservations.add(detail);System.out.println("IF_HIDPI_RAW_CACHE_OK "+GsonTools.getInstance().toJson(detail));
                }
            }
            Files.writeString(output.resolve("verification.json"),GsonTools.getInstance(true).toJson(Map.of("runs",observations,"rawCacheRuns",cacheObservations,"screenshots",false,
                    "scope","Device-resolution source-rendering core and recoloring cache checks only; no GUI, optical-resolution or perceptual-equivalence claim")));
        }
    }
}
