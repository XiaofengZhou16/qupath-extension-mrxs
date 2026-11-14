package io.github.xiaofengzhou.qupath.mrxs;

import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;
import com.google.gson.*;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathCellObject;

/** Reads measured cells only. Geometry/table agreement is not biological truth or source authentication. */
final class IfHistoricalCells {
    record Loaded(List<IfAnalysis.Cell> cells,Map<String,Integer> pnMasks) {}
    private record Counts(int npn,int cpn,int pn,double x,double y) {}
    static Loaded load(IfAnalysisHistory.Entry entry,BooleanSupplier cancelled)throws IOException {
        check(cancelled);Path path=entry.directory();
        byte[] csv=read(path.resolve("cells.csv")),geo=read(path.resolve("cells.geojson"));
        try {
            var rows=IfReportCsv.parse(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(csv)).toString(),200001,24);
            var header=new ArrayList<>(List.of("cell_id","x_source_px","y_source_px","nucleus_sample_pixels","cytoplasm_sample_pixels","NPN_mask","CPN_mask","PN_mask"));
            for(var m:entry.profile().markers()){header.add(protectedText(m.name()+":nucleus_mean"));header.add(protectedText(m.name()+":cytoplasm_mean"));}
            if(rows.isEmpty()||!rows.getFirst().equals(header)||rows.size()-1!=entry.measuredCells())throw new IOException("Historical cell header/count mismatch");
            int max=(1<<entry.profile().markers().size())-1;var counts=new HashMap<String,Counts>();
            for(var row:rows.subList(1,rows.size())) {
                check(cancelled);if(row.size()!=header.size()||row.getFirst().isBlank())throw new IOException("Invalid cell row");
                int n=mask(row.get(5),max),c=mask(row.get(6),max),p=mask(row.get(7),max);
                if(p!=(n|c)||new java.math.BigDecimal(row.get(3)).longValueExact()<1||new java.math.BigDecimal(row.get(4)).longValueExact()<1)throw new IOException("Invalid historical cell compartment/masks");
                for(int i=8;i<row.size();i++)finite(row.get(i));
                if(counts.put(row.getFirst(),new Counts(n,c,p,finite(row.get(1)),finite(row.get(2))))!=null)throw new IOException("Duplicate historical cell ID");
            }
            var json=JsonParser.parseString(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(geo)).toString()).getAsJsonObject();
            if(!"FeatureCollection".equals(json.get("type").getAsString()))throw new IOException("Expected GeoJSON FeatureCollection");
            var features=json.getAsJsonArray("features");if(features.size()!=entry.measuredCells())throw new IOException("Geometry count differs from measured cells");
            var cells=new ArrayList<IfAnalysis.Cell>();var masks=new HashMap<String,Integer>();
            var region=org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(entry.roi().getGeometry());
            for(var feature:features) {
                check(cancelled);var object=GsonTools.getInstance().fromJson(feature,PathObject.class);
                if(!(object instanceof PathCellObject cell)||cell.getROI()==null||cell.getNucleusROI()==null)throw new IOException("Historical geometry is not a complete cell");
                var cellROI=IfGeometryExport.read(feature.getAsJsonObject().getAsJsonObject("geometry"),cell.getROI());
                var nucleusROI=IfGeometryExport.read(feature.getAsJsonObject().getAsJsonObject("nucleusGeometry"),cell.getNucleusROI());
                String id=cell.getMetadata().get("IF source cell ID");if(id==null||id.isBlank()||masks.containsKey(id))throw new IOException("Missing/duplicate cell identity");
                var value=counts.remove(protectedText(id));if(value==null)throw new IOException("Cell geometry/table ID mismatch");
                for(var roi:List.of(cellROI,nucleusROI)) {
                    if(!roi.isArea()||roi.isEmpty()||roi.getZ()!=entry.roi().getZ()||roi.getT()!=entry.roi().getT()
                            ||!roi.getGeometry().isValid()||!region.covers(roi.getGeometry()))throw new IOException("Historical cell outside ROI/plane or invalid geometry");
                }
                if(Math.abs(nucleusROI.getCentroidX()-value.x())>1e-5||Math.abs(nucleusROI.getCentroidY()-value.y())>1e-5)throw new IOException("Nuclear centroid/table mismatch for "+id+": geometry="+nucleusROI.getCentroidX()+","+nucleusROI.getCentroidY()+" table="+value.x()+","+value.y());
                if(cell.getMeasurementList().get("IF: PN mask")!=value.pn()||cell.getMeasurementList().get("IF: NPN mask")!=value.npn()||cell.getMeasurementList().get("IF: CPN mask")!=value.cpn())throw new IOException("Geometry phenotype/table mismatch");
                cells.add(new IfAnalysis.Cell(id,cellROI,nucleusROI));masks.put(id,value.pn());
            }
            if(!counts.isEmpty())throw new IOException("Missing historical geometries");
            if(!Arrays.equals(csv,read(path.resolve("cells.csv")))||!Arrays.equals(geo,read(path.resolve("cells.geojson"))))throw new IOException("Historical cell files changed during loading");
            check(cancelled);return new Loaded(List.copyOf(cells),Map.copyOf(masks));
        }catch(java.util.concurrent.CancellationException e){throw e;}
        catch(RuntimeException e){throw new IOException("Invalid historical cell data",e);}
    }
    private static int mask(String s,int max)throws IOException {int value=new java.math.BigDecimal(s).intValueExact();if(value<0||value>max)throw new IOException("Invalid phenotype mask");return value;}
    private static double finite(String s)throws IOException {double d=Double.parseDouble(s);if(!Double.isFinite(d))throw new IOException("Nonfinite cell measurement");return d;}
    private static String protectedText(String s){return !s.isEmpty()&&"=+-@\t\r".indexOf(s.charAt(0))>=0?"'"+s:s;}
    private static byte[] read(Path p)throws IOException {
        int limit=32*1024*1024;if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)>limit)throw new IOException("Missing, linked or oversized historical cells");
        try(var in=Files.newInputStream(p)){byte[] b=in.readNBytes(limit+1);if(b.length>limit)throw new IOException("Historical cell input too large");return b;}
    }
    private static void check(BooleanSupplier c){if(c.getAsBoolean())throw new java.util.concurrent.CancellationException();}
}
