package io.github.xiaofengzhou.qupath.mrxs;

import qupath.lib.io.GsonTools;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;

/** Lossless bitmask tiles streamed during the raw analysis pass. Constant-sized pixel buffers. */
final class IfAreaMasks implements IfAnalysis.MaskSink, AutoCloseable {
    record Tile(String file, int levelX, int levelY, int width, int height, long roiPixels, String sha256) {}
    private final Path directory;
    private final BufferedWriter index;
    private final BooleanSupplier cancelled;
    private final long[] histogram;
    private long pixels;
    private int tiles;
    private boolean closed;
    private final java.util.Set<Long> coordinates=new java.util.HashSet<>();
    private final Map<String,String> constantFiles=new java.util.HashMap<>();
    private int pngFiles;

    IfAreaMasks(Path directory, int markers, BooleanSupplier cancelled) throws IOException {
        if(markers<1 || markers>8)throw new IllegalArgumentException("Masks support 1–8 markers");
        this.directory=directory;this.cancelled=cancelled;histogram=new long[1<<markers];
        Files.createDirectory(directory);
        index=Files.newBufferedWriter(directory.resolve("tiles.jsonl"));
    }

    @Override public void accept(int levelX,int levelY,BufferedImage labels) throws IOException {
        check();
        if(closed)throw new IOException("Mask writer already closed");
        var raster=labels.getRaster();
        if(levelX<0 || levelY<0 || raster.getNumBands()!=1 || raster.getDataBuffer().getDataType()!=DataBuffer.TYPE_USHORT)
            throw new IOException("Expected unsigned 16-bit native mask tile");
        if(!coordinates.add(((long)levelX<<32)|(levelY&0xffffffffL)))throw new IOException("Duplicate mask tile coordinates");
        int firstCode=raster.getSample(0,0,0);boolean constant=true;
        long count=0;
        for(int y=0;y<labels.getHeight();y++) {
            check();
            for(int x=0;x<labels.getWidth();x++) {
                int code=raster.getSample(x,y,0);
                if(code!=firstCode)constant=false;
                if(code>histogram.length)throw new IOException("Mask code exceeds selected marker count");
                if(code>0){histogram[code-1]++;count++;}
            }
        }
        writeTile(levelX,levelY,labels.getWidth(),labels.getHeight(),labels,count,constant,firstCode);
    }

    @Override public void acceptConstant(int levelX,int levelY,int width,int height,int code)throws IOException {
        check();if(closed)throw new IOException("Mask writer already closed");
        if(levelX<0||levelY<0||width<1||height<1||(long)width*height>4_194_304L||code<0||code>histogram.length)
            throw new IOException("Invalid constant mask tile");
        if(!coordinates.add(((long)levelX<<32)|(levelY&0xffffffffL)))throw new IOException("Duplicate mask tile coordinates");
        long count=code==0?0:(long)width*height;
        if(code>0)histogram[code-1]+=count;
        writeTile(levelX,levelY,width,height,null,count,true,code);
    }

    private void writeTile(int levelX,int levelY,int width,int height,BufferedImage labels,long count,boolean constant,int firstCode)throws IOException {
        String name=constant?"constant-"+width+"-"+height+"-"+firstCode+".png"
                :"tile-"+levelX+"-"+levelY+".png";
        Path path=directory.resolve(name);
        // Duplicate coordinates are a failure, not an overwrite. Keep partial output marked incomplete.
        String sha=constant?constantFiles.get(name):null;
        if(sha==null) {
            if(labels==null) {
                labels=new BufferedImage(width,height,BufferedImage.TYPE_USHORT_GRAY);
                java.util.Arrays.fill(((java.awt.image.DataBufferUShort)labels.getRaster().getDataBuffer()).getData(),(short)firstCode);
            }
            try(var out=Files.newOutputStream(path,java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE)) {
                if(!ImageIO.write(labels,"PNG",out))throw new IOException("PNG writer unavailable");
            }
            check();sha=digest(path);pngFiles++;
            if(constant)constantFiles.put(name,sha);
        }
        check();
        index.write(GsonTools.getInstance().toJson(new Tile(name,levelX,levelY,width,height,count,sha)));
        index.newLine();pixels+=count;tiles++;
    }

    Map<String,Object> finish(IfAnalysis.Result result,int z,int t) throws IOException {
        check();close();
        if(tiles!=result.tilesRead()+result.zeroTilesCounted() || pixels!=result.roiPixels())throw new IOException("Mask coverage differs from analyzed pixels/tiles");
        long[] inclusive=IfAnalysis.inclusive(histogram);
        for(var c:result.combinations())if(c.paPixels()!=inclusive[c.bits()] || c.paExactPixels()!=histogram[c.bits()])
            throw new IOException("Mask counts differ from PA statistics: "+c.markers());
        var metadata=new LinkedHashMap<String,Object>();
        metadata.put("schemaVersion",2);metadata.put("status","complete");metadata.put("encoding","uint16_png_bitmask_plus_one_shared_constants");
        metadata.put("uniquePngFiles",pngFiles);metadata.put("sharedConstantPngFiles",constantFiles.size());
        metadata.put("profile",result.profile());metadata.put("downsample",result.downsample());
        metadata.put("sourceZ",z);metadata.put("sourceT",t);
        metadata.put("sourcePixelWidthMicrons",finite(result.pixelWidthMicrons()));
        metadata.put("sourcePixelHeightMicrons",finite(result.pixelHeightMicrons()));
        metadata.put("indexFile","tiles.jsonl");metadata.put("indexSha256",digest(directory.resolve("tiles.jsonl")));
        metadata.put("tiles",tiles);metadata.put("roiPixels",pixels);metadata.put("exactHistogramIncludingNegative",histogram);
        metadata.put("definitions",List.of(
                "Code 0 = outside sampled ROI, including holes; code 1 = sampled ROI with no selected marker positive",
                "Codes 1..256: positivityBits = code - 1; bit j maps to profile.markers[j], not source channel index",
                "Raw intensity >= pixelThreshold defines positivity; no display LUT/gamma/AI is used for PA",
                "Inclusive AB: code > 0 and (positivityBits & requestedBits) == requestedBits; exact AB: positivityBits == requestedBits",
                "Source pixel center = ((levelX + x + 0.5) * downsample, (levelY + y + 0.5) * downsample)",
                "ROI membership uses Java2D Shape.contains; rectangular raster boundaries are half-open (left/top included, right/bottom excluded)",
                "Tile dimensions and origins are level coordinates; PNG is a numeric label mask, not a pseudocolor figure",
                "Schema 2: spatially distinct constant tiles may reference the same lossless constant-W-H-code.png; retain every index row and its origin",
                "Index entries are sparse: absent tiles are not part of this exported ROI; do not infer tissue area",
                "Use only when parent analysis manifest is complete and parent INCOMPLETE is absent; pixel overlap is not molecular interaction"));
        Files.writeString(directory.resolve("manifest.json"),GsonTools.getInstance(true).newBuilder().serializeNulls().create().toJson(metadata));
        return Map.of("exported",true,"manifest","area-masks/manifest.json","manifestSha256",digest(directory.resolve("manifest.json")),
                "tiles",tiles,"roiPixels",pixels,"countsVerifiedAgainstStatistics",true);
    }

    static boolean matches(int code,int requiredMask,boolean exact) {
        if(code==0)return false;
        int bits=code-1;
        return requiredMask==0 ? bits!=0 : exact ? bits==requiredMask : (bits&requiredMask)==requiredMask;
    }
    private void check(){if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())throw new CancellationException();}
    private static Double finite(double value){return Double.isFinite(value)?value:null;}
    static String digest(Path file) throws IOException {
        try {
            var sha=MessageDigest.getInstance("SHA-256");
            try(var input=Files.newInputStream(file)){byte[] buffer=new byte[65536];for(int n;(n=input.read(buffer))>=0;)sha.update(buffer,0,n);}
            return HexFormat.of().formatHex(sha.digest());
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    @Override public void close() throws IOException {if(!closed){closed=true;index.close();}}
}
