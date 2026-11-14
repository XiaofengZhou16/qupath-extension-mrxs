package io.github.xiaofengzhou.qupath.mrxs;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import com.google.gson.*;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.io.GsonTools;

/** Conservative locator/metadata gate, not a claim that historical source pixels are unchanged. */
final class IfHistorySource {
    record Current(List<String> uris,String serverClass,int width,int height,int zSlices,int timepoints,
                   List<String> channels,double pixelWidth,double pixelHeight,List<Double> downsamples) {}
    static Current capture(ImageServer<?> server) {
        return new Current(server.getURIs().stream().map(Object::toString).sorted().toList(),server.getClass().getName(),
                server.getWidth(),server.getHeight(),server.nZSlices(),server.nTimepoints(),
                server.getMetadata().getChannels().stream().map(c->c.getName()).toList(),
                server.getPixelCalibration().getPixelWidthMicrons(),server.getPixelCalibration().getPixelHeightMicrons(),
                java.util.stream.IntStream.range(0,server.nResolutions()).mapToObj(server::getDownsampleForResolution).toList());
    }
    static IfAnalysisHistory.Entry validate(Path directory,Current current,BooleanSupplier cancelled) throws IOException {
        var catalog=IfAnalysisHistory.scan(directory,cancelled);Path path=directory.toRealPath();
        if(catalog.entries().size()!=1||!catalog.rejected().isEmpty()||!catalog.entries().getFirst().directory().equals(path))throw new IOException("Select one complete analysis run");
        var entry=catalog.entries().getFirst();Path file=path.resolve("manifest.json");
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>2*1024*1024)throw new IOException("Invalid analysis manifest");
        byte[] bytes;try(var in=Files.newInputStream(file)){bytes=in.readNBytes(2*1024*1024+1);}
        if(bytes.length>2*1024*1024)throw new IOException("Manifest too large");
        try {
            var m=JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            var source=m.getAsJsonObject("source");
            if(source==null||!"locator_metadata_only".equals(source.get("status").getAsString()))throw new IOException("Historical run lacks source locator metadata; cannot locate safely");
            var uris=new ArrayList<String>();source.getAsJsonArray("uris").forEach(u->uris.add(u.getAsString()));Collections.sort(uris);
            if(uris.isEmpty()||!uris.equals(current.uris()))throw new IOException("Source URI mismatch (renamed/moved files are not automatically matched)");
            if(source.has("fileFingerprint")) {
                var saved=GsonTools.getInstance().fromJson(source.get("fileFingerprint"),IfSourceFingerprint.Snapshot.class);
                var actual=IfSourceFingerprint.capture(current.uris(),cancelled);
                if(saved==null || actual==null || !saved.equals(actual))
                    throw new IOException("Source file fingerprint mismatch; historical analysis cannot be safely replayed");
            }
            var channels=new ArrayList<String>();source.getAsJsonArray("channels").forEach(c->channels.add(c.getAsString()));
            if(!source.get("serverClass").getAsString().equals(current.serverClass())||source.get("width").getAsInt()!=current.width()
                    ||source.get("height").getAsInt()!=current.height()||!channels.equals(current.channels()))throw new IOException("Source reader, dimensions or channel mapping mismatch");
            if(!entry.profile().equals(GsonTools.getInstance().fromJson(m.get("profile"),IfAnalysisProfile.class))
                    ||!entry.createdAt().equals(m.get("createdAt").getAsString()))throw new IOException("Run changed during source validation");
            int level=entry.profile().resolutionLevel();
            if(level>=current.downsamples().size()||!same(m.get("downsample"),current.downsamples().get(level))
                    ||!same(m.get("pixelWidthMicrons"),current.pixelWidth())||!same(m.get("pixelHeightMicrons"),current.pixelHeight()))throw new IOException("Source pyramid/calibration mismatch");
            var r=entry.roi();
            if(r.getZ()<0||r.getT()<0||r.getZ()>=current.zSlices()||r.getT()>=current.timepoints()
                    ||!Double.isFinite(r.getBoundsX())||!Double.isFinite(r.getBoundsY())||!Double.isFinite(r.getBoundsWidth())||!Double.isFinite(r.getBoundsHeight())
                    ||r.getBoundsWidth()<=0||r.getBoundsHeight()<=0||r.getBoundsX()<0||r.getBoundsY()<0
                    ||r.getBoundsX()+r.getBoundsWidth()>current.width()||r.getBoundsY()+r.getBoundsHeight()>current.height())throw new IOException("Historical ROI outside current image/plane");
            if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
            return entry;
        }catch(java.util.concurrent.CancellationException e){throw e;}
        catch(RuntimeException e){throw new IOException("Invalid historical source metadata",e);}
    }
    private static boolean same(JsonElement value,double current) {
        if(value==null||value.isJsonNull())return !Double.isFinite(current);
        double saved=value.getAsDouble();return Double.isFinite(saved)&&Double.isFinite(current)&&Math.abs(saved-current)<=1e-9*Math.max(1,Math.abs(saved));
    }
}
