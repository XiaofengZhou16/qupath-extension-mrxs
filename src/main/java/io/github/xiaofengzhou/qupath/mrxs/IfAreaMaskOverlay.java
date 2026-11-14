package io.github.xiaofengzhou.qupath.mrxs;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.STRtree;
import qupath.lib.gui.viewer.overlays.PathOverlay;
import qupath.lib.images.ImageData;
import qupath.lib.io.GsonTools;
import qupath.lib.regions.ImageRegion;
import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Numeric PA masks rendered as a transient overlay. File I/O never occurs in paintOverlay. */
final class IfAreaMaskOverlay implements PathOverlay, AutoCloseable {
    record Dataset(Path directory,STRtree index,double downsample,int z,int t,int markerCount) {}
    private record Filter(int bits,boolean exact) {}
    private record Key(String file,Filter filter) {}
    private final ImageData<BufferedImage> source;
    private final Dataset data;
    private final Runnable repaint;
    private final Consumer<String> status;
    private final ThreadPoolExecutor loader=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(32),r->{
        var thread=new Thread(r,"if-pa-mask-preview");thread.setDaemon(true);return thread;});
    private final LinkedHashMap<Key,BufferedImage> cache=new LinkedHashMap<>(32,0.75f,true);
    private final Set<Key> pending=new HashSet<>();
    private final Set<String> failures=new HashSet<>();
    private final long cacheLimit=Math.min(64L*1024*1024,Runtime.getRuntime().maxMemory()/32);
    private long cacheBytes;
    private volatile Filter filter=new Filter(0,false);
    private volatile boolean visible,closed;
    private volatile float opacity=0.4f;
    private String lastStatus="";

    IfAreaMaskOverlay(ImageData<BufferedImage> source,Dataset data,Runnable repaint,Consumer<String> status) {
        this.source=source;this.data=data;this.repaint=repaint;this.status=status;
    }
    /** Run on the analysis worker after successful export, not on the JavaFX application thread. */
    static Dataset readIndex(Path analysisDirectory,IfAnalysis.Result result,int z,int t,BooleanSupplier cancelled) throws IOException {
        return readIndex(analysisDirectory,result.profile(),result.downsample(),result.roiPixels(),(long)result.tilesRead()+result.zeroTilesCounted(),z,t,cancelled);
    }

    static Dataset readHistoricalIndex(IfAnalysisHistory.Entry entry,BooleanSupplier cancelled) throws IOException {
        if(cancelled.getAsBoolean())throw new CancellationException();
        try {
            var parent=com.google.gson.JsonParser.parseString(readMetadata(entry.directory().resolve("manifest.json"))).getAsJsonObject();
            if(!entry.profile().equals(GsonTools.getInstance().fromJson(parent.get("profile"),IfAnalysisProfile.class))
                    ||!entry.createdAt().equals(parent.get("createdAt").getAsString()))throw new IOException("Historical run changed during mask load");
            if(!parent.has("areaMasks")||!parent.getAsJsonObject("areaMasks").get("exported").getAsBoolean())throw new IOException("This historical run did not export PA masks");
            long pixels=parent.get("roiPixels").getAsLong(),tiles=parent.get("tilesRead").getAsLong()+
                    (parent.has("indexCertifiedZeroTilesCountedWithoutRead")?parent.get("indexCertifiedZeroTilesCountedWithoutRead").getAsLong():0);
            double ds=parent.get("downsample").getAsDouble();
            if(pixels<0||tiles<0||!Double.isFinite(ds)||ds<1)throw new IOException("Invalid historical mask coverage");
            return readIndex(entry.directory(),entry.profile(),ds,pixels,tiles,entry.roi().getZ(),entry.roi().getT(),cancelled);
        }catch(CancellationException e){throw e;}catch(RuntimeException e){throw new IOException("Invalid historical PA metadata",e);}
    }

    private static Dataset readIndex(Path analysisDirectory,IfAnalysisProfile profile,double downsample,long roiPixels,long tileCount,int z,int t,BooleanSupplier cancelled) throws IOException {
        if(Files.exists(analysisDirectory.resolve("INCOMPLETE")) || !Files.isRegularFile(analysisDirectory.resolve("manifest.json")))
            throw new IOException("Analysis is incomplete");
        Path directory=analysisDirectory.resolve("area-masks");
        if(Files.isSymbolicLink(directory))throw new IOException("Linked mask directory is not supported");
        var parent=com.google.gson.JsonParser.parseString(readMetadata(analysisDirectory.resolve("manifest.json"))).getAsJsonObject();
        String maskText=readMetadata(directory.resolve("manifest.json"));
        if(!IfAreaMasks.digest(directory.resolve("manifest.json")).equals(parent.getAsJsonObject("areaMasks").get("manifestSha256").getAsString()))
            throw new IOException("PA mask manifest checksum mismatch");
        var metadata=com.google.gson.JsonParser.parseString(maskText).getAsJsonObject();
        int schema=metadata.get("schemaVersion").getAsInt();
        boolean supportedEncoding=schema==1 && "uint16_png_bitmask_plus_one".equals(metadata.get("encoding").getAsString())
                ||schema==2 && "uint16_png_bitmask_plus_one_shared_constants".equals(metadata.get("encoding").getAsString());
        requireFile(directory.resolve("tiles.jsonl"),64L*1024*1024);
        if(!IfAreaMasks.digest(directory.resolve("tiles.jsonl")).equals(metadata.get("indexSha256").getAsString())
                || !supportedEncoding||!"complete".equals(metadata.get("status").getAsString())
                ||!profile.equals(GsonTools.getInstance().fromJson(metadata.get("profile"),IfAnalysisProfile.class))
                ||metadata.get("tiles").getAsLong()!=tileCount||metadata.get("roiPixels").getAsLong()!=roiPixels
                || metadata.get("downsample").getAsDouble()!=downsample
                || metadata.get("sourceZ").getAsInt()!=z || metadata.get("sourceT").getAsInt()!=t)
            throw new IOException("PA index checksum or plane/resolution mismatch");
        var tree=new STRtree();int count=0;long pixels=0;var names=new HashSet<String>();
        var coordinates=new HashSet<Long>();var sharedHashes=new java.util.HashMap<String,String>();
        try(var lines=Files.newBufferedReader(directory.resolve("tiles.jsonl"))) {
            for(String line;(line=lines.readLine())!=null;) {
                if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException();
                if(line.length()>16384)throw new IOException("Oversized PA index entry");
                var tile=GsonTools.getInstance().fromJson(line,IfAreaMasks.Tile.class);
                if(tile==null || tile.file()==null
                        || tile.levelX()<0 || tile.levelY()<0 || tile.width()<1 || tile.height()<1
                        || (long)tile.width()*tile.height()>4_194_304L || tile.roiPixels()<0
                        || tile.roiPixels()>(long)tile.width()*tile.height()||tile.sha256()==null||!tile.sha256().matches("[0-9a-f]{64}")
                        ||!coordinates.add(((long)tile.levelX()<<32)|(tile.levelY()&0xffffffffL)))throw new IOException("Invalid or duplicate PA tile index");
                boolean shared=schema==2 && tile.file().startsWith("constant-");
                if(shared) {
                    String prefix="constant-"+tile.width()+"-"+tile.height()+"-";
                    if(!tile.file().startsWith(prefix)||!tile.file().endsWith(".png"))throw new IOException("Invalid shared mask name");
                    int code;
                    try {code=Integer.parseInt(tile.file().substring(prefix.length(),tile.file().length()-4));}
                    catch(NumberFormatException e){throw new IOException("Invalid shared mask code",e);}
                    if(code<0||code>(1<<profile.markers().size())||!tile.file().equals(prefix+code+".png")
                            ||tile.roiPixels()!=(code==0?0:(long)tile.width()*tile.height()))throw new IOException("Invalid shared mask coverage/code");
                    var old=sharedHashes.putIfAbsent(tile.file(),tile.sha256());
                    if(old!=null&&!old.equals(tile.sha256()))throw new IOException("Conflicting shared mask hashes");
                } else if(!tile.file().equals("tile-"+tile.levelX()+"-"+tile.levelY()+".png")||!names.add(tile.file()))
                    throw new IOException("Invalid or duplicate PA tile filename");
                if(++count*384L>Runtime.getRuntime().maxMemory()/8)throw new IOException("PA tile index exceeds preview memory budget");
                double ds=downsample;pixels=Math.addExact(pixels,tile.roiPixels());
                tree.insert(new Envelope(tile.levelX()*ds,(tile.levelX()+(double)tile.width())*ds,
                        tile.levelY()*ds,(tile.levelY()+(double)tile.height())*ds),tile);
            }
        }
        if(count!=tileCount || pixels!=roiPixels)throw new IOException("PA index coverage differs from analysis");
        if(schema==2 && (metadata.get("uniquePngFiles").getAsLong()!=names.size()+sharedHashes.size()
                ||metadata.get("sharedConstantPngFiles").getAsLong()!=sharedHashes.size()))
            throw new IOException("Shared PA file inventory differs from index");
        if(!maskText.equals(readMetadata(directory.resolve("manifest.json")))||!IfAreaMasks.digest(directory.resolve("tiles.jsonl")).equals(metadata.get("indexSha256").getAsString()))throw new IOException("PA index changed during load");
        tree.build();return new Dataset(directory,tree,downsample,z,t,profile.markers().size());
    }
    private static void requireFile(Path path,long limit)throws IOException {if(!Files.isRegularFile(path,java.nio.file.LinkOption.NOFOLLOW_LINKS)||Files.size(path)>limit)throw new IOException("Missing, linked or oversized PA input: "+path);}
    private static String readMetadata(Path path)throws IOException {
        int limit=2*1024*1024;requireFile(path,limit);try(var in=Files.newInputStream(path)){byte[] bytes=in.readNBytes(limit+1);if(bytes.length>limit)throw new IOException("Oversized PA metadata");return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);}
    }

    void setVisible(boolean value){visible=value;}
    float opacity(){return opacity;}
    void setFilter(int bits,boolean exact){filter=new Filter(bits,exact);}
    void setOpacity(double value){if(!Double.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("Opacity must be 0..1");opacity=(float)value;}

    @Override public void paintOverlay(Graphics2D graphics,ImageRegion region,double downsample,
                                       ImageData<BufferedImage> imageData,boolean paintCompletely) {
        if(closed||!visible||opacity==0||imageData!=source||region.getZ()!=data.z()||region.getT()!=data.t())return;
        var selected=filter;
        List<?> tiles=data.index().query(IfOverlayViewport.bounds(graphics,region));
        long bytes=0;for(var item:tiles){var tile=(IfAreaMasks.Tile)item;bytes+=(long)tile.width()*tile.height()*4;}
        if(tiles.size()>128 || bytes>cacheLimit){report("Zoom in for PA masks: visible tiles exceed the bounded preview cache.");return;}
        var g=(Graphics2D)graphics.create();int missing=0;
        try {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,opacity));
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            for(var item:tiles) {
                var tile=(IfAreaMasks.Tile)item;var key=new Key(tile.file(),selected);BufferedImage image;
                synchronized(cache){image=cache.get(key);}
                if(image==null){missing++;request(tile,key);continue;}
                var transform=AffineTransform.getTranslateInstance(tile.levelX()*data.downsample(),tile.levelY()*data.downsample());
                transform.scale(data.downsample(),data.downsample());g.drawImage(image,transform,null);
            }
        } finally {g.dispose();}
        synchronized(cache){
            report(!failures.isEmpty()?"PA mask read failed; affected tiles are hidden. Re-run analysis to regenerate files.":
                    missing>0?"Loading PA mask tiles…":"PA mask overlay: raw pixel positivity, not cell phenotype or molecular interaction.");
        }
    }
    private void request(IfAreaMasks.Tile tile,Key key) {
        synchronized(cache){if(closed||failures.contains(tile.file())||pending.contains(key)||pending.size()>=32)return;pending.add(key);}
        try {loader.execute(()->{
            try {
                if(closed||!filter.equals(key.filter()))return;
                Path path=data.directory().resolve(tile.file());
                requireFile(path,32L*1024*1024);
                if(!IfAreaMasks.digest(path).equals(tile.sha256()))throw new IOException("PA tile checksum mismatch");
                BufferedImage raw;
                try(var input=new javax.imageio.stream.MemoryCacheImageInputStream(Files.newInputStream(path))) {
                    var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("Unreadable PA PNG");
                    var reader=readers.next();try{reader.setInput(input);
                        if(!reader.getFormatName().equalsIgnoreCase("png")||reader.getWidth(0)!=tile.width()||reader.getHeight(0)!=tile.height())throw new IOException("PA PNG header dimensions/type changed");
                        raw=reader.read(0);
                    }finally{reader.dispose();}
                }
                if(raw==null||raw.getWidth()!=tile.width()||raw.getHeight()!=tile.height()
                        ||raw.getRaster().getDataBuffer().getDataType()!=java.awt.image.DataBuffer.TYPE_USHORT)throw new IOException("PA tile dimensions/type changed");
                int expectedConstant=tile.file().startsWith("constant-")
                        ?Integer.parseInt(tile.file().substring(tile.file().lastIndexOf('-')+1,tile.file().length()-4)):-1;
                var image=render(raw,data.markerCount(),key.filter().bits(),key.filter().exact(),expectedConstant);
                synchronized(cache){
                    if(closed||!filter.equals(key.filter()))return;
                    var previous=cache.put(key,image);if(previous!=null)cacheBytes-=(long)previous.getWidth()*previous.getHeight()*4;
                    cacheBytes+=(long)image.getWidth()*image.getHeight()*4;
                    var iterator=cache.entrySet().iterator();
                    while(cacheBytes>cacheLimit&&iterator.hasNext()){var old=iterator.next().getValue();cacheBytes-=(long)old.getWidth()*old.getHeight()*4;iterator.remove();}
                }
            }catch(Exception e){if(!closed){synchronized(cache){failures.add(tile.file());}}}
            finally{synchronized(cache){pending.remove(key);}if(!closed)repaint.run();}
        });}catch(java.util.concurrent.RejectedExecutionException ignored){synchronized(cache){pending.remove(key);}}
    }
    static BufferedImage render(BufferedImage raw,int markers,int required,boolean exact) throws IOException {
        return render(raw,markers,required,exact,-1);
    }
    static BufferedImage render(BufferedImage raw,int markers,int required,boolean exact,int expectedConstant) throws IOException {
        if(markers<1||markers>8||required<0||required>=(1<<markers)||raw.getRaster().getNumBands()!=1)
            throw new IOException("Invalid numeric mask/render settings");
        if(expectedConstant < -1 || expectedConstant > (1<<markers))throw new IOException("Invalid constant mask declaration");
        var image=new BufferedImage(raw.getWidth(),raw.getHeight(),BufferedImage.TYPE_INT_ARGB);
        var output=((DataBufferInt)image.getRaster().getDataBuffer()).getData();
        for(int y=0;y<raw.getHeight();y++) {
            if(Thread.currentThread().isInterrupted())throw new CancellationException();
            for(int x=0;x<raw.getWidth();x++) {
                int code=raw.getRaster().getSample(x,y,0);
                if(code<0||code>(1<<markers))throw new IOException("Invalid PA mask code");
                if(expectedConstant>=0 && code!=expectedConstant)throw new IOException("Shared PA PNG differs from declared constant code");
                if(IfAreaMasks.matches(code,required,exact))output[y*raw.getWidth()+x]=0xff000000|
                        Color.HSBtoRGB((float)(((code-1)*0.61803398875)%1),0.75f,1);
            }
        }
        return image;
    }
    private synchronized void report(String message){if(!message.equals(lastStatus)){lastStatus=message;status.accept(message);}}
    @Override public void close(){closed=true;loader.shutdownNow();synchronized(cache){cache.clear();cacheBytes=0;pending.clear();failures.clear();}}
}
