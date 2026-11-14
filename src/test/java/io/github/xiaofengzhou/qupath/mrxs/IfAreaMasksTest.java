package io.github.xiaofengzhou.qupath.mrxs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.color.ColorModelFactory;
import qupath.lib.images.servers.*;
import qupath.lib.io.GsonTools;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RoiTools;
import java.awt.Point;
import java.awt.image.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class IfAreaMasksTest {
    @TempDir Path temporary;
    @Test void directConstantTilesMatchRasterPathIncludingOutsideAndCode256()throws Exception {
        Path direct=temporary.resolve("direct"),raster=temporary.resolve("raster");
        var codes=List.of(0,1,256,1,0,256);
        try(var a=new IfAreaMasks(direct,8,()->false);var b=new IfAreaMasks(raster,8,()->false)) {
            for(int i=0;i<codes.size();i++) {
                int code=codes.get(i);a.acceptConstant(i*4,0,4,3,code);
                IfAnalysis.MaskSink sink=b::accept;
                sink.acceptConstant(i*4,0,4,3,code); // exercise compatibility default
            }
            assertThrows(java.io.IOException.class,()->a.acceptConstant(0,0,4,3,1));
            assertThrows(java.io.IOException.class,()->a.acceptConstant(100,0,4,3,257));
            assertThrows(java.io.IOException.class,()->a.acceptConstant(100,0,0,3,1));
        }
        assertArrayEquals(Files.readAllBytes(raster.resolve("tiles.jsonl")),Files.readAllBytes(direct.resolve("tiles.jsonl")));
        try(var files=Files.list(raster)) {
            for(var path:files.toList())assertArrayEquals(Files.readAllBytes(path),Files.readAllBytes(direct.resolve(path.getFileName())));
        }
        assertEquals(256,ImageIO.read(direct.resolve("constant-4-3-256.png").toFile()).getRaster().getSample(3,2,0));
    }
    @Test void identicalConstantTilesSharePngButMixedCoverageAndDuplicatesAreNotCollapsed()throws Exception {
        Path directory=temporary.resolve("shared");
        var constant=new BufferedImage(4,4,BufferedImage.TYPE_USHORT_GRAY);
        for(int y=0;y<4;y++)for(int x=0;x<4;x++)constant.getRaster().setSample(x,y,0,1);
        try(var writer=new IfAreaMasks(directory,3,()->false)) {
            writer.accept(0,0,constant);writer.accept(4,0,constant);
            constant.getRaster().setSample(1,1,0,0);writer.accept(8,0,constant);
            assertThrows(java.io.IOException.class,()->writer.accept(4,0,constant));
        }
        var rows=Files.readAllLines(directory.resolve("tiles.jsonl")).stream()
                .map(s->GsonTools.getInstance().fromJson(s,IfAreaMasks.Tile.class)).toList();
        assertEquals(3,rows.size());assertEquals(rows.get(0).file(),rows.get(1).file());
        assertEquals(16,rows.get(0).roiPixels());assertEquals(15,rows.get(2).roiPixels());
        assertNotEquals(rows.get(0).file(),rows.get(2).file());
        try(var files=Files.list(directory)){assertEquals(2,files.filter(p->p.toString().endsWith(".png")).count());}
        assertEquals(1,ImageIO.read(directory.resolve(rows.get(0).file()).toFile()).getRaster().getSample(1,1,0));
        assertEquals(0,ImageIO.read(directory.resolve(rows.get(2).file()).toFile()).getRaster().getSample(1,1,0));
    }
    @Test void legacyVersionOneMasksStillLoad()throws Exception {
        try(var server=new IfAnalysisTest.TruthServer(32)) {
            Path directory=temporary.resolve("legacy");
            var result=IfAnalysisExport.analyzeAndWrite(directory,server,ROIs.createRectangleROI(0,0,32,8,ImagePlane.getDefaultPlane()),
                    profile(0),List.of(),Map.of(),true,()->false,v->{});
            Path mask=directory.resolve("area-masks/manifest.json"),parent=directory.resolve("manifest.json");
            var m=com.google.gson.JsonParser.parseString(Files.readString(mask)).getAsJsonObject();
            m.addProperty("schemaVersion",1);m.addProperty("encoding","uint16_png_bitmask_plus_one");
            m.remove("uniquePngFiles");m.remove("sharedConstantPngFiles");Files.writeString(mask,m.toString());
            var p=com.google.gson.JsonParser.parseString(Files.readString(parent)).getAsJsonObject();
            p.getAsJsonObject("areaMasks").addProperty("manifestSha256",IfAreaMasks.digest(mask));Files.writeString(parent,p.toString());
            assertEquals(result.tilesRead(),IfAreaMaskOverlay.readIndex(directory,result,0,0,()->false).index().size());
        }
    }
    static IfAnalysisProfile profile(int level) {
        return new IfAnalysisProfile(1,level,List.of(new IfAnalysisProfile.Marker(0,"A",100,100,100),
                new IfAnalysisProfile.Marker(1,"B",100,100,100),new IfAnalysisProfile.Marker(2,"C",100,100,100)));
    }
    @Test void tileMasksRoundtripMatchIndependentPixelsAndAllStatisticsAtBothLevels() throws Exception {
        for(int level:List.of(0,1))try(var server=new IfAnalysisTest.TruthServer(7)) {
            var roi=RoiTools.difference(ROIs.createRectangleROI(3,1,28,6,ImagePlane.getDefaultPlane()),
                    ROIs.createRectangleROI(10,2,7,3,ImagePlane.getDefaultPlane()));
            var directory=temporary.resolve("level-"+level);
            var result=IfAnalysisExport.analyzeAndWrite(directory,server,roi,profile(level),List.of(),Map.of("method","none"),true,()->false,v->{});
            assertEquals(result.tilesRead(),server.reads); // no second pass through the source
            assertFalse(Files.exists(directory.resolve("INCOMPLETE")));
            long[] histogram=new long[8];long roiPixels=0;int tileCount=0;
            for(var line:Files.readAllLines(directory.resolve("area-masks/tiles.jsonl"))) {
                var tile=GsonTools.getInstance().fromJson(line,IfAreaMasks.Tile.class);tileCount++;
                var path=directory.resolve("area-masks").resolve(tile.file());
                assertEquals(tile.sha256(),IfAreaMasks.digest(path));
                var mask=ImageIO.read(path.toFile());assertEquals(DataBuffer.TYPE_USHORT,mask.getRaster().getDataBuffer().getDataType());
                assertEquals(tile.width(),mask.getWidth());assertEquals(tile.height(),mask.getHeight());long tilePixels=0;
                for(int y=0;y<mask.getHeight();y++)for(int x=0;x<mask.getWidth();x++) {
                    double sx=(tile.levelX()+x+0.5)*result.downsample(),sy=(tile.levelY()+y+0.5)*result.downsample();
                    // Independent half-open raster geometry, including centers exactly on coarse-level edges.
                    boolean sampled=sx>=3&&sx<31&&sy>=1&&sy<7&&!(sx>=10&&sx<17&&sy>=2&&sy<5);
                    int expected=sampled ? ((int)sx)/4+1 : 0;
                    int actual=mask.getRaster().getSample(x,y,0);assertEquals(expected,actual,"level="+level+" center="+sx+","+sy+" shapeContains="+RoiTools.getShape(roi).contains(sx,sy));
                    if(actual>0){histogram[actual-1]++;roiPixels++;tilePixels++;}
                }
                assertEquals(tile.roiPixels(),tilePixels);
            }
            assertEquals(result.roiPixels(),roiPixels);assertEquals(result.tilesRead(),tileCount);
            for(var c:result.combinations()) {
                assertEquals(c.paExactPixels(),histogram[c.bits()]);long count=0;
                for(int mask=0;mask<8;mask++)if((mask&c.bits())==c.bits())count+=histogram[mask];
                assertEquals(c.paPixels(),count);
            }
            var manifest=com.google.gson.JsonParser.parseString(Files.readString(directory.resolve("manifest.json"))).getAsJsonObject();
            assertTrue(manifest.getAsJsonObject("areaMasks").get("countsVerifiedAgainstStatistics").getAsBoolean());
            assertEquals(IfAreaMasks.digest(directory.resolve("area-masks/manifest.json")),manifest.getAsJsonObject("areaMasks").get("manifestSha256").getAsString());
            assertThrows(java.nio.file.FileAlreadyExistsException.class,()->IfAnalysisExport.analyzeAndWrite(directory,server,roi,profile(level),List.of(),Map.of(),true,()->false,v->{}));
        }
    }
    @Test void eightMarkersRetainCode256InsteadOfWrappingToOutside() throws Exception {
        var channels=java.util.stream.IntStream.range(0,8).mapToObj(i->ImageChannel.getInstance("M"+i,0xff0000)).toList();
        var raster=Raster.createBandedRaster(DataBuffer.TYPE_BYTE,4,4,8,new Point());
        for(int c=0;c<8;c++)for(int y=0;y<4;y++)for(int x=0;x<4;x++)raster.setSample(x,y,c,200);
        try(var server=new WrappedBufferedImageServer("eight",new BufferedImage(ColorModelFactory.createColorModel(PixelType.UINT8,channels),raster,false,null),channels)) {
            server.setMetadata(new ImageServerMetadata.Builder(server.getMetadata()).rgb(false).build());
            var p=new IfAnalysisProfile(1,0,java.util.stream.IntStream.range(0,8).mapToObj(i->new IfAnalysisProfile.Marker(i,"M"+i,100,100,100)).toList());
            var output=temporary.resolve("eight");
            var result=IfAnalysisExport.analyzeAndWrite(output,server,ROIs.createRectangleROI(0,0,4,4,ImagePlane.getDefaultPlane()),p,List.of(),Map.of(),true,()->false,v->{});
            assertEquals(255,result.combinations().size());
            var tile=GsonTools.getInstance().fromJson(Files.readAllLines(output.resolve("area-masks/tiles.jsonl")).getFirst(),IfAreaMasks.Tile.class);
            var mask=ImageIO.read(output.resolve("area-masks").resolve(tile.file()).toFile());
            assertEquals(256,mask.getRaster().getSample(0,0,0));
            assertTrue(IfAreaMasks.matches(256,3,false));assertFalse(IfAreaMasks.matches(256,3,true));
            assertTrue(IfAreaMasks.matches(256,255,true));
            assertFalse(IfAreaMasks.matches(0,0,false));assertFalse(IfAreaMasks.matches(1,0,false));
        }
    }
    @Test void cancelledMaskRunIsNeverMarkedCompleteAndOptionalOffProducesSameCounts() throws Exception {
        try(var server=new IfAnalysisTest.TruthServer(7)) {
            var roi=ROIs.createRectangleROI(0,0,32,8,ImagePlane.getDefaultPlane());
            var cancelled=new AtomicBoolean();var output=temporary.resolve("cancelled");
            assertThrows(CancellationException.class,()->IfAnalysisExport.analyzeAndWrite(output,server,roi,profile(0),List.of(),Map.of(),true,cancelled::get,v->cancelled.set(true)));
            assertTrue(Files.exists(output.resolve("INCOMPLETE")));assertFalse(Files.exists(output.resolve("manifest.json")));
            assertFalse(Files.exists(output.resolve("area-masks/manifest.json")));
            var off=IfAnalysisExport.analyzeAndWrite(temporary.resolve("off"),server,roi,profile(0),List.of(),Map.of(),false,()->false,v->{});
            var on=IfAnalysisExport.analyzeAndWrite(temporary.resolve("on"),server,roi,profile(0),List.of(),Map.of(),true,()->false,v->{});
            assertEquals(off.combinations(),on.combinations());assertFalse(Files.exists(temporary.resolve("off/area-masks")));
        }
    }
    @Test void sinkFailureAbortsAnalysisRatherThanSilentlyDroppingMasks() throws Exception {
        try(var server=new IfAnalysisTest.TruthServer(7)) {
            var roi=ROIs.createRectangleROI(0,0,32,8,ImagePlane.getDefaultPlane());
            var exception=assertThrows(java.io.IOException.class,()->IfAnalysis.analyze(server,roi,profile(0),List.of(),()->false,v->{},
                    (x,y,mask)->{throw new java.io.IOException("Simulated full disk");}));
            assertEquals("Simulated full disk",exception.getMessage());assertEquals(1,server.reads);
        }
    }
}
