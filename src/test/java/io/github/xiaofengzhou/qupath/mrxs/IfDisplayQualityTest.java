package io.github.xiaofengzhou.qupath.mrxs;

import org.junit.jupiter.api.Test;
import qupath.lib.color.ColorModelFactory;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.PixelType;
import qupath.lib.io.GsonTools;
import java.awt.Point;
import java.awt.image.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class IfDisplayQualityTest {
    @Test void previewCountsFullBoundingRasterAndExplicitlyDistinguishesScope() {
        var image=raw(0,10,15,20,21,255,Float.NaN);
        var report=IfDisplayQuality.measurePreview(image,profile(10,20,1,false),()->false);
        assertEquals(7,report.sampledRoiPixels());var c=report.channels().getFirst();
        assertEquals(6,c.finitePixels());assertEquals(1,c.nonFinitePixels());
        assertEquals(1,c.belowMinimum());assertEquals(2,c.aboveMaximum());
        assertEquals(1,c.atMinimum());assertEquals(1,c.atMaximum());
        assertTrue(report.definitions().getFirst().contains("including outside-ROI pixels"));
        var text=IfDisplayQuality.previewSummary(report);
        assertTrue(text.contains("33.3333%"));assertTrue(text.contains("Not staining positivity"));
        assertTrue(text.contains("Export has separate ROI-masked QC"));
        assertThrows(CancellationException.class,()->IfDisplayQuality.measurePreview(image,profile(10,20,1,false),()->true));
    }
    private static BufferedImage raw(float... values) {
        var raster=Raster.createWritableRaster(new BandedSampleModel(DataBuffer.TYPE_FLOAT,values.length,1,1),
                new DataBufferFloat(values.clone(),values.length),new Point());
        return new BufferedImage(ColorModelFactory.createColorModel(PixelType.FLOAT32,List.of(ImageChannel.getInstance("A",0xff0000))),raster,false,null);
    }
    private static IfDisplayProfile profile(double minimum,double maximum,double gamma,boolean inverted) {
        return new IfDisplayProfile(1,gamma,false,inverted,List.of(new IfDisplayProfile.Channel(0,"A",0xff0000,minimum,maximum,true)));
    }
    @Test void excludesHolesSeparatesEndpointsAndCountsNonfiniteSamplesWithoutChangingSource() {
        var image=raw(0,10,15,20,21,255,Float.NaN,Float.POSITIVE_INFINITY);
        var before=image.getRaster().getPixels(0,0,8,1,(float[])null);
        var mask=new boolean[]{true,true,true,true,true,false,true,true};
        var report=IfDisplayQuality.measure(image,mask,profile(10,20,1,false),()->false);
        assertEquals(7,report.sampledRoiPixels());var c=report.channels().getFirst();
        assertEquals(5,c.finitePixels());assertEquals(2,c.nonFinitePixels());
        assertEquals(1,c.belowMinimum());assertEquals(1,c.atMinimum());assertEquals(1,c.aboveMaximum());assertEquals(1,c.atMaximum());
        assertEquals(0.2,c.belowMinimumFraction());assertEquals(0.2,c.aboveMaximumFraction());
        assertEquals(0,c.observedMinimum());assertEquals(21,c.observedMaximum());
        assertArrayEquals(before,image.getRaster().getPixels(0,0,8,1,(float[])null));
        assertEquals(report,IfDisplayQuality.measure(image,mask,profile(10,20,2,true),()->false));
    }
    @Test void emptyAndNonfiniteOnlyRegionsHaveUndefinedFractionsNotZero() {
        var image=raw(Float.NaN,Float.NEGATIVE_INFINITY);
        for(var mask:List.of(new boolean[]{false,false},new boolean[]{true,true})) {
            var report=IfDisplayQuality.measure(image,mask,profile(0,255,1,false),()->false);
            var c=report.channels().getFirst();assertEquals(0,c.finitePixels());
            assertNull(c.observedMinimum());assertNull(c.observedMaximum());assertNull(c.belowMinimumFraction());assertNull(c.aboveMaximumFraction());
            var json=GsonTools.getInstance().newBuilder().serializeNulls().create().toJson(report);
            assertTrue(json.contains("\"aboveMaximumFraction\":null"));
            assertTrue(IfDisplayQuality.summary(report).contains("undefined"));
        }
    }
    @Test void usesActualFloatDisplayEndpoints() {
        var report=IfDisplayQuality.measure(raw(0.1f,0.2f),new boolean[]{true,true},profile(0.1,0.2,1,false),()->false);
        var c=report.channels().getFirst();assertEquals((double)0.1f,c.effectiveMinimum());assertEquals((double)0.2f,c.effectiveMaximum());
        assertEquals(1,c.atMinimum());assertEquals(1,c.atMaximum());assertEquals(0,c.aboveMaximum());assertEquals(0,c.belowMinimum());
    }
    @Test void cancellationAndMaskMismatchFailExplicitly() {
        var image=raw(1,2);
        assertThrows(CancellationException.class,()->IfDisplayQuality.measure(image,new boolean[]{true,true},profile(0,255,1,false),()->true));
        assertThrows(IllegalArgumentException.class,()->IfDisplayQuality.measure(image,new boolean[]{true},profile(0,255,1,false),()->false));
    }
}
