package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.regions.RegionRequest;
import static org.junit.jupiter.api.Assertions.*;

class MrxsConcatenatedTest {
    @TempDir Path temp;
    @Test void baseMergedAcrossCamerasUsesPositionMapAndSkipsInactiveCamera() throws Exception {
        Path slide=SyntheticMrxsFixture.createConcatenatedWithPositions(temp,false);
        Path directory=temp.resolve("concatenated");
        Path ini=directory.resolve("Slidedat.ini");
        java.nio.file.Files.writeString(ini,java.nio.file.Files.readString(ini)
                .replace("IMAGE_CONCAT_FACTOR=0","IMAGE_CONCAT_FACTOR=1"));
        // Both base filters now reference the packed 16px four-camera JPEG.
        Path indexPath=directory.resolve("Index.dat");
        var indexBytes=java.nio.ByteBuffer.wrap(java.nio.file.Files.readAllBytes(indexPath))
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for(int flat:new int[]{0,6})indexBytes.putInt(45+flat*4,indexBytes.getInt(45+(flat+1)*4));
        java.nio.file.Files.write(indexPath,indexBytes.array());
        Path dataPath=directory.resolve("Data0000.dat");
        var data=java.nio.ByteBuffer.wrap(java.nio.file.Files.readAllBytes(dataPath))
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        data.position(data.capacity()-36);
        // Position records are in stored base pixels. Top-right is inactive.
        for(int[] xy:new int[][]{{0,0},{0,0},{1,6},{7,7}})
            data.put((byte)0).putInt(xy[0]).putInt(xy[1]);
        java.nio.file.Files.write(dataPath,data.array());
        try(var index=MiraxIndex.open(SlidedatParser.parse(slide));var server=new MrxsImageServer(slide.toUri())) {
            assertEquals(15,server.getWidth());
            var source=ImageIO.read(new ByteArrayInputStream(index.readPayload(index.readTiles(0,0).getFirst())));
            int[][] expected=new int[15][15];
            for(int y=0;y<8;y++)for(int x=0;x<8;x++)expected[y][x]=source.getRaster().getSample(x,y,0);
            for(int y=0;y<8;y++)for(int x=0;x<8;x++)expected[y+6][x+1]=source.getRaster().getSample(x,y+8,0);
            for(int y=0;y<8;y++)for(int x=0;x<8;x++)expected[y+7][x+7]=source.getRaster().getSample(x+8,y+8,0);
            var actual=server.readRegion(RegionRequest.createInstance(server.getPath(),1,0,0,15,15));
            for(int y=0;y<15;y++)for(int x=0;x<15;x++)for(int c=0;c<4;c++)
                assertEquals(expected[y][x],actual.getRaster().getSample(x,y,c));
        }
    }
    @Test void preconcatenatedBaseUsesStoredPixelCoordinates() throws Exception {
        Path slide=SyntheticMrxsFixture.createDividedCameras(temp);
        Path ini=temp.resolve("concatenated/Slidedat.ini");
        String text=java.nio.file.Files.readString(ini)
                .replace("IMAGE_CONCAT_FACTOR=0","IMAGE_CONCAT_FACTOR=1")
                .replace("IMAGE_CONCAT_FACTOR=2","IMAGE_CONCAT_FACTOR=1");
        java.nio.file.Files.writeString(ini,text);
        try(var server=new MrxsImageServer(slide.toUri())) {
            assertEquals(31,server.getWidth());assertEquals(31,server.getHeight());
            assertEquals(2,server.getDownsampleForResolution(1));
            var actual=server.readRegion(RegionRequest.createInstance(server.getPath(),1,0,0,31,31));
            // Each stored 16px JPEG represents 2x2 original subdivisions.
            // Camera advance is (2*16-2)/2=15 stored pixels, not 30.
            for(int y=0;y<31;y++)for(int x=0;x<31;x++) {
                int expected=y<15?(x<16?20:0):(x<15?60:100);
                for(int c=0;c<4;c++)assertEquals(expected,actual.getRaster().getSample(x,y,c),1);
            }
        }
    }
    @Test void cameraDivisionsAreNotMistakenForCameraPositions() throws Exception {
        Path slide=SyntheticMrxsFixture.createDividedCameras(temp);
        var metadata=SlidedatParser.parse(slide);
        try(var index=MiraxIndex.open(metadata);var server=new MrxsImageServer(slide.toUri())) {
            assertEquals(62,server.getWidth());assertEquals(4,server.getDownsampleForResolution(1));
            var source=ImageIO.read(new ByteArrayInputStream(index.readPayload(index.readTiles(1,0).getFirst())));
            var actual=server.readRegion(RegionRequest.createInstance(server.getPath(),4,0,0,62,62));
            assertEquals(16,actual.getWidth());assertEquals(16,actual.getHeight());
            // Cameras advance (2*16-2)/4 = 7.5 pixels. The lower/right source
            // region starts at 8 while target pixel center 7.5 is its first sample.
            for(int y=0;y<16;y++)for(int x=0;x<16;x++) {
                int expected;
                if(x==15 || y==15)expected=0;
                else if(y<7)expected=x<8?source.getRaster().getSample(x,y,0):0;
                else expected=source.getRaster().getSample(x<7?x:x+1,y+1,0);
                for(int c=0;c<4;c++)assertEquals(expected,actual.getRaster().getSample(x,y,c),"divided camera "+x+","+y);
            }
        }
    }
    @Test void explicitAndCompressedPositionMapsFollowKnownIrregularLayout() throws Exception {
        for(boolean compressed:new boolean[]{false,true}) {
            Path slide=SyntheticMrxsFixture.createConcatenatedWithPositions(temp.resolve("map-"+compressed),compressed);
            var metadata=SlidedatParser.parse(slide);
            try(var index=MiraxIndex.open(metadata);var server=new MrxsImageServer(slide.toUri())) {
                var source=ImageIO.read(new ByteArrayInputStream(index.readPayload(index.readTiles(1,0).getFirst())));
                int[][] expected=new int[15][15];
                // Explicit physical origins at level 1: (0,0), (1,6), (7,7).
                // This is intentionally not the regular lattice inferred from overlap.
                for(int y=0;y<8;y++)for(int x=0;x<8;x++)expected[y][x]=source.getRaster().getSample(x,y,0);
                for(int y=0;y<8;y++)for(int x=0;x<8;x++)expected[y+6][x+1]=source.getRaster().getSample(x,y+8,0);
                for(int y=0;y<8;y++)for(int x=0;x<8;x++)expected[y+7][x+7]=source.getRaster().getSample(x+8,y+8,0);
                var actual=server.readRegion(RegionRequest.createInstance(server.getPath(),2,0,0,30,30));
                for(int y=0;y<15;y++)for(int x=0;x<15;x++)for(int c=0;c<4;c++)
                    assertEquals(expected[y][x],actual.getRaster().getSample(x,y,c),"compressed="+compressed+" at "+x+","+y);
            }
        }
    }
    @Test void fractionalOverlapAndPartialRequestsMatchIndependentLayout() throws Exception {
        Path slide=SyntheticMrxsFixture.createConcatenated(temp,1.5);
        var metadata=SlidedatParser.parse(slide);
        try(var index=MiraxIndex.open(metadata);var server=new MrxsImageServer(slide.toUri())) {
            var source=ImageIO.read(new ByteArrayInputStream(index.readPayload(index.readTiles(1,0).getFirst())));
            // Base-camera advance 14.5 means level-1 advance 7.25.
            // Output pixel centers 7.5..14.5 are in the lower/right crop;
            // center 15.5 is outside its endpoint 15.25 and must stay blank.
            var full=server.readRegion(RegionRequest.createInstance(server.getPath(),2,0,0,31,31));
            assertEquals(16,full.getWidth());assertEquals(16,full.getHeight());
            for(int y=0;y<16;y++)for(int x=0;x<16;x++) {
                int expected;
                if(x==15 || y==15)expected=0;
                else if(y<7)expected=x<8?source.getRaster().getSample(x,y,0):0;
                else expected=source.getRaster().getSample(x<7?x:x+1,y+1,0);
                for(int c=0;c<4;c++)assertEquals(expected,full.getRaster().getSample(x,y,c),"full "+x+","+y);
            }
            // Requests on either side of the crop boundary must reproduce full-view pixels.
            for(int ox:new int[]{0,6,8,14})for(int oy:new int[]{0,6,8,14}) {
                var part=server.readRegion(RegionRequest.createInstance(server.getPath(),2,ox,oy,8,8));
                for(int y=0;y<part.getHeight();y++)for(int x=0;x<part.getWidth();x++)for(int c=0;c<4;c++)
                    assertEquals(full.getRaster().getSample(ox/2+x,oy/2+y,c),part.getRaster().getSample(x,y,c));
            }
        }
    }
    @Test void cameraCropsFollowKnownLayoutAndExcludeGrayInactiveQuadrant() throws Exception {
        Path slide=SyntheticMrxsFixture.createConcatenated(temp);
        var metadata=SlidedatParser.parse(slide);
        try(var index=MiraxIndex.open(metadata);var server=new MrxsImageServer(slide.toUri())) {
            var source=ImageIO.read(new ByteArrayInputStream(index.readPayload(index.readTiles(1,0).getFirst())));
            var actual=server.readRegion(RegionRequest.createInstance(server.getPath(),2,0,0,30,30));
            assertEquals(15,actual.getWidth());assertEquals(15,actual.getHeight());
            // Known independent layout: 16px cameras overlap 2px at base, hence
            // 8px crops advance 7px at level 1. Later bottom/right crops own overlap.
            for(int y=0;y<15;y++)for(int x=0;x<15;x++) {
                int expected;
                if(y<7)expected=x<8?source.getRaster().getSample(x,y,0):0;
                else expected=source.getRaster().getSample(x<7?x:x+1,y+1,0);
                for(int c=0;c<4;c++)assertEquals(expected,actual.getRaster().getSample(x,y,c),
                        "at "+x+","+y+" channel "+c);
            }
            // Gray is valid when stored within an active region, not a magic sentinel.
            assertEquals(128,source.getRaster().getSample(12,2,0),1);
            assertEquals(0,actual.getRaster().getSample(12,2,0));
            assertEquals(128,actual.getRaster().getSample(12,12,0),1);
            var base=server.readRegion(RegionRequest.createInstance(server.getPath(),1,2,2,1,1));
            assertEquals(20,base.getRaster().getSample(0,0,0),1);
        }
    }
}
