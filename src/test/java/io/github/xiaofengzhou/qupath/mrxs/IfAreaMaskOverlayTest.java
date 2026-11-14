package io.github.xiaofengzhou.qupath.mrxs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.images.ImageData;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.ImageRegion;
import qupath.lib.roi.ROIs;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class IfAreaMaskOverlayTest {
    @TempDir Path temporary;
    @Test void sharedPngMustMatchDeclaredCodeIncludingNegativeAndOutside()throws Exception {
        var raw=new BufferedImage(3,2,BufferedImage.TYPE_USHORT_GRAY);
        for(int expected:List.of(0,1,256)) {
            for(int y=0;y<2;y++)for(int x=0;x<3;x++)raw.getRaster().setSample(x,y,0,expected);
            var reference=IfAreaMaskOverlay.render(raw,8,1,false);
            var validated=IfAreaMaskOverlay.render(raw,8,1,false,expected);
            assertArrayEquals(reference.getRGB(0,0,3,2,null,0,3),validated.getRGB(0,0,3,2,null,0,3));
            raw.getRaster().setSample(2,1,0,expected==0?1:0);
            assertThrows(java.io.IOException.class,()->IfAreaMaskOverlay.render(raw,8,1,false,expected));
        }
    }
    @Test void sharedIndexRejectsSemanticChangesEvenWithUpdatedChecksums()throws Exception {
        try(var server=new IfAnalysisTest.TruthServer(1)) {
            Path directory=temporary.resolve("shared-integrity");
            var result=IfAnalysisExport.analyzeAndWrite(directory,server,ROIs.createRectangleROI(0,0,32,8,ImagePlane.getDefaultPlane()),
                    IfAreaMasksTest.profile(0),List.of(),Map.of(),true,()->false,v->{});
            Path index=directory.resolve("area-masks/tiles.jsonl"),manifest=directory.resolve("area-masks/manifest.json"),parent=directory.resolve("manifest.json");
            var originalLines=Files.readAllLines(index);
            String originalManifest=Files.readString(manifest),originalParent=Files.readString(parent);
            for(int mutation=0;mutation<4;mutation++) {
                var lines=new java.util.ArrayList<>(originalLines);
                var first=com.google.gson.JsonParser.parseString(lines.get(0)).getAsJsonObject();
                if(mutation==0)lines.set(1,lines.get(0)); // duplicate origin, identical totals
                if(mutation==1){first.addProperty("file","constant-1-1-257.png");lines.set(0,first.toString());}
                if(mutation==2){first.addProperty("sha256","0".repeat(64));lines.set(0,first.toString());}
                Files.write(index,lines);
                var m=com.google.gson.JsonParser.parseString(originalManifest).getAsJsonObject();
                m.addProperty("indexSha256",IfAreaMasks.digest(index));
                if(mutation==3)m.addProperty("uniquePngFiles",999);
                Files.writeString(manifest,m.toString());
                var p=com.google.gson.JsonParser.parseString(originalParent).getAsJsonObject();
                p.getAsJsonObject("areaMasks").addProperty("manifestSha256",IfAreaMasks.digest(manifest));Files.writeString(parent,p.toString());
                assertThrows(java.io.IOException.class,()->IfAreaMaskOverlay.readIndex(directory,result,0,0,()->false),"mutation="+mutation);
            }
        }
    }
    @Test void numericOverlayExcludesOutsideAndNegativeAndDistinguishesExactFromInclusive() throws Exception {
        var raw=new BufferedImage(9,1,BufferedImage.TYPE_USHORT_GRAY);
        for(int x=0;x<9;x++)raw.getRaster().setSample(x,0,0,x);
        for(boolean exact:List.of(false,true)) {
            var image=IfAreaMaskOverlay.render(raw,3,3,exact);
            for(int x=0;x<9;x++) {
                int bits=x-1;boolean positive=x>0&&(exact?bits==3:(bits&3)==3);
                assertEquals(positive,((image.getRGB(x,0)>>>24)&255)>0);
            }
        }
        var any=IfAreaMaskOverlay.render(raw,3,0,false);
        assertEquals(0,any.getRGB(0,0));assertEquals(0,any.getRGB(1,0));assertNotEquals(0,any.getRGB(8,0));
        raw.getRaster().setSample(0,0,0,9);
        assertThrows(java.io.IOException.class,()->IfAreaMaskOverlay.render(raw,3,0,false));
    }
    @Test void asyncViewportOverlayPreservesNativeCoordinatesAndIsolatesOtherImagesAndPlanes() throws Exception {
        for(int level:List.of(0,1))try(var server=new IfAnalysisTest.TruthServer(7)) {
            var roi=ROIs.createRectangleROI(3,1,28,6,ImagePlane.getDefaultPlane());
            var directory=temporary.resolve("preview-"+level);
            var result=IfAnalysisExport.analyzeAndWrite(directory,server,roi,IfAreaMasksTest.profile(level),List.of(),Map.of(),true,()->false,v->{});
            int reads=server.reads;var entry=IfAnalysisHistory.scan(directory,()->false).entries().getFirst();
            var dataset=IfAreaMaskOverlay.readHistoricalIndex(entry,()->false);
            var direct=IfAreaMaskOverlay.readIndex(directory,result,0,0,()->false);
            assertEquals(direct.index().size(),dataset.index().size());assertEquals(direct.downsample(),dataset.downsample());
            var data=new ImageData<>(server,ImageData.ImageType.FLUORESCENCE);
            var ready=new CountDownLatch(result.tilesRead());var status=new AtomicReference<String>();
            try(var overlay=new IfAreaMaskOverlay(data,dataset,ready::countDown,status::set)) {
                overlay.setVisible(true);overlay.setOpacity(1);overlay.setFilter(3,false);
                var region=ImageRegion.createInstance(0,0,32,8,0,0);
                var other=new ImageData<>(server,ImageData.ImageType.FLUORESCENCE);
                var hidden=new BufferedImage(32,8,BufferedImage.TYPE_INT_ARGB);var hg=hidden.createGraphics();
                try{overlay.paintOverlay(hg,region,1,other,true);overlay.paintOverlay(hg,ImageRegion.createInstance(0,0,32,8,1,0),1,data,true);}finally{hg.dispose();}
                assertEquals(result.tilesRead(),ready.getCount());assertEquals(0,hidden.getRGB(15,3));
                var scratch=new BufferedImage(32,8,BufferedImage.TYPE_INT_ARGB);var sg=scratch.createGraphics();
                try{overlay.paintOverlay(sg,region,1,data,true);}finally{sg.dispose();}
                assertTrue(ready.await(5,TimeUnit.SECONDS),"Background mask loads did not complete");
                var painted=new BufferedImage(32,8,BufferedImage.TYPE_INT_ARGB);var graphics=painted.createGraphics();
                try{overlay.paintOverlay(graphics,region,1,data,true);}finally{graphics.dispose();}
                for(int y=0;y<8;y++)for(int x=0;x<32;x++) {
                    double ds=result.downsample(),sx=(Math.floor(x/ds)+0.5)*ds,sy=(Math.floor(y/ds)+0.5)*ds;
                    boolean expected=sx>=3&&sx<31&&sy>=1&&sy<7&&((((int)sx/4)&3)==3);
                    assertEquals(expected,painted.getRGB(x,y)!=0,"level="+level+" source="+x+","+y);
                }
                assertEquals(reads,server.reads,"Overlay must not reread original scan");
                assertFalse(status.get().contains("failed"));
                overlay.setVisible(false);
                var blank=new BufferedImage(32,8,BufferedImage.TYPE_INT_ARGB);var bg=blank.createGraphics();
                try{overlay.paintOverlay(bg,region,1,data,true);}finally{bg.dispose();}
                assertEquals(0,blank.getRGB(15,3));
            }
        }
    }
    @Test void incompleteOrModifiedIndexIsRejectedAndCorruptTileNeverPainted() throws Exception {
        try(var server=new IfAnalysisTest.TruthServer(32)) {
            var directory=temporary.resolve("integrity");var roi=ROIs.createRectangleROI(0,0,32,8,ImagePlane.getDefaultPlane());
            var result=IfAnalysisExport.analyzeAndWrite(directory,server,roi,IfAreaMasksTest.profile(0),List.of(),Map.of(),true,()->false,v->{});
            Files.writeString(directory.resolve("INCOMPLETE"),"test");
            assertThrows(java.io.IOException.class,()->IfAreaMaskOverlay.readIndex(directory,result,0,0,()->false));
            Files.delete(directory.resolve("INCOMPLETE"));
            var dataset=IfAreaMaskOverlay.readIndex(directory,result,0,0,()->false);
            Files.writeString(directory.resolve("area-masks/tile-0-0.png"),"corrupt fixture");
            var data=new ImageData<>(server,ImageData.ImageType.FLUORESCENCE);var ready=new CountDownLatch(1);var status=new AtomicReference<String>();
            try(var overlay=new IfAreaMaskOverlay(data,dataset,ready::countDown,status::set)) {
                overlay.setVisible(true);var image=new BufferedImage(32,8,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();
                try {
                    var region=ImageRegion.createInstance(0,0,32,8,0,0);overlay.paintOverlay(g,region,1,data,true);
                    assertTrue(ready.await(5,TimeUnit.SECONDS));overlay.paintOverlay(g,region,1,data,true);
                }finally{g.dispose();}
                assertEquals(0,image.getRGB(15,3));assertTrue(status.get().contains("read failed"));
            }
            Files.writeString(directory.resolve("area-masks/tiles.jsonl"),"{}\n");
            assertThrows(java.io.IOException.class,()->IfAreaMaskOverlay.readIndex(directory,result,0,0,()->false));
        }
    }

    @Test void fullServerRegionDoesNotTriggerZoomLimitWhenActualGraphicsClipIsSmall() throws Exception {
        try(var server=new IfAnalysisTest.TruthServer(1)) {
            var directory=temporary.resolve("viewport");var roi=ROIs.createRectangleROI(0,0,32,8,ImagePlane.getDefaultPlane());
            var result=IfAnalysisExport.analyzeAndWrite(directory,server,roi,IfAreaMasksTest.profile(0),List.of(),Map.of(),true,()->false,v->{});
            assertTrue(result.tilesRead()>128);
            var dataset=IfAreaMaskOverlay.readIndex(directory,result,0,0,()->false);
            var data=new ImageData<>(server,ImageData.ImageType.FLUORESCENCE);
            int visible=dataset.index().query(new org.locationtech.jts.geom.Envelope(12,16,0,4)).size();
            assertTrue(visible<=32);
            int unique=(int)dataset.index().query(new org.locationtech.jts.geom.Envelope(12,16,0,4)).stream()
                    .map(o->((IfAreaMasks.Tile)o).file()).distinct().count();
            var ready=new CountDownLatch(unique);var status=new AtomicReference<String>();
            try(var overlay=new IfAreaMaskOverlay(data,dataset,ready::countDown,status::set)) {
                overlay.setVisible(true);overlay.setOpacity(1);
                var image=new BufferedImage(32,8,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();
                try {
                    g.setClip(12,0,4,4);
                    overlay.paintOverlay(g,ImageRegion.createInstance(0,0,32,8,0,0),1,data,true);
                    assertTrue(ready.await(5,TimeUnit.SECONDS));
                    overlay.paintOverlay(g,ImageRegion.createInstance(0,0,32,8,0,0),1,data,true);
                }finally{g.dispose();}
                assertNotEquals(0,image.getRGB(13,2));assertEquals(0,image.getRGB(20,2));
                assertFalse(status.get().contains("Zoom in"));
            }
        }
    }
    @Test void historicalMasksRejectMissingExportAndTamperedIndex()throws Exception {
        try(var server=new IfAnalysisTest.TruthServer(7)){
            var roi=ROIs.createRectangleROI(0,0,32,8,ImagePlane.getDefaultPlane());
            Path absent=temporary.resolve("absent"),present=temporary.resolve("present");
            IfAnalysisExport.analyzeAndWrite(absent,server,roi,IfAreaMasksTest.profile(0),List.of(),Map.of(),false,()->false,v->{});
            var missing=IfAnalysisHistory.scan(absent,()->false).entries().getFirst();assertThrows(java.io.IOException.class,()->IfAreaMaskOverlay.readHistoricalIndex(missing,()->false));
            IfAnalysisExport.analyzeAndWrite(present,server,roi,IfAreaMasksTest.profile(0),List.of(),Map.of(),true,()->false,v->{});
            var entry=IfAnalysisHistory.scan(present,()->false).entries().getFirst();
            assertThrows(java.util.concurrent.CancellationException.class,()->IfAreaMaskOverlay.readHistoricalIndex(entry,()->true));
            Files.writeString(present.resolve("area-masks/tiles.jsonl"),"{}\n");assertThrows(java.io.IOException.class,()->IfAreaMaskOverlay.readHistoricalIndex(entry,()->false));
        }
    }
}
