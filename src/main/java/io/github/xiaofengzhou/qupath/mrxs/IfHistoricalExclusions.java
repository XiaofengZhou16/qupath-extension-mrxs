package io.github.xiaofengzhou.qupath.mrxs;

import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;
import com.google.gson.*;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.PathObject;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.images.ImageData;
import qupath.lib.regions.ImageRegion;
import java.awt.image.BufferedImage;

/** Exclusions remain review records, never detections or negative phenotypes. */
final class IfHistoricalExclusions implements qupath.lib.gui.viewer.overlays.PathOverlay {
    record Item(String id,String stage,String reason,ROI cell,ROI nucleus) {
        ROI target(){return cell!=null?cell:nucleus;}
    }
    private final List<Item> items;
    private final ImageData<BufferedImage> source;
    private final org.locationtech.jts.index.strtree.STRtree tree=new org.locationtech.jts.index.strtree.STRtree();
    private volatile String reason;
    private int cursor=-1;
    private List<Item> navigableItems=List.of();
    private final List<Item> allNavigable;
    private final Map<String,List<Item>> byReason;
    private final List<String> reasonNames;
    private final int drawableCount;
    private record Outline(String reason,ROI roi,java.awt.Shape shape) {}
    IfHistoricalExclusions(List<Item> items,ImageData<BufferedImage> source) {
        this(items,source,()->false);
    }
    IfHistoricalExclusions(List<Item> items,ImageData<BufferedImage> source,BooleanSupplier cancelled) {
        check(cancelled);
        this.items=List.copyOf(items);this.source=source;
        var navigable=new ArrayList<Item>();var groups=new HashMap<String,List<Item>>();
        var names=new TreeSet<String>();int drawable=0;
        for(var item:this.items) {
            check(cancelled);names.add(item.reason());
            if(item.target()!=null)drawable++;
            if(navigable(item.target())) {
                navigable.add(item);groups.computeIfAbsent(item.reason(),key->new ArrayList<>()).add(item);
            }
            for(var roi:new ROI[]{item.cell(),item.nucleus()})if(roi!=null) {
                check(cancelled);
                tree.insert(roi.getGeometry().getEnvelopeInternal(),new Outline(item.reason(),roi,qupath.lib.roi.RoiTools.getShape(roi)));
            }
        }
        check(cancelled);tree.build();check(cancelled);
        // Private lists are never mutated after construction; filtering only swaps references.
        allNavigable=Collections.unmodifiableList(navigable);byReason=Collections.unmodifiableMap(groups);
        reasonNames=List.copyOf(names);drawableCount=drawable;filter(null);
    }
    List<String> reasons(){return reasonNames;}
    void filter(String reason){this.reason=reason;cursor=-1;navigableItems=reason==null?allNavigable:byReason.getOrDefault(reason,List.of());}
    int total(){return items.size();}
    boolean isFor(ImageData<BufferedImage> data){return data==source;}
    int drawable(){return drawableCount;}
    Item move(int direction) {
        if(direction!=1&&direction!=-1)throw new IllegalArgumentException("Direction must be +/-1");
        var selected=navigableItems;
        if(selected.isEmpty())return null;cursor=cursor<0?(direction>0?0:selected.size()-1):Math.floorMod(cursor+direction,selected.size());return selected.get(cursor);
    }
    private boolean navigable(ROI r){return r!=null&&r.getZ()>=0&&r.getZ()<source.getServer().nZSlices()&&r.getT()>=0&&r.getT()<source.getServer().nTimepoints()
            &&r.getCentroidX()>=0&&r.getCentroidY()>=0&&r.getCentroidX()<source.getServer().getWidth()&&r.getCentroidY()<source.getServer().getHeight();}
    @Override public void paintOverlay(java.awt.Graphics2D g,ImageRegion region,double ds,ImageData<BufferedImage> data,boolean complete) {
        if(data!=source)return;var copy=(java.awt.Graphics2D)g.create();try {
            float w=(float)Math.max(0.5,ds);copy.setColor(java.awt.Color.ORANGE);copy.setStroke(new java.awt.BasicStroke(w,0,0,10,new float[]{4*w,3*w},0));
            for(var obj:tree.query(IfOverlayViewport.bounds(g,region))) {var o=(Outline)obj;if(o.roi().getZ()==region.getZ()&&o.roi().getT()==region.getT()&&(reason==null||reason.equals(o.reason())))copy.draw(o.shape());}
        }finally{copy.dispose();}
    }
    static List<Item> load(IfAnalysisHistory.Entry entry,BooleanSupplier cancelled)throws IOException {
        check(cancelled);Path root=entry.directory();byte[] csv=read(root.resolve("excluded-cells.csv")),geo=read(root.resolve("excluded-cells.geojson")),manifestBytes=read(root.resolve("manifest.json"));
        try {
            var manifest=JsonParser.parseString(new String(manifestBytes,StandardCharsets.UTF_8)).getAsJsonObject();
            if(!entry.createdAt().equals(manifest.get("createdAt").getAsString())||!entry.profile().equals(GsonTools.getInstance().fromJson(manifest.get("profile"),IfAnalysisProfile.class)))throw new IOException("Exclusion run changed during load");
            var rows=IfReportCsv.parse(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(csv)).toString(),200001,3);
            if(rows.isEmpty()||!rows.getFirst().equals(List.of("cell_id","stage","first_failure_reason"))||rows.size()-1!=entry.proposedCells()-entry.measuredCells())throw new IOException("Excluded-cell table/count mismatch");
            var records=new LinkedHashMap<String,Item>();
            for(var row:rows.subList(1,rows.size())) {check(cancelled);if(row.size()!=3||row.stream().anyMatch(String::isBlank)||records.put(row.get(0),new Item(row.get(0),row.get(1),row.get(2),null,null))!=null)throw new IOException("Invalid/duplicate exclusion record");}
            var collection=JsonParser.parseString(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(geo)).toString()).getAsJsonObject();
            if(!"FeatureCollection".equals(collection.get("type").getAsString()))throw new IOException("Invalid excluded GeoJSON");
            var features=collection.getAsJsonArray("features");if(features.size()>2L*records.size()||features.size()!=manifest.get("excludedReviewGeometryFeatures").getAsInt())throw new IOException("Excluded geometry count differs from manifest");
            var seen=new HashSet<String>();
            for(var feature:features){check(cancelled);
                var metadataFeature=feature;
                var sourceGeometry=feature.getAsJsonObject().getAsJsonObject("geometry");
                if(sourceGeometry!=null && sourceGeometry.has("ifCoordinatePrecision")
                        && sourceGeometry.has("ifRoiClass") && "PolygonROI".equals(sourceGeometry.get("ifRoiClass").getAsString())) {
                    // Deserialize metadata/plane without letting QuPath repair or reject a
                    // deliberately retained invalid review ring. Restore the validated raw
                    // coordinate representation below, never this temporary geometry.
                    metadataFeature=feature.deepCopy();
                    var placeholder=metadataFeature.getAsJsonObject().getAsJsonObject("geometry");
                    placeholder.addProperty("type","Polygon");
                    placeholder.add("coordinates",JsonParser.parseString("[[[0,0],[1,0],[1,1],[0,0]]]"));
                }
                var object=GsonTools.getInstance().fromJson(metadataFeature,PathObject.class);
                if(!object.isAnnotation())throw new IOException("Exclusion geometry must be a review annotation");
                var metadata=object.getMetadata();String id=metadata.get("IF source cell ID"),compartment=metadata.get("IF review compartment");
                if(id==null||compartment==null||!Set.of("cell","nucleus").contains(compartment)||!seen.add(id+"/"+compartment))throw new IOException("Invalid/duplicate review compartment");
                String key=protect(id);var item=records.get(key);
                if(item==null||!item.stage().equals(protect(metadata.get("IF exclusion stage")))||!item.reason().equals(protect(metadata.get("IF exclusion reason"))))throw new IOException("Exclusion ID/reason/geometry mismatch");
                ROI roi=object.getROI();
                if(roi!=null)roi=IfGeometryExport.read(feature.getAsJsonObject().getAsJsonObject("geometry"),roi);
                if(roi==null||roi.isEmpty()||!Double.isFinite(roi.getBoundsX())||!Double.isFinite(roi.getBoundsY())||!Double.isFinite(roi.getBoundsWidth())||!Double.isFinite(roi.getBoundsHeight()))throw new IOException("Invalid excluded review geometry");
                records.put(key,new Item(id,item.stage(),item.reason(),compartment.equals("cell")?roi:item.cell(),compartment.equals("nucleus")?roi:item.nucleus()));
            }
            if(records.values().stream().filter(i->i.target()!=null).count()!=manifest.get("excludedCellsWithReviewGeometry").getAsLong())throw new IOException("Drawable exclusion count differs from manifest");
            if(!Arrays.equals(csv,read(root.resolve("excluded-cells.csv")))||!Arrays.equals(geo,read(root.resolve("excluded-cells.geojson")))||!Arrays.equals(manifestBytes,read(root.resolve("manifest.json"))))throw new IOException("Exclusions changed during loading");
            return List.copyOf(records.values());
        }catch(java.util.concurrent.CancellationException e){throw e;}catch(RuntimeException e){throw new IOException("Invalid historical exclusions",e);}
    }
    private static String protect(String s){if(s==null)return null;return !s.isEmpty()&&"=+-@\t\r".indexOf(s.charAt(0))>=0?"'"+s:s;}
    private static byte[] read(Path p)throws IOException {int limit=32*1024*1024;if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)>limit)throw new IOException("Missing, linked or oversized exclusions");try(var in=Files.newInputStream(p)){byte[] bytes=in.readNBytes(limit+1);if(bytes.length>limit)throw new IOException("Exclusion data too large");return bytes;}}
    private static void check(BooleanSupplier c){if(c.getAsBoolean())throw new java.util.concurrent.CancellationException();}
}
