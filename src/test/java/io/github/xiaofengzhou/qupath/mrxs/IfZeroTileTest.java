package io.github.xiaofengzhou.qupath.mrxs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.nio.file.Path;
import java.util.List;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RoiTools;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import static org.junit.jupiter.api.Assertions.*;

class IfZeroTileTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    private static qupath.lib.roi.interfaces.ROI rect(double x,double y,double w,double h) {
        return ROIs.createRectangleROI(x,y,w,h,ImagePlane.getDefaultPlane());
    }
    @Test void edgeCenterCountsAndZeroThresholdEquality() {
        // Centers 9 and 11 are inside each axis; x=13 lies on the excluded image boundary.
        assertEquals(4,IfAnalysis.zeroTilePixelCount(8,8,4,4,2,13,12));
        var p=new IfAnalysisProfile(1,0,List.of(new IfAnalysisProfile.Marker(0,"a",0,0,0),
                new IfAnalysisProfile.Marker(1,"b",-1,0,0),new IfAnalysisProfile.Marker(2,"c",1,0,0)));
        assertEquals(3,IfAnalysis.zeroPixelMask(p));
    }
    @Test void realIndexedZerosMatchForcedPerPixelPathWithEdgesHolesAndCells() throws Exception {
        var fixture=System.getenv("MRXS_TEST_SAMPLE_4C");Assumptions.assumeTrue(fixture!=null);
        try(var server=new MrxsImageServer(Path.of(fixture).toUri())) {
            var first=server.getTileRequestManager().getTileRequests(RegionRequest.createInstance(server.getPath(),1,0,0,512,512)).iterator().next();
            assertFalse(server.hasStoredImage(first),"Fixture must include an index-empty origin tile");
            for(int level:List.of(0,6))for(double threshold:new double[]{30,0,-1}) {
                var p=new IfAnalysisProfile(1,level,List.of(new IfAnalysisProfile.Marker(0,server.getChannel(0).getName(),threshold,threshold,threshold)));
                for(var roi:List.of(rect(0,0,1031,1029),
                        RoiTools.difference(rect(0,0,1031,1029),rect(400,300,350,350)),
                        rect(server.getWidth()-1031,server.getHeight()-1029,1031,1029))) {
                    var a=IfAnalysis.analyze(server,roi,p,List.of(),()->false,v->{});
                    var slowLabels=new java.util.TreeMap<String,int[]>();
                    var fastLabels=new java.util.TreeMap<String,int[]>();
                    var b=IfAnalysis.analyze(server,roi,p,List.of(),()->false,v->{},
                            (x,y,image)->slowLabels.put(x+","+y,image.getRaster().getSamples(0,0,image.getWidth(),image.getHeight(),0,(int[])null)),false);
                    var c=IfAnalysis.analyze(server,roi,p,List.of(),()->false,v->{},
                            (x,y,image)->fastLabels.put(x+","+y,image.getRaster().getSamples(0,0,image.getWidth(),image.getHeight(),0,(int[])null)));
                    assertEquals(b.combinations(),c.combinations());assertEquals(b.roiPixels(),c.roiPixels());
                    assertEquals(slowLabels.keySet(),fastLabels.keySet());
                    for(var key:slowLabels.keySet())assertArrayEquals(slowLabels.get(key),fastLabels.get(key));
                    assertEquals(b.roiPixels(),a.roiPixels());assertEquals(b.combinations(),a.combinations());
                    assertEquals(b.tilesRead(),a.tilesRead()+a.zeroTilesCounted());
                    assertEquals(0,b.zeroTilesCounted());
                    if(level==0 && roi.getBoundsX()==0 && roi.getArea()==1031*1029)assertTrue(a.zeroTilesCounted()>0);
                }
            }
            var p=new IfAnalysisProfile(1,0,List.of(new IfAnalysisProfile.Marker(0,server.getChannel(0).getName(),0,0,0)));
            var cells=List.of(new IfAnalysis.Cell("zero-cell",rect(10,10,20,20),rect(12,12,10,10)));
            var a=IfAnalysis.analyze(server,rect(0,0,1024,1024),p,cells,()->false,v->{});
            var b=IfAnalysis.analyze(server,rect(0,0,1024,1024),p,cells,()->false,v->{},(x,y,image)->{},false);
            assertEquals(b.cells(),a.cells());assertEquals(1,a.cells().size());
            var nucleusOnly=List.of(new IfAnalysis.Cell("zero-nucleus",null,rect(12,12,10,10)));
            var nuclearFast=IfAnalysis.analyze(server,rect(0,0,1024,1024),p,nucleusOnly,()->false,v->{});
            var nuclearSlow=IfAnalysis.analyze(server,rect(0,0,1024,1024),p,nucleusOnly,()->false,v->{},null,false);
            assertEquals(nuclearSlow.nuclearMeasurements(),nuclearFast.nuclearMeasurements());
            assertEquals(1,nuclearFast.nuclearMeasurements().nuclei().size());
            assertEquals(1,nuclearFast.nuclearMeasurements().nuclei().getFirst().mask());
            assertEquals(100,nuclearFast.nuclearMeasurements().nuclei().getFirst().pixels());
            assertEquals(1,nuclearFast.tilesRead());assertEquals(3,nuclearFast.zeroTilesCounted());
            var exported=IfAnalysisExport.analyzeAndWrite(temporary.resolve("zero-masks"),server,rect(0,0,1024,1024),p,
                    List.of(),java.util.Map.of("method","index-empty fixture"),true,()->false,v->{});
            assertEquals(0,exported.tilesRead());assertEquals(4,exported.zeroTilesCounted());
            assertNotNull(IfAreaMaskOverlay.readIndex(temporary.resolve("zero-masks"),exported,0,0,()->false));
            var manifest=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(temporary.resolve("zero-masks/manifest.json"))).getAsJsonObject();
            assertEquals(4,manifest.get("indexCertifiedZeroTilesCountedWithoutRead").getAsInt());
            assertFalse(java.nio.file.Files.exists(temporary.resolve("zero-masks/INCOMPLETE")));
        }
    }
}
