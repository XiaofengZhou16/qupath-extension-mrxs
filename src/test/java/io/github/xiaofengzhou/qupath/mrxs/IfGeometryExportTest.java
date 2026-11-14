package io.github.xiaofengzhou.qupath.mrxs;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;
import qupath.lib.io.GsonTools;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.interfaces.ROI;
import static org.junit.jupiter.api.Assertions.*;

class IfGeometryExportTest {
    @Test void rejectsPolygonMarkerThatWouldDiscardHoles() {
        var roi=qupath.lib.roi.ROIs.createPolygonROI(new double[]{0,10,10,0},new double[]{0,0,10,10},ImagePlane.getDefaultPlane());
        var json=IfGeometryExport.roi(roi);
        json.getAsJsonArray("coordinates").add(com.google.gson.JsonParser.parseString("[[2,2],[3,2],[3,3],[2,3],[2,2]]"));
        assertThrows(IllegalArgumentException.class,()->IfGeometryExport.read(json,roi));
    }
    @Test void rejectsMalformedScaleFlagsCoordinatesAndDeepCollections() {
        var roi=qupath.lib.roi.ROIs.createRectangleROI(0,0,10,10,ImagePlane.getDefaultPlane());
        for(double scale:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1}) {
            var json=IfGeometryExport.roi(roi);json.addProperty("ifPrecisionScale",scale);
            assertThrows(IllegalArgumentException.class,()->IfGeometryExport.read(json,roi));
        }
        var flag=IfGeometryExport.roi(roi);flag.addProperty("ifFloatSequence","true");
        assertThrows(IllegalArgumentException.class,()->IfGeometryExport.read(flag,roi));
        var bad=IfGeometryExport.roi(roi);bad.getAsJsonArray("coordinates").get(0).getAsJsonArray().get(0).getAsJsonArray().set(0,new com.google.gson.JsonPrimitive("1"));
        assertThrows(IllegalArgumentException.class,()->IfGeometryExport.read(bad,roi));
        var deep=IfGeometryExport.roi(roi);
        for(int i=0;i<34;i++){
            var parent=new com.google.gson.JsonObject();parent.addProperty("type","GeometryCollection");parent.addProperty("ifCoordinatePrecision","floating-v1");
            var children=new com.google.gson.JsonArray();children.add(deep);parent.add("geometries",children);deep=parent;
        }
        var nested=deep;assertThrows(IllegalArgumentException.class,()->IfGeometryExport.read(nested,roi));
    }
    @Test void floatingMultipolygonRetainsHolesPlaneAndCoordinates() {
        var f=new GeometryFactory(new PrecisionModel(),0);
        var shell=f.createLinearRing(new Coordinate[]{new Coordinate(33000.123,82000.456),new Coordinate(33020.123,82000.456),new Coordinate(33020.123,82020.456),new Coordinate(33000.123,82020.456),new Coordinate(33000.123,82000.456)});
        var hole=f.createLinearRing(new Coordinate[]{new Coordinate(33005.123,82005.456),new Coordinate(33010.123,82005.456),new Coordinate(33010.123,82010.456),new Coordinate(33005.123,82010.456),new Coordinate(33005.123,82005.456)});
        var polygon=f.createPolygon(shell,new LinearRing[]{hole});
        var original=GeometryTools.geometryToROI(f.createMultiPolygon(new Polygon[]{polygon}),ImagePlane.getPlane(2,3));
        var json=IfGeometryExport.roi(original);
        var fallback=GsonTools.getInstance().fromJson(json,ROI.class);
        var restored=IfGeometryExport.read(json,fallback);
        assertEquals(original.getImagePlane(),restored.getImagePlane());
        assertEquals(original.getCentroidX(),restored.getCentroidX(),1e-8);
        assertEquals(original.getCentroidY(),restored.getCentroidY(),1e-8);
        assertEquals(original.getArea(),restored.getArea(),1e-8);
        assertEquals(original.getGeometry().getNumPoints(),restored.getGeometry().getNumPoints());
    }
    @Test void legacyFallbackAndUnsupportedPrecisionAreExplicit() {
        var roi=qupath.lib.roi.ROIs.createRectangleROI(0,0,10,10,ImagePlane.getDefaultPlane());
        var json=IfGeometryExport.roi(roi);json.remove("ifCoordinatePrecision");
        assertSame(roi,IfGeometryExport.read(json,roi));
        json.addProperty("ifCoordinatePrecision","unknown");
        assertThrows(IllegalArgumentException.class,()->IfGeometryExport.read(json,roi));
    }
}
