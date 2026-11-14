package io.github.xiaofengzhou.qupath.mrxs;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import com.google.gson.*;
import qupath.lib.io.GsonTools;
import qupath.lib.roi.interfaces.ROI;

/** Bounded byte snapshots; refuses declared but broken associations instead of guessing another image. */
final class IfReportFigureInput {
    record Figure(byte[] png,int width,int height,String sha256,String caption) {}
    static Figure load(IfBatchReportInput.Run run) throws IOException {
        Path root=run.entry().directory();
        byte[] analysis=read(root.resolve("manifest.json"),2*1024*1024);
        if(!hash(analysis).equals(run.manifestSha256()))throw new IOException("Analysis changed before figure preparation");
        try {
            var source=json(analysis).getAsJsonObject("source");
            if(source==null||!source.has("reportFigure"))return null;
            var ref=source.getAsJsonObject("reportFigure");
            if(Set.of("not_requested","unavailable").contains(ref.get("status").getAsString()))return null;
            if(!"exported".equals(ref.get("status").getAsString())||!"report-figure/manifest.json".equals(ref.get("manifest").getAsString()))throw new IOException("Unsupported figure reference");
            Path directory=root.resolve("report-figure");
            if(Files.isSymbolicLink(directory)||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)||Files.exists(directory.resolve("INCOMPLETE")))throw new IOException("Incomplete or linked figure directory");
            byte[] metadata=read(directory.resolve("manifest.json"),2*1024*1024);
            if(!hash(metadata).equals(ref.get("manifestSha256").getAsString()))throw new IOException("Figure manifest hash mismatch");
            byte[] roiBytes=read(directory.resolve("roi.json"),2*1024*1024);
            if(!ref.has("roiSha256")||!hash(roiBytes).equals(ref.get("roiSha256").getAsString()))throw new IOException("Figure lacks matching ROI fingerprint; regenerate analysis preview");
            var roi=GsonTools.getInstance().fromJson(new String(roiBytes,java.nio.charset.StandardCharsets.UTF_8),ROI.class);
            var expected=run.entry().roi();
            if(roi==null||roi.getZ()!=expected.getZ()||roi.getT()!=expected.getT()||!roi.getGeometry().equalsExact(expected.getGeometry()))throw new IOException("Figure ROI mismatch");
            var manifest=json(metadata);
            if(manifest.get("schemaVersion").getAsInt()!=1||!"complete".equals(manifest.get("status").getAsString())
                    ||!run.entry().imageName().equals(manifest.get("imageName").getAsString())
                    ||manifest.get("sourceZ").getAsInt()!=expected.getZ()||manifest.get("sourceT").getAsInt()!=expected.getT())throw new IOException("Figure source metadata mismatch");
            var outputs=manifest.getAsJsonArray("outputs");if(outputs.size()!=1)throw new IOException("Expected one report composite");
            var item=outputs.get(0).getAsJsonObject();String name=item.get("figureFile").getAsString();
            if(!name.matches("[0-9]{3}-mask-[0-9]+-figure\\.png"))throw new IOException("Unsafe figure filename");
            byte[] png=read(directory.resolve(name),16*1024*1024);String hash=hash(png);
            if(!hash.equals(item.get("figureSha256").getAsString()))throw new IOException("Figure PNG hash mismatch");
            int width=item.get("figureWidth").getAsInt(),height=item.get("figureHeight").getAsInt();
            if(width<1||height<1||(long)width*height>8_388_608)throw new IOException("Figure dimensions exceed report bounds");
            // Inspect dimensions before allocating a decoded raster; still verify PNG can fully decode.
            try(var input=new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
                var readers=javax.imageio.ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("Unreadable figure PNG");
                var reader=readers.next();try {reader.setInput(input);
                    if(!reader.getFormatName().equalsIgnoreCase("png")||reader.getWidth(0)!=width||reader.getHeight(0)!=height)throw new IOException("PNG dimensions/type mismatch");
                    var image=reader.read(0);if(image==null)throw new IOException("PNG decode failed");image.flush();
                }finally{reader.dispose();}
            }
            String caption="Source-derived ROI context; native level "+manifest.getAsJsonObject("options").get("level")
                    +"; channels "+item.get("channels")+". Display composite, not raw quantitative data or full-resolution publication figure. PNG SHA256: "+hash;
            return new Figure(png,width,height,hash,caption);
        }catch(RuntimeException e){throw new IOException("Invalid associated report figure",e);}
    }
    private static JsonObject json(byte[] bytes){return JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();}
    private static byte[] read(Path path,int max) throws IOException {
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>max)throw new IOException("Missing, linked or oversized report asset: "+path);
        try(var in=Files.newInputStream(path)){byte[] bytes=in.readNBytes(max+1);if(bytes.length>max)throw new IOException("Report asset grew beyond limit");return bytes;}
    }
    private static String hash(byte[] data){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}}
}
