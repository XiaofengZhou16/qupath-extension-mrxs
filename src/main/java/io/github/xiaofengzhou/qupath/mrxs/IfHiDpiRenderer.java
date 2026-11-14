package io.github.xiaofengzhou.qupath.mrxs;

import qupath.lib.images.servers.ImageServer;
import qupath.lib.regions.RegionRequest;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Source-tile renderer for a device-resolution viewport. No screen capture or viewer mutation.
 * The main viewer integration is separate: this core intentionally has no JavaFX state. */
final class IfHiDpiRenderer {
    record View(int logicalWidth,int logicalHeight,double scaleX,double scaleY,double centerX,double centerY,
                double downsample,double rotation,int z,int t,int backgroundRgb,boolean bilinear) {
        View {
            if(logicalWidth<1||logicalHeight<1||!Double.isFinite(scaleX)||scaleX<1||scaleX>4
                    ||!Double.isFinite(scaleY)||scaleY<1||scaleY>4||!Double.isFinite(centerX)||!Double.isFinite(centerY)
                    ||!Double.isFinite(downsample)||downsample<=0||!Double.isFinite(rotation)||z<0||t<0)
                throw new IllegalArgumentException("Invalid HiDPI view geometry");
        }
    }
    record Frame(BufferedImage image,View view,int nativeLevel,double nativeDownsample,int tilesRead,int cachedTiles,int rawCachedTiles,double seconds) {}

    /** Viewer-local raw/RGB LRU sharing one pixel-storage budget; null profile denotes raw. */
    static final class TileCache implements AutoCloseable {
        private record Key(RegionRequest request,IfDisplayProfile profile) {}
        private record Entry(BufferedImage image,long bytes) {}
        private final java.util.LinkedHashMap<Key,Entry> images=new java.util.LinkedHashMap<>(32,0.75f,true);
        private final long limit;
        private long bytes;
        private boolean closed;
        TileCache(long limit){if(limit<0)throw new IllegalArgumentException("Negative tile cache budget");this.limit=limit;}
        synchronized BufferedImage get(RegionRequest request,IfDisplayProfile profile){
            var entry=closed?null:images.get(new Key(request,profile));return entry==null?null:entry.image();
        }
        synchronized void put(RegionRequest request,IfDisplayProfile profile,BufferedImage image) {
            long size=pixelStorageBytes(image);
            if(closed||size==Long.MAX_VALUE||size>limit)return;
            var old=images.put(new Key(request,profile),new Entry(image,size));if(old!=null)bytes-=old.bytes();
            bytes+=size;var iterator=images.entrySet().iterator();
            while(bytes>limit&&iterator.hasNext()){bytes-=iterator.next().getValue().bytes();iterator.remove();}
        }
        // Count actual backing primitive arrays, including padding/offset storage.
        // Unknown custom buffers are not cached. Object/map overhead is not included.
        static long pixelStorageBytes(BufferedImage image) {
            var b=image.getRaster().getDataBuffer();long size=0;
            if(b instanceof java.awt.image.DataBufferByte v){for(var a:v.getBankData())size+=a.length;}
            else if(b instanceof java.awt.image.DataBufferUShort v){for(var a:v.getBankData())size+=2L*a.length;}
            else if(b instanceof java.awt.image.DataBufferShort v){for(var a:v.getBankData())size+=2L*a.length;}
            else if(b instanceof java.awt.image.DataBufferInt v){for(var a:v.getBankData())size+=4L*a.length;}
            else if(b instanceof java.awt.image.DataBufferFloat v){for(var a:v.getBankData())size+=4L*a.length;}
            else if(b instanceof java.awt.image.DataBufferDouble v){for(var a:v.getBankData())size+=8L*a.length;}
            else return Long.MAX_VALUE;
            return size;
        }
        synchronized long bytes(){return bytes;}
        @Override public synchronized void close(){closed=true;images.clear();bytes=0;}
    }

    static AffineTransform sourceToDevice(View view) {
        var transform=AffineTransform.getScaleInstance(view.scaleX(),view.scaleY());
        transform.translate(view.logicalWidth()/2.0,view.logicalHeight()/2.0);
        transform.scale(1/view.downsample(),1/view.downsample());transform.translate(-view.centerX(),-view.centerY());
        transform.rotate(view.rotation(),view.centerX(),view.centerY());return transform;
    }
    static Frame render(ImageServer<BufferedImage> server,IfDisplayProfile profile,View view,BooleanSupplier cancelled) throws IOException {
        return render(server,profile,view,cancelled,null);
    }
    static Frame render(ImageServer<BufferedImage> server,IfDisplayProfile profile,View view,BooleanSupplier cancelled,TileCache cache) throws IOException {
        long start=System.nanoTime();check(cancelled);profile.validateFor(server);
        if(view.z()>=server.nZSlices()||view.t()>=server.nTimepoints())throw new IllegalArgumentException("View plane outside source image");
        var plan=MrxsDisplaySampling.plan(server.getPreferredDownsamples(),view.downsample(),view.scaleX(),view.scaleY(),view.logicalWidth(),view.logicalHeight());
        if(plan.renderPixelCount()>16_777_216L||plan.renderPixelCount()*16>Runtime.getRuntime().maxMemory()/3)
            throw new IllegalArgumentException("HiDPI viewport exceeds frame memory budget; reduce window size/render scale");
        int width=(int)Math.ceil(view.logicalWidth()*view.scaleX()),height=(int)Math.ceil(view.logicalHeight()*view.scaleY());
        var transform=sourceToDevice(view);java.awt.Shape visible;
        try{visible=transform.createInverse().createTransformedShape(new Rectangle2D.Double(0,0,width,height));}
        catch(java.awt.geom.NoninvertibleTransformException e){throw new IllegalArgumentException("Noninvertible view",e);}
        var bounds=visible.getBounds2D();
        int x=(int)Math.max(0,Math.min(server.getWidth(),Math.floor(bounds.getMinX())));
        int y=(int)Math.max(0,Math.min(server.getHeight(),Math.floor(bounds.getMinY())));
        int x2=(int)Math.max(0,Math.min(server.getWidth(),Math.ceil(bounds.getMaxX())));
        int y2=(int)Math.max(0,Math.min(server.getHeight(),Math.ceil(bounds.getMaxY())));
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);int reads=0,cached=0,rawCached=0;
        var g=image.createGraphics();
        try {
            g.setColor(new Color(view.backgroundRgb()));g.fillRect(0,0,width,height);
            g.setClip(0,0,width,height);g.transform(transform);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,view.bilinear()?RenderingHints.VALUE_INTERPOLATION_BILINEAR:RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            if(x2>x&&y2>y) {
                double ds=plan.detailLevelDownsample();
                var request=RegionRequest.createInstance(server.getPath(),ds,x,y,x2-x,y2-y,view.z(),view.t());
                var tiles=server.getTileRequestManager().getTileRequests(request);
                if(tiles.size()>1024)throw new IllegalArgumentException("Too many native tiles for one interactive frame");
                var selected=profile.selectedChannels().stream().map(IfDisplayProfile.Channel::index).toList();
                var renderer=IfDisplaySettings.prepare(server,profile);
                for(var tile:tiles) {
                    check(cancelled);
                    if(tile.getLevel()!=plan.detailLevel())throw new IOException("Unexpected native level selected for device-resolution frame");
                    double tx=tile.getTileX()*ds,ty=tile.getTileY()*ds;
                    if(!visible.intersects(tx,ty,tile.getTileWidth()*ds,tile.getTileHeight()*ds))continue;
                    long tilePixels=(long)tile.getTileWidth()*tile.getTileHeight();
                    if(tilePixels>4_194_304L||tilePixels*(server.nChannels()*8L+16)>Runtime.getRuntime().maxMemory()/4)
                        throw new IllegalArgumentException("Source tile exceeds frame memory budget");
                    var rendered=cache==null?null:cache.get(tile.getRegionRequest(),profile);
                    if(rendered==null) {
                        var raw=cache==null?null:cache.get(tile.getRegionRequest(),null);
                        if(raw==null){raw=server.readRegion(tile.getRegionRequest());reads++;}
                        else rawCached++;
                        check(cancelled);
                        if(raw==null||raw.getWidth()!=tile.getTileWidth()||raw.getHeight()!=tile.getTileHeight())
                            throw new IOException("Unexpected native tile dimensions");
                        rendered=renderer.render(raw,selected);check(cancelled);
                        if(cache!=null){cache.put(tile.getRegionRequest(),null,raw);cache.put(tile.getRegionRequest(),profile,rendered);}
                    } else cached++;
                    var placement=AffineTransform.getTranslateInstance(tx,ty);placement.scale(ds,ds);
                    g.drawImage(rendered,placement,null);
                }
            }
        }finally{g.dispose();}
        check(cancelled);
        return new Frame(image,view,plan.detailLevel(),plan.detailLevelDownsample(),reads,cached,rawCached,(System.nanoTime()-start)/1e9);
    }
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException();}
}
