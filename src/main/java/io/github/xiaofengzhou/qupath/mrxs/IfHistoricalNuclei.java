package io.github.xiaofengzhou.qupath.mrxs;

import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;
import com.google.gson.*;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.PathObject;

/** Independently measured nuclear outlines only; never creates cell detections. */
final class IfHistoricalNuclei {
    // Cell records below are drawing adapters: cell shape = nuclear outline, no inner shape.
    record Loaded(List<IfAnalysis.Cell> outlines,Map<String,Integer> masks) {}
    private record Values(double x,double y,int mask) {}
    static Loaded load(IfAnalysisHistory.Entry entry,BooleanSupplier cancelled)throws IOException {
        check(cancelled);Path path=entry.directory();
        byte[] manifest=read(path.resolve("manifest.json")),csv=read(path.resolve("nuclei.csv")),geo=read(path.resolve("nuclei.geojson"));
        try {
            var root=JsonParser.parseString(text(manifest)).getAsJsonObject();
            if(!root.has("nuclearMeasurements"))throw new IOException("This run has no independent nuclear measurements");
            var metadata=root.getAsJsonObject("nuclearMeasurements");
            if(metadata.get("schemaVersion").getAsInt()!=1||!metadata.get("algorithm").getAsString().equals("raw-nucleus-only-v1")
                    ||!metadata.get("denominator").getAsString().equals("all_measured_nuclei"))throw new IOException("Unsupported nuclear supplement");
            int count=integer(metadata.get("measuredNuclei").getAsString()),excluded=integer(metadata.get("excludedNuclei").getAsString());
            if(count>entry.proposedCells()||excluded!=entry.proposedCells()-count||integer(metadata.get("geometryFeatures").getAsString())!=count)
                throw new IOException("Nuclear count/geometry declaration mismatch");
            var header=new ArrayList<>(List.of("cell_id","x_source_px","y_source_px","nucleus_sample_pixels","NPN_nucleus_only_mask"));
            for(var m:entry.profile().markers())header.add(protectedText(m.name()+":nucleus_mean"));
            var rows=IfReportCsv.parse(text(csv),200001,24);
            if(rows.isEmpty()||!rows.getFirst().equals(header)||rows.size()-1!=count)throw new IOException("Nuclear table header/count mismatch");
            var values=new HashMap<String,Values>();int max=(1<<entry.profile().markers().size())-1;
            for(var row:rows.subList(1,rows.size())) {
                check(cancelled);if(row.size()!=header.size()||row.getFirst().isBlank())throw new IOException("Invalid nuclear row");
                int mask=integer(row.get(4));if(mask>max||new java.math.BigDecimal(row.get(3)).longValueExact()<1)throw new IOException("Invalid nuclear mask/pixel count");
                for(int i=5;i<row.size();i++)finite(row.get(i));
                if(values.put(row.getFirst(),new Values(finite(row.get(1)),finite(row.get(2)),mask))!=null)throw new IOException("Duplicate nuclear ID");
            }
            var json=JsonParser.parseString(text(geo)).getAsJsonObject();
            if(!"FeatureCollection".equals(json.get("type").getAsString()))throw new IOException("Expected nuclear FeatureCollection");
            var features=json.getAsJsonArray("features");if(features.size()!=count)throw new IOException("Nuclear geometry count mismatch");
            var outlines=new ArrayList<IfAnalysis.Cell>();var masks=new HashMap<String,Integer>();
            var region=org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(entry.roi().getGeometry());
            for(var feature:features) {
                check(cancelled);var object=GsonTools.getInstance().fromJson(feature,PathObject.class);
                if(!object.isAnnotation()||object.getROI()==null)throw new IOException("Nuclear geometry must be a review annotation");
                var roi=IfGeometryExport.read(feature.getAsJsonObject().getAsJsonObject("geometry"),object.getROI());
                String id=object.getMetadata().get("IF source cell ID");
                if(id==null||id.isBlank()||masks.containsKey(id))throw new IOException("Missing/duplicate nuclear identity");
                var value=values.remove(protectedText(id));if(value==null)throw new IOException("Nuclear geometry/table identity mismatch");
                if(!roi.isArea()||roi.isEmpty()||roi.getZ()!=entry.roi().getZ()||roi.getT()!=entry.roi().getT()
                        ||!IfAnalysis.validBoundary(roi)||!region.covers(roi.getGeometry()))throw new IOException("Nucleus outside ROI/plane or invalid geometry");
                if(Math.abs(roi.getCentroidX()-value.x())>1e-5||Math.abs(roi.getCentroidY()-value.y())>1e-5
                        ||object.getMeasurementList().get("IF: NPN nucleus-only mask")!=value.mask())throw new IOException("Nuclear geometry/measurement mismatch");
                outlines.add(new IfAnalysis.Cell(id,roi,null));masks.put(id,value.mask());
            }
            if(!values.isEmpty())throw new IOException("Nuclear geometry missing");
            if(!Arrays.equals(manifest,read(path.resolve("manifest.json")))||!Arrays.equals(csv,read(path.resolve("nuclei.csv")))
                    ||!Arrays.equals(geo,read(path.resolve("nuclei.geojson"))))throw new IOException("Nuclear inputs changed during loading");
            check(cancelled);return new Loaded(List.copyOf(outlines),Map.copyOf(masks));
        }catch(java.util.concurrent.CancellationException e){throw e;}
        catch(RuntimeException e){throw new IOException("Invalid historical nuclear data",e);}
    }
    private static int integer(String s)throws IOException {int n=new java.math.BigDecimal(s).intValueExact();if(n<0)throw new IOException("Negative nuclear count");return n;}
    private static double finite(String s)throws IOException {double n=Double.parseDouble(s);if(!Double.isFinite(n))throw new IOException("Nonfinite nuclear value");return n;}
    private static String protectedText(String s){return !s.isEmpty()&&"=+-@\t\r".indexOf(s.charAt(0))>=0?"'"+s:s;}
    private static String text(byte[] bytes)throws IOException{return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();}
    private static byte[] read(Path p)throws IOException {
        int limit=32*1024*1024;if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)>limit)throw new IOException("Missing, linked or oversized nuclear input: "+p.getFileName());
        try(var in=Files.newInputStream(p)){byte[] b=in.readNBytes(limit+1);if(b.length>limit)throw new IOException("Nuclear input too large");return b;}
    }
    private static void check(BooleanSupplier c){if(c.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();}
}
