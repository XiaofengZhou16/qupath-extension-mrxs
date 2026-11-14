package io.github.xiaofengzhou.qupath.mrxs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.color.ColorModelFactory;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.*;
import qupath.lib.io.PathIO;
import qupath.lib.objects.PathCellObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;

import java.awt.Point;
import java.awt.image.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class IfSegmentationTest {
    @TempDir Path temporary;
    private static WrappedBufferedImageServer syntheticNuclei() {
        var channels = List.of(ImageChannel.getInstance("DAPI", 0x0000ff), ImageChannel.getInstance("A", 0x00ff00));
        var raster = Raster.createBandedRaster(DataBuffer.TYPE_BYTE, 256, 256, 2, new Point());
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) {
            boolean nucleus = false;
            for (int cy : new int[]{64, 192}) for (int cx : new int[]{64, 192})
                nucleus |= Math.hypot(x - cx, y - cy) <= 10;
            raster.setSample(x, y, 0, nucleus ? 200 : 5);
            raster.setSample(x, y, 1, x < 128 ? 180 : 5);
        }
        var image = new BufferedImage(ColorModelFactory.createColorModel(PixelType.UINT8, channels), raster, false, null);
        var server = new WrappedBufferedImageServer("nuclear truth", image, channels);
        server.setMetadata(new ImageServerMetadata.Builder(server.getMetadata()).pixelSizeMicrons(0.5, 0.5).rgb(false).build());
        return server;
    }

    @Test void watershedFindsKnownNucleiAndExportsReimportableCellsWithoutChangingSourceHierarchy() throws Exception {
        try (var server = syntheticNuclei()) {
            var roi = ROIs.createRectangleROI(0, 0, 256, 256, ImagePlane.getDefaultPlane());
            var original = new ImageData<>(server, ImageData.ImageType.FLUORESCENCE);
            var annotation = PathObjects.createAnnotationObject(roi);
            original.getHierarchy().addObject(annotation);
            var config = new IfSegmentation.Config("DAPI", 30, 0.5, 10, 400, 3);
            var segmented = IfSegmentation.detect(server, roi, config, () -> false);
            assertEquals(4, segmented.cells().size());
            assertTrue(original.getHierarchy().getDetectionObjects().isEmpty());
            assertEquals(1, original.getHierarchy().getAnnotationObjects().size());
            for (var cell : segmented.cells()) {
                assertNotNull(cell.nucleus());
                assertTrue(cell.cell().getArea() > cell.nucleus().getArea());
                assertEquals(64, Math.min(Math.abs(cell.nucleus().getCentroidX()), Math.abs(256-cell.nucleus().getCentroidX())), 2);
            }
            var profile = new IfAnalysisProfile(1, 0, List.of(new IfAnalysisProfile.Marker(1, "A", 100, 100, 100)));
            var result = IfAnalysis.analyze(server, roi, profile, segmented.cells(), () -> false, p -> {});
            assertEquals(4, result.cells().size());
            assertEquals(2, result.combinations().getFirst().pn());
            Path output = temporary.resolve("analysis");
            IfAnalysisExport.write(output, "truth", roi, result, segmented.cells(), segmented.provenance(), () -> false);
            assertFalse(Files.exists(output.resolve("INCOMPLETE")));
            var loaded = PathIO.readObjects(output.resolve("cells.geojson"));
            assertEquals(4, loaded.size());
            for (var cell : loaded) {
                assertInstanceOf(PathCellObject.class, cell);
                assertNotNull(((PathCellObject)cell).getNucleusROI());
                assertTrue(segmented.cells().stream().anyMatch(c -> c.id().equals(cell.getID().toString())));
            }
            assertTrue(Files.readString(output.resolve("segmentation.json")).contains("not AI"));
            // Segmentation must not close the shared image server.
            assertNotNull(server.readRegion(RegionRequest.createInstance(server.getPath(), 1, 0, 0, 10, 10)));
        }
    }

    @Test void cancellationAndInvalidConfigurationDoNotProduceDetections() throws Exception {
        try (var server = syntheticNuclei()) {
            var roi = ROIs.createRectangleROI(0, 0, 256, 256, ImagePlane.getDefaultPlane());
            var config = new IfSegmentation.Config("DAPI", 30, 0.5, 10, 400, 3);
            assertThrows(CancellationException.class, () -> IfSegmentation.detect(server, roi, config, () -> true));
            assertThrows(IllegalArgumentException.class, () -> new IfSegmentation.Config("DAPI", 30, 0.5, 10, 400, 0));
            assertThrows(IllegalArgumentException.class, () -> IfSegmentation.detect(server, roi,
                    new IfSegmentation.Config("missing", 30, 0.5, 10, 400, 3), () -> false));
        }
    }

    @Test void realMrxsTissueRoiRunsSegmentationAndMeasurementWithoutAccuracyClaim() throws Exception {
        try (var server = new MrxsImageServer(TestSlides.fourChannelUri())) {
            int dapi = java.util.stream.IntStream.range(0, server.nChannels()).filter(c ->
                    server.getMetadata().getChannels().get(c).getName().toUpperCase(java.util.Locale.ROOT).contains("DAPI"))
                    .findFirst().orElseThrow();
            int size=1024;
            int x=0,y=0,examined=0; double bestVariance=-1;
            // Low-resolution backgrounds are not reliable tissue masks. Check stored native patches.
            outer: for(int sy=0;sy+size<=server.getHeight();sy+=4096) for(int sx=0;sx+size<=server.getWidth();sx+=4096) {
                if(server.indexStats(0,0,sx,sy,size,size).candidateImages()==0) continue;
                var patch=server.readRegion(RegionRequest.createInstance(server.getPath(),1,sx,sy,size,size));
                double sum=0,squares=0;int n=0;
                for(int py=0;py<size;py+=4) for(int px=0;px<size;px+=4) {
                    double v=patch.getRaster().getSampleDouble(px,py,dapi);sum+=v;squares+=v*v;n++;
                }
                double variance=squares/n-Math.pow(sum/n,2);
                if(variance>bestVariance) {bestVariance=variance;x=sx;y=sy;}
                if(++examined>=256) break outer;
            }
            assertTrue(bestVariance>0, "Fixture must contain native nuclear-channel texture");
            System.out.println("Native ROI " + x + "," + y + " variance=" + bestVariance + " calibration=" + server.getPixelCalibration());
            var roi=ROIs.createRectangleROI(x,y,size,size,ImagePlane.getDefaultPlane());
            var segmented=IfSegmentation.detect(server,roi,new IfSegmentation.Config(
                    server.getMetadata().getChannels().get(dapi).getName(),10,0.5,10,400,3),()->false);
            assertFalse(segmented.cells().isEmpty(), "Runtime smoke test must exercise real detected cells");
            var markers=java.util.stream.IntStream.range(0,server.nChannels()).filter(c->c!=dapi).mapToObj(c->
                    new IfAnalysisProfile.Marker(c,server.getMetadata().getChannels().get(c).getName(),30,30,30)).toList();
            var result=IfAnalysis.analyze(server,roi,new IfAnalysisProfile(1,0,markers),segmented.cells(),()->false,p->{});
            assertFalse(result.cells().isEmpty());
            System.out.printf(java.util.Locale.ROOT,
                    "IF real segmentation smoke ROI=(%d,%d,%d,%d): detected=%d eligible=%d segmentation=%.3f s analysis=%.3f s; NOT validated accuracy%n",
                    x,y,size,size,segmented.cells().size(),result.cells().size(),
                    (Double)segmented.provenance().get("elapsedSeconds"),result.elapsedSeconds());
        }
    }
}
