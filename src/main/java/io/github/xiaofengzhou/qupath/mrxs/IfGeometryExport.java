package io.github.xiaofengzhou.qupath.mrxs;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.locationtech.jts.geom.*;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.PathObject;
import qupath.lib.roi.interfaces.ROI;

/** Retains QuPath metadata but avoids its two-decimal GeoJSON coordinate rounding. */
final class IfGeometryExport {
    private static Geometry geometry(ROI roi) {
        if(roi instanceof qupath.lib.roi.GeometryROI)return roi.getGeometry();
        return new qupath.lib.roi.GeometryTools.GeometryConverter.Builder()
                .factory(new GeometryFactory(new PrecisionModel(),0)).build().roiToGeometry(roi);
    }
    static JsonObject roi(ROI roi) {
        var json=JsonParser.parseString(GsonTools.getInstance().toJson(roi,ROI.class)).getAsJsonObject();
        preciseROI(json,roi);
        return json;
    }
    static void objects(Path file,List<? extends PathObject> objects,BooleanSupplier cancelled)throws IOException {
        try(var out=Files.newBufferedWriter(file);var writer=new com.google.gson.stream.JsonWriter(out)) {
            writer.beginObject().name("type").value("FeatureCollection").name("features").beginArray();
            for(var object:objects) {
                if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
                var json=JsonParser.parseString(GsonTools.getInstance().toJson(object,PathObject.class)).getAsJsonObject();
                if(object.getROI()!=null)preciseROI(json.getAsJsonObject("geometry"),object.getROI());
                if(object instanceof qupath.lib.objects.PathCellObject cell && cell.getNucleusROI()!=null)
                    preciseROI(json.getAsJsonObject("nucleusGeometry"),cell.getNucleusROI());
                GsonTools.getInstance().toJson(json,writer);
            }
            writer.endArray().endObject();
        }
    }
    private static void preciseROI(JsonObject json,ROI roi) {
        var g=geometry(roi);precise(json,g);
        json.addProperty("ifRoiClass",roi.getClass().getSimpleName());
        json.addProperty("ifPrecisionScale",g.getPrecisionModel().getScale());
        json.addProperty("ifFloatSequence",g.getFactory().getCoordinateSequenceFactory().create(new Coordinate[]{new Coordinate(1.123456789,2)})
                .getX(0)!=1.123456789);
        if(roi instanceof qupath.lib.roi.PolygonROI) {
            // QuPath may normalize a self-intersection into MultiPolygon. We retain
            // the original single ring for review, so its type must match that ring.
            json.addProperty("type","Polygon");
            var ring=new JsonArray();for(var p:roi.getAllPoints())ring.add(coordinate(new Coordinate(p.getX(),p.getY())));
            if(!ring.isEmpty())ring.add(ring.get(0).deepCopy());
            var rings=new JsonArray();rings.add(ring);json.add("coordinates",rings);
        }
    }
    private static void precise(JsonObject json,Geometry geometry) {
        json.addProperty("ifCoordinatePrecision","floating-v1");
        if(geometry instanceof Polygon polygon) {
            var rings=new JsonArray();rings.add(line(polygon.getExteriorRing()));
            for(int i=0;i<polygon.getNumInteriorRing();i++)rings.add(line(polygon.getInteriorRingN(i)));
            json.add("coordinates",rings);
        }else if(geometry instanceof Point point) {
            json.add("coordinates",point.isEmpty()?new JsonArray():coordinate(point.getCoordinate()));
        }else if(geometry instanceof LineString line)json.add("coordinates",line(line));
        else if(geometry instanceof GeometryCollection collection) {
            boolean multi=geometry instanceof MultiPolygon || geometry instanceof MultiPoint || geometry instanceof MultiLineString;
            var parts=new JsonArray();
            for(int i=0;i<collection.getNumGeometries();i++) {
                var part=new JsonObject();var child=collection.getGeometryN(i);
                part.addProperty("type",child.getGeometryType());precise(part,child);
                parts.add(multi?part.get("coordinates"):part);
            }
            json.add(multi?"coordinates":"geometries",parts);
        }else throw new IllegalArgumentException("Unsupported geometry: "+geometry.getGeometryType());
    }
    private static JsonArray line(LineString line) {
        var array=new JsonArray();for(var point:line.getCoordinates())array.add(coordinate(point));return array;
    }
    private static JsonArray coordinate(Coordinate c) {
        if(!Double.isFinite(c.x)||!Double.isFinite(c.y))throw new IllegalArgumentException("Nonfinite source geometry");
        var value=new JsonArray();value.add(c.x);value.add(c.y);return value;
    }
    static ROI read(JsonObject json,ROI fallback) {
        if(!json.has("ifCoordinatePrecision"))return fallback;
        if(!"floating-v1".equals(json.get("ifCoordinatePrecision").getAsString()))throw new IllegalArgumentException("Unsupported IF coordinate precision");
        double scale=json.has("ifPrecisionScale")?json.get("ifPrecisionScale").getAsDouble():0;
        if(!Double.isFinite(scale)||scale<0)throw new IllegalArgumentException("Invalid IF precision scale");
        if(json.has("ifFloatSequence")&&(!json.get("ifFloatSequence").isJsonPrimitive()
                ||!json.getAsJsonPrimitive("ifFloatSequence").isBoolean()))throw new IllegalArgumentException("Expected boolean IF coordinate storage flag");
        var factory=new GeometryFactory(scale==0?new PrecisionModel():new PrecisionModel(scale),0,
                json.has("ifFloatSequence")&&json.get("ifFloatSequence").getAsBoolean()
                ?org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory.FLOAT_FACTORY
                :org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory.DOUBLE_FACTORY);
        var g=parse(json,factory,0);
        for(var coordinate:g.getCoordinates())if(!Double.isFinite(coordinate.x)||!Double.isFinite(coordinate.y))
            throw new IllegalArgumentException("IF coordinate exceeds storage range");
        if(json.has("ifRoiClass")&&"PolygonROI".equals(json.get("ifRoiClass").getAsString())) {
            if(!"Polygon".equals(json.get("type").getAsString())||json.getAsJsonArray("coordinates").size()!=1)
                throw new IllegalArgumentException("PolygonROI requires exactly one ring; refusing to discard holes or components");
            var p=points(json.getAsJsonArray("coordinates").get(0).getAsJsonArray());
            if(p.length<4||!p[0].equals2D(p[p.length-1]))throw new IllegalArgumentException("PolygonROI requires a closed nonempty ring");
            var x=new double[p.length-1];var y=new double[p.length-1];
            for(int i=0;i<x.length;i++){
                x[i]=p[i].x;y[i]=p[i].y;
                if(!Float.isFinite((float)x[i])||!Float.isFinite((float)y[i]))throw new IllegalArgumentException("PolygonROI coordinate exceeds float storage range");
            }
            return qupath.lib.roi.ROIs.createPolygonROI(x,y,fallback.getImagePlane());
        }
        if(json.has("isEllipse")&&json.get("isEllipse").getAsBoolean()) {
            var e=g.getEnvelopeInternal();
            return qupath.lib.roi.ROIs.createEllipseROI(e.getMinX(),e.getMinY(),e.getWidth(),e.getHeight(),fallback.getImagePlane());
        }
        return qupath.lib.roi.GeometryTools.geometryToROI(g,fallback.getImagePlane());
    }
    private static Geometry parse(JsonObject json,GeometryFactory f,int depth) {
        if(depth>32)throw new IllegalArgumentException("IF geometry nesting exceeds 32 levels");
        String type=json.get("type").getAsString();
        var a=json.getAsJsonArray(type.equals("GeometryCollection")?"geometries":"coordinates");
        return switch(type) {
            case "Point" -> f.createPoint(a.isEmpty()?null:point(a));
            case "LineString" -> f.createLineString(points(a));
            case "Polygon" -> polygon(a,f);
            case "MultiPolygon" -> {var p=new Polygon[a.size()];for(int i=0;i<p.length;i++)p[i]=polygon(a.get(i).getAsJsonArray(),f);yield f.createMultiPolygon(p);}
            case "MultiPoint" -> f.createMultiPointFromCoords(points(a));
            case "MultiLineString" -> {var p=new LineString[a.size()];for(int i=0;i<p.length;i++)p[i]=f.createLineString(points(a.get(i).getAsJsonArray()));yield f.createMultiLineString(p);}
            case "GeometryCollection" -> {var p=new Geometry[a.size()];for(int i=0;i<p.length;i++)p[i]=parse(a.get(i).getAsJsonObject(),f,depth+1);yield f.createGeometryCollection(p);}
            default -> throw new IllegalArgumentException("Unsupported IF geometry: "+type);
        };
    }
    private static Polygon polygon(JsonArray a,GeometryFactory f) {
        if(a.isEmpty())return f.createPolygon();
        var holes=new LinearRing[a.size()-1];for(int i=0;i<holes.length;i++)holes[i]=f.createLinearRing(points(a.get(i+1).getAsJsonArray()));
        return f.createPolygon(f.createLinearRing(points(a.get(0).getAsJsonArray())),holes);
    }
    private static Coordinate[] points(JsonArray a) {
        var c=new Coordinate[a.size()];for(int i=0;i<c.length;i++)c[i]=point(a.get(i).getAsJsonArray());return c;
    }
    private static Coordinate point(JsonArray a) {
        if(a.size()!=2)throw new IllegalArgumentException("Expected two-dimensional IF coordinate");
        for(var value:a)if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Expected numeric IF coordinate");
        double x=a.get(0).getAsDouble(),y=a.get(1).getAsDouble();
        if(!Double.isFinite(x)||!Double.isFinite(y))throw new IllegalArgumentException("Nonfinite IF coordinate");
        return new Coordinate(x,y);
    }
}
