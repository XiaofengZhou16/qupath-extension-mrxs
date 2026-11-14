package io.github.xiaofengzhou.qupath.mrxs;

import org.junit.jupiter.api.Test;
import qupath.lib.color.ColorModelFactory;
import qupath.lib.images.servers.*;
import java.awt.Point;
import java.awt.geom.Point2D;
import java.awt.image.*;
import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class IfHiDpiRendererTest {
    @Test void rawAndRgbTilesShareOneBackingStorageBudget() {
        var request=qupath.lib.regions.RegionRequest.createInstance("cache-test",1,0,0,2,2);
        var raw=new BufferedImage(2,2,BufferedImage.TYPE_USHORT_GRAY);
        var rgb=new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB);
        assertEquals(8,IfHiDpiRenderer.TileCache.pixelStorageBytes(raw));
        assertEquals(16,IfHiDpiRenderer.TileCache.pixelStorageBytes(rgb));
        try(var cache=new IfHiDpiRenderer.TileCache(20)) {
            cache.put(request,null,raw);assertEquals(8,cache.bytes());
            cache.put(request,profile(),rgb);assertEquals(16,cache.bytes());
            assertNull(cache.get(request,null));assertSame(rgb,cache.get(request,profile()));
            cache.put(request,null,raw);assertEquals(8,cache.bytes());
            assertNull(cache.get(request,profile()));assertSame(raw,cache.get(request,null));
            cache.close();cache.put(request,null,raw);assertEquals(0,cache.bytes());assertNull(cache.get(request,null));
        }
    }
    private static IfDisplayProfile profile(){return new IfDisplayProfile(1,1,false,false,List.of(new IfDisplayProfile.Channel(0,"Pattern",0xffffff,0,255,true)));}
    private static IfHiDpiRenderer.View view(double scale,double rotation){return new IfHiDpiRenderer.View(16,16,scale,scale,16,16,2,rotation,0,0,0,false);}
    @Test void devicePixelFrameRetainsNativeAlternatingLinesInsteadOfEnlargingLogicalBuffer() throws Exception {
        try(var server=new PatternServer()) {
            var logical=IfHiDpiRenderer.render(server,profile(),view(1,0),()->false);
            var retina=IfHiDpiRenderer.render(server,profile(),view(2,0),()->false);
            assertEquals(1,logical.nativeLevel());assertEquals(0,retina.nativeLevel());
            assertEquals(16,logical.image().getWidth());assertEquals(32,retina.image().getWidth());
            for(int y=0;y<32;y++)for(int x=0;x<32;x++)assertEquals(x%2==0?0xff000000:0xffffffff,retina.image().getRGB(x,y));
            for(int y=0;y<16;y++)for(int x=0;x<16;x++)assertEquals(0xff808080,logical.image().getRGB(x,y));
        }
    }
    @Test void quarterTurnAndFractionalOutputScaleUseTheSameSourceCenter() throws Exception {
        var view=new IfHiDpiRenderer.View(20,12,1.5,2,16,16,2,Math.PI/2,0,0,0,false);
        var center=IfHiDpiRenderer.sourceToDevice(view).transform(new Point2D.Double(16,16),null);
        assertEquals(15,center.getX(),1e-10);assertEquals(12,center.getY(),1e-10);
        try(var server=new PatternServer()) {
            var frame=IfHiDpiRenderer.render(server,profile(),view(2,Math.PI/2),()->false);
            for(int y=0;y<32;y++)for(int x=0;x<32;x++)assertEquals(y%2==0?0xff000000:0xffffffff,frame.image().getRGB(x,y));
        }
    }
    @Test void cancellationAndFrameBudgetsStopBeforeReadsAndOffImageIsBackground() throws Exception {
        try(var server=new PatternServer()) {
            assertThrows(CancellationException.class,()->IfHiDpiRenderer.render(server,profile(),view(2,0),()->true));assertEquals(0,server.reads);
            assertThrows(IllegalArgumentException.class,()->IfHiDpiRenderer.render(server,profile(),
                    new IfHiDpiRenderer.View(10000,10000,2,2,16,16,2,0,0,0,0,false),()->false));assertEquals(0,server.reads);
            var frame=IfHiDpiRenderer.render(server,profile(),new IfHiDpiRenderer.View(16,16,2,2,-100,-100,2,0,0,0,0x123456,false),()->false);
            assertEquals(0,frame.tilesRead());assertEquals(0xff123456,frame.image().getRGB(10,10));
        }
    }
    @Test void cachedTilesAvoidSourceReadsAndInvalidateWhenDisplayProfileChanges() throws Exception {
        try(var server=new PatternServer();var cache=new IfHiDpiRenderer.TileCache(1024*1024)) {
            var first=IfHiDpiRenderer.render(server,profile(),view(2,0),()->false,cache);int originalReads=server.reads;
            var again=IfHiDpiRenderer.render(server,profile(),view(2,0),()->false,cache);
            assertEquals(0,again.tilesRead());assertEquals(first.tilesRead(),again.cachedTiles());assertEquals(originalReads,server.reads);
            assertArrayEquals(first.image().getRGB(0,0,32,32,null,0,32),again.image().getRGB(0,0,32,32,null,0,32));
            var blue=new IfDisplayProfile(1,1,false,false,List.of(new IfDisplayProfile.Channel(0,"Pattern",0x0000ff,0,255,true)));
            var changed=IfHiDpiRenderer.render(server,blue,view(2,0),()->false,cache);
            assertEquals(0,changed.tilesRead());assertEquals(0,changed.cachedTiles());assertEquals(first.tilesRead(),changed.rawCachedTiles());
            assertEquals(originalReads,server.reads);assertEquals(0xff0000ff,changed.image().getRGB(1,0));
            var fresh=IfHiDpiRenderer.render(server,blue,view(2,0),()->false);
            assertArrayEquals(fresh.image().getRGB(0,0,32,32,null,0,32),changed.image().getRGB(0,0,32,32,null,0,32));
            var restored=IfHiDpiRenderer.render(server,profile(),view(2,0),()->false,cache);
            assertArrayEquals(first.image().getRGB(0,0,32,32,null,0,32),restored.image().getRGB(0,0,32,32,null,0,32));
        }
    }
    @Test void cacheBudgetAndClosedStateCannotRetainOrRepopulateTiles() throws Exception {
        try(var server=new PatternServer();var cache=new IfHiDpiRenderer.TileCache(256)) {
            IfHiDpiRenderer.render(server,profile(),view(2,0),()->false,cache);assertTrue(cache.bytes()<=256);
            cache.close();assertEquals(0,cache.bytes());
            var after=IfHiDpiRenderer.render(server,profile(),view(2,0),()->false,cache);
            assertEquals(0,after.cachedTiles());assertEquals(0,cache.bytes());
        }
    }
    @Test void changingOutputScaleReplansNativeLevelAndCacheMatchesFreshRendering() throws Exception {
        try(var server=new PatternServer();var cache=new IfHiDpiRenderer.TileCache(1024*1024)) {
            for(double sx:List.of(1.0,1.5,2.0))for(double sy:List.of(1.0,1.5,2.0)) {
                var geometry=new IfHiDpiRenderer.View(15,13,sx,sy,16,16,2,0.27,0,0,0,false);
                var cached=IfHiDpiRenderer.render(server,profile(),geometry,()->false,cache);
                var fresh=IfHiDpiRenderer.render(server,profile(),geometry,()->false);
                assertEquals((int)Math.ceil(15*sx),cached.image().getWidth());
                assertEquals((int)Math.ceil(13*sy),cached.image().getHeight());
                assertEquals(Math.max(sx,sy)==1?1:0,cached.nativeLevel());
                int width=cached.image().getWidth(),height=cached.image().getHeight();
                assertArrayEquals(fresh.image().getRGB(0,0,width,height,null,0,width),
                        cached.image().getRGB(0,0,width,height,null,0,width));
                var center=IfHiDpiRenderer.sourceToDevice(geometry).transform(new Point2D.Double(16,16),null);
                assertEquals(15*sx/2,center.getX(),1e-10);
                assertEquals(13*sy/2,center.getY(),1e-10);
            }
            var restored=IfHiDpiRenderer.render(server,profile(),view(1,0),()->false,cache);
            assertEquals(1,restored.nativeLevel());assertEquals(16,restored.image().getWidth());
        }
    }
    private static final class PatternServer extends AbstractTileableImageServer {
        final ImageServerMetadata metadata=new ImageServerMetadata.Builder(getClass(),"native-resolution-pattern",32,32)
                .rgb(false).pixelType(PixelType.UINT8).channels(List.of(ImageChannel.getInstance("Pattern",0xffffff)))
                .levelsFromDownsamples(1,2).preferredTileSize(8,8).build();
        int reads;
        @Override protected BufferedImage readTile(TileRequest request) {
            reads++;int w=request.getTileWidth(),h=request.getTileHeight();var raster=Raster.createBandedRaster(DataBuffer.TYPE_BYTE,w,h,1,new Point());
            for(int y=0;y<h;y++)for(int x=0;x<w;x++)raster.setSample(x,y,0,request.getLevel()==0?((request.getTileX()+x)%2==0?0:255):128);
            return new BufferedImage(ColorModelFactory.createColorModel(PixelType.UINT8,metadata.getChannels()),raster,false,null);
        }
        @Override public ImageServerMetadata getOriginalMetadata(){return metadata;}
        @Override protected String createID(){return "pattern-"+System.identityHashCode(this);}
        @Override public String getServerType(){return "Synthetic native detail truth";}
        @Override public Collection<URI> getURIs(){return List.of();}
        @Override protected ImageServerBuilder.ServerBuilder<BufferedImage> createServerBuilder(){return null;}
    }
}
