package io.github.xiaofengzhou.qupath.mrxs;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CancellationException;
import com.google.gson.*;
import qupath.lib.io.GsonTools;
import qupath.lib.roi.interfaces.ROI;

/** Read-only, bounded catalog. Structural completeness is not scientific validity. */
final class IfAnalysisHistory {
    record Entry(Path directory, String imageName, String createdAt, int proposedCells,
                 int measuredCells, String sourceStatus, IfAnalysisProfile profile, ROI roi) {}
    record Rejected(Path directory,String reason) {}
    record Catalog(List<Entry> entries,List<Rejected> rejected) {}
    private static final int MAX_CHILDREN=10000;
    private static final long MAX_JSON_BYTES=2L*1024*1024;

    static Catalog scan(Path selected,BooleanSupplier cancelled) throws IOException {
        Path root=selected.toRealPath();
        if(!Files.isDirectory(root))throw new IOException("Select an analysis directory or its immediate parent");
        List<Path> candidates;
        if(isRunMarker(root.resolve("manifest.json")) || isRunMarker(root.resolve("INCOMPLETE"))) candidates=List.of(root);
        else try(var stream=Files.list(root)) {
            candidates=stream.limit(MAX_CHILDREN+1L).toList();
            if(candidates.size()>MAX_CHILDREN)throw new IOException("History folder exceeds 10000 immediate entries; choose a narrower folder");
            candidates=candidates.stream().filter(p->Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))
                    .sorted().toList();
        }
        var entries=new ArrayList<Entry>();var rejected=new ArrayList<Rejected>();
        for(Path path:candidates) {
            if(cancelled.getAsBoolean())throw new CancellationException();
            try {entries.add(read(path));}
            catch(IOException|RuntimeException e){rejected.add(new Rejected(path,e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));}
        }
        return new Catalog(List.copyOf(entries),List.copyOf(rejected));
    }

    private static boolean isRunMarker(Path path) {
        // Case-insensitive filesystems may contain a child folder named "incomplete".
        return Files.exists(path,LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS);
    }

    private static Entry read(Path directory) throws IOException {
        if(Files.exists(directory.resolve("INCOMPLETE")))throw new IOException("Incomplete/cancelled run");
        var manifest=readJson(directory.resolve("manifest.json")).getAsJsonObject();
        if(manifest.get("schemaVersion").getAsInt()!=1 || !"complete".equals(manifest.get("status").getAsString())
                || !"raw-tile-if-v1".equals(manifest.get("algorithm").getAsString()))
            throw new IOException("Unsupported or unfinished analysis manifest");
        for(String file:List.of("combinations.csv","cells.csv","excluded-cells.csv","cells.geojson","excluded-cells.geojson"))
            if(!Files.isRegularFile(directory.resolve(file),LinkOption.NOFOLLOW_LINKS))throw new IOException("Missing regular file: "+file);
        var profile=GsonTools.getInstance().fromJson(readJson(directory.resolve("profile.json")),IfAnalysisProfile.class);
        var embedded=GsonTools.getInstance().fromJson(manifest.get("profile"),IfAnalysisProfile.class);
        if(profile==null || !profile.equals(embedded))throw new IOException("Profile file/manifest mismatch");
        var roiJson=readJson(directory.resolve("roi.json"));
        var roi=GsonTools.getInstance().fromJson(roiJson,ROI.class);
        if(roi!=null)roi=IfGeometryExport.read(roiJson.getAsJsonObject(),roi);
        if(roi==null || !roi.isArea() || roi.isEmpty())throw new IOException("Missing nonempty area ROI");
        int proposed=manifest.get("inputCells").getAsInt(),measured=manifest.get("eligibleCells").getAsInt();
        long excluded=(long)manifest.get("excludedCells").getAsInt()+manifest.get("unmeasurableCells").getAsInt();
        if(excluded<0 || excluded>Integer.MAX_VALUE)throw new IOException("Invalid exclusion count");
        IfMeasurementCoverage.of(proposed,measured,(int)excluded);
        if(manifest.has("areaMasks") && manifest.getAsJsonObject("areaMasks").has("exported")
                && manifest.getAsJsonObject("areaMasks").get("exported").getAsBoolean())
            readJson(directory.resolve("area-masks/manifest.json"));
        String sourceStatus=manifest.has("source")?manifest.getAsJsonObject("source").get("status").getAsString():"legacy_source_unknown";
        return new Entry(directory,manifest.get("imageName").getAsString(),manifest.get("createdAt").getAsString(),
                proposed,measured,sourceStatus,profile,roi);
    }

    private static JsonElement readJson(Path path) throws IOException {
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>MAX_JSON_BYTES)
            throw new IOException("Missing, linked or oversized JSON: "+path.getFileName());
        try(var input=Files.newInputStream(path)) {
            byte[] bytes=input.readNBytes((int)MAX_JSON_BYTES+1);
            if(bytes.length>MAX_JSON_BYTES)throw new IOException("JSON exceeded size limit");
            return JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    static String format(Catalog catalog) {
        var text=new StringBuilder("Structurally complete runs: "+catalog.entries().size()+"; skipped: "+catalog.rejected().size()+"\n"
                +"Read-only inventory, not pixel/content/CSV-integrity or biological validation. Source locator metadata is NOT a content fingerprint.\n\n");
        for(var e:catalog.entries())text.append(e.createdAt()).append(" | ").append(e.imageName())
                .append(" | measured ").append(e.measuredCells()).append('/').append(e.proposedCells())
                .append(" | ").append(e.sourceStatus()).append("\n").append(e.directory()).append("\n")
                .append("ROI: ").append(e.roi().getBoundsX()).append(',').append(e.roi().getBoundsY())
                .append("; level ").append(e.profile().resolutionLevel()).append("; markers ")
                .append(e.profile().markers().stream().map(IfAnalysisProfile.Marker::name).toList()).append("\n\n");
        for(var r:catalog.rejected())text.append("SKIPPED ").append(r.directory()).append(": ").append(r.reason()).append('\n');
        return text.toString();
    }
}
