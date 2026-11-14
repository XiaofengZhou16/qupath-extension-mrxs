package io.github.xiaofengzhou.qupath.mrxs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.color.ColorModelFactory;
import qupath.lib.images.servers.*;
import qupath.lib.io.GsonTools;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.interfaces.ROI;

import java.awt.Point;
import java.awt.image.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class IfAnalysisTest {
    @TempDir Path temporary;
    private static final ImagePlane PLANE = ImagePlane.getDefaultPlane();

    private static ROI rect(double x, double y, double w, double h) {
        return ROIs.createRectangleROI(x, y, w, h, PLANE);
    }
    private static IfAnalysisProfile profile(int level) {
        return new IfAnalysisProfile(1, level, List.of(
                new IfAnalysisProfile.Marker(0, "A", 100, 100, 100),
                new IfAnalysisProfile.Marker(1, "B", 100, 100, 100),
                new IfAnalysisProfile.Marker(2, "C", 100, 100, 100)));
    }
    private static IfAnalysis.Combination combination(IfAnalysis.Result result, int mask) {
        return result.combinations().stream().filter(c -> c.bits() == mask).findFirst().orElseThrow();
    }

    @Test void invalidCompartmentGeometryIsExcludedAndInvalidRegionRejected()throws Exception {
        // Unequal lobes retain nonzero area while the boundary self-intersects.
        var invalid=ROIs.createPolygonROI(new double[]{4,12,4,8},new double[]{0,4,4,0},PLANE);
        assertFalse(invalid.isEmpty());assertFalse(IfAnalysis.validBoundary(invalid));
        var originalPoints=List.copyOf(invalid.getAllPoints());
        try(var server=new TruthServer(4)) {
            var cells=List.of(new IfAnalysis.Cell("bad-cell",invalid,rect(5,1,1,1)),
                    new IfAnalysis.Cell("bad-nucleus",rect(0,0,16,8),invalid),
                    new IfAnalysis.Cell("good",rect(20,0,4,4),rect(21,1,2,2)));
            var result=IfAnalysis.analyze(server,rect(0,0,32,8),profile(0),cells,()->false,v->{});
            assertEquals(List.of(new IfAnalysis.RejectedCell("bad-cell","eligibility","invalid_cell_geometry"),
                    new IfAnalysis.RejectedCell("bad-nucleus","eligibility","invalid_nucleus_geometry")),result.rejectedCells());
            assertEquals(1,result.cells().size());
            int reads=server.reads;
            assertThrows(IllegalArgumentException.class,()->IfAnalysis.analyze(server,invalid,profile(0),List.of(),()->false,v->{}));
            assertEquals(reads,server.reads,"Invalid analysis ROI must fail before reading pixels");
            assertEquals(originalPoints,invalid.getAllPoints(),"Do not silently repair source geometry");
            Path output=temporary.resolve("invalid-boundary-review");
            IfAnalysisExport.analyzeAndWrite(output,server,rect(0,0,32,8),profile(0),cells,
                    java.util.Map.of(),false,()->false,v->{});
            var entry=IfAnalysisHistory.scan(output,()->false).entries().getFirst();
            var exclusions=IfHistoricalExclusions.load(entry,()->false);
            assertEquals(2,exclusions.size());
            var restored=exclusions.stream().filter(item->item.id().equals("bad-cell")).findFirst().orElseThrow();
            assertEquals("invalid_cell_geometry",restored.reason());
            assertEquals(originalPoints,restored.cell().getAllPoints());
            assertFalse(IfAnalysis.validBoundary(restored.cell()));
        }
    }

    @Test void tiledAreaCountsMatchIndependentPixelEnumerationWithHoleAndOffset() throws Exception {
        try (var server = new TruthServer(7)) {
            var roi = RoiTools.difference(rect(3, 1, 28, 6), rect(10, 2, 7, 3));
            var r = IfAnalysis.analyze(server, roi, profile(0), List.of(), () -> false, v -> {});
            long[] expected = new long[8];
            for (int y = 0; y < 8; y++) for (int x = 0; x < 32; x++)
                if (roi.contains(x + 0.5, y + 0.5)) expected[x / 4]++;
            assertEquals(147, r.roiPixels());
            for (var c : r.combinations()) {
                long inclusive = 0;
                for (int mask = 0; mask < 8; mask++) if ((mask & c.bits()) == c.bits()) inclusive += expected[mask];
                assertEquals(expected[c.bits()], c.paExactPixels());
                assertEquals(inclusive, c.paPixels());
            }
            assertEquals(147 * 0.5 * 0.75 / 1e6, r.sampledAreaMm2(), 1e-12);
            assertTrue(r.tilesRead() > 1);
            assertEquals(r.tilesRead(), server.reads);
        }
    }

    @Test void rawCellCompartmentsCountOrOnceAndDistinguishExactFromInclusive() throws Exception {
        try (var server = new TruthServer(3)) {
            var cells = List.of(
                    // Spans A-only and B-only stripes; nucleus A+, cytoplasm B+.
                    new IfAnalysis.Cell("split", rect(4, 0, 8, 4), rect(4, 0, 4, 4)),
                    new IfAnalysis.Cell("triple", rect(28, 0, 4, 4), rect(29, 1, 2, 2)),
                    new IfAnalysis.Cell("boundary", rect(30, 6, 4, 3), rect(31, 7, 1, 1)),
                    new IfAnalysis.Cell("no-cytoplasm", rect(0, 0, 2, 2), rect(0, 0, 2, 2)));
            var r = IfAnalysis.analyze(server, rect(0, 0, 32, 8), profile(0), cells, () -> false, v -> {});
            assertEquals(2, r.cells().size());
            assertEquals(1, r.excludedCells());
            assertEquals(1, r.unmeasurableCells());
            assertEquals(List.of(new IfAnalysis.RejectedCell("boundary","eligibility","cell_outside_image"),
                    new IfAnalysis.RejectedCell("no-cytoplasm","measurement","no_valid_cytoplasm_pixels")),r.rejectedCells());
            assertEquals(r.inputCells(),r.cells().size()+r.rejectedCells().size());
            var split = r.cells().getFirst();
            assertEquals(1, split.npn()); assertEquals(2, split.cpn()); assertEquals(3, split.pn());
            assertEquals(List.of(200.0, 20.0, 20.0), split.nucleusMeans());
            assertEquals(List.of(20.0, 200.0, 20.0), split.cytoplasmMeans());
            assertEquals(16, split.nucleusPixels()); assertEquals(16, split.cytoplasmPixels());
            assertEquals(2, combination(r, 3).pn());
            assertEquals(1, combination(r, 3).pnExact());
            assertEquals(1, combination(r, 7).pn());
            assertEquals(2, combination(r, 1).pn()); // triple not double-counted
        }
    }

    @Test void statisticsDoNotDependOnTileSizeOrChannelDisplayColors() throws Exception {
        try (var a = new TruthServer(3); var b = new TruthServer(11)) {
            var roi = rect(1, 1, 29, 6);
            b.setMetadata(new ImageServerMetadata.Builder(b.getMetadata()).channels(List.of(
                    ImageChannel.getInstance("A", 0), ImageChannel.getInstance("B", 0),
                    ImageChannel.getInstance("C", 0))).build());
            var first = IfAnalysis.analyze(a, roi, profile(0), List.of(), () -> false, v -> {});
            var second = IfAnalysis.analyze(b, roi, profile(0), List.of(), () -> false, v -> {});
            assertEquals(first.combinations(), second.combinations());
            assertEquals(first.roiPixels(), second.roiPixels());
        }
    }

    @Test void nativeCoarseLevelUsesItsPixelCentersWithoutDoubleCounting() throws Exception {
        try (var server = new TruthServer(3)) {
            var r = IfAnalysis.analyze(server, rect(0, 0, 32, 8), profile(1), List.of(), () -> false, v -> {});
            assertEquals(64, r.roiPixels());
            assertEquals(32, combination(r, 1).paPixels());
            assertEquals(8, combination(r, 1).paExactPixels());
            assertEquals(256 * 0.5 * 0.75 / 1e6, r.sampledAreaMm2(), 1e-12);
        }
    }

    @Test void profileRejectsInvalidThresholdsDuplicatesAndChangedChannelMapping() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new IfAnalysisProfile.Marker(0, "A", Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new IfAnalysisProfile(1, 0,
                List.of(profile(0).markers().getFirst(), profile(0).markers().getFirst())));
        var p = profile(0);
        assertEquals(p, GsonTools.getInstance().fromJson(GsonTools.getInstance().toJson(p), IfAnalysisProfile.class));
        try (var server = new TruthServer(4)) {
            var wrong = new IfAnalysisProfile(1, 0, List.of(new IfAnalysisProfile.Marker(0, "Wrong", 100, 100, 100)));
            assertThrows(IllegalArgumentException.class, () -> IfAnalysis.analyze(server, rect(0,0,32,8), wrong, List.of(), () -> false, v -> {}));
            assertEquals(0, server.reads);
        }
    }

    @Test void cancellationStopsBeforeAnyReadsAndDuringTileLoop() throws Exception {
        try (var server = new TruthServer(4)) {
            assertThrows(CancellationException.class, () -> IfAnalysis.analyze(server, rect(0,0,32,8),
                    profile(0), List.of(), () -> true, v -> {}));
            assertEquals(0, server.reads);
            AtomicInteger checks = new AtomicInteger();
            assertThrows(CancellationException.class, () -> IfAnalysis.analyze(server, rect(0,0,32,8),
                    profile(0), List.of(), () -> checks.incrementAndGet() > 5, v -> {}));
            assertTrue(server.reads <= 2);
        }
    }

    @Test void rejectionAuditPreservesCellIdsAndFirstFailingReason() throws Exception {
        try(var server=new TruthServer(4)) {
            var roi=rect(0,0,16,8);
            var cells=List.of(new IfAnalysis.Cell("missing",rect(0,0,2,2),null),
                    new IfAnalysis.Cell("outside-roi",rect(20,0,4,4),rect(21,1,1,1)),
                    new IfAnalysis.Cell("bad-nucleus",rect(0,0,2,2),rect(4,0,1,1)));
            var r=IfAnalysis.analyze(server,roi,profile(0),cells,()->false,v->{});
            assertEquals(List.of("missing_nucleus_roi","cell_not_fully_inside_roi","nucleus_not_inside_cell"),
                    r.rejectedCells().stream().map(IfAnalysis.RejectedCell::reason).toList());
            var dir=temporary.resolve("rejection-audit");
            IfAnalysisExport.write(dir,"synthetic",roi,r,cells,java.util.Map.of(),()->false);
            assertEquals(4,Files.readAllLines(dir.resolve("excluded-cells.csv")).size());
            var json=GsonTools.getInstance().fromJson(Files.readString(dir.resolve("manifest.json")),com.google.gson.JsonObject.class);
            assertEquals(1,json.getAsJsonObject("cellExclusionReasonCounts").get("missing_nucleus_roi").getAsInt());
            assertEquals(3,json.get("excludedCellsWithReviewGeometry").getAsInt());
            assertEquals(0.0,json.getAsJsonObject("measurementCoverage").get("measuredFraction").getAsDouble());
            assertEquals("review_exclusions",json.getAsJsonObject("measurementCoverage").get("status").getAsString());
            assertEquals(5,json.get("excludedReviewGeometryFeatures").getAsInt());
            var review=qupath.lib.io.PathIO.readObjects(dir.resolve("excluded-cells.geojson"));
            assertEquals(5,review.size());assertTrue(review.stream().allMatch(qupath.lib.objects.PathObject::isAnnotation));
            assertEquals(1,review.stream().filter(o->"missing".equals(o.getMetadata().get("IF source cell ID"))).count());
            assertTrue(review.stream().allMatch(o->o.getMetadata().containsKey("IF exclusion reason")));
        }
    }

    @Test void exportContainsExplicitDenominatorsNullCalibrationAndDoesNotOverwrite() throws Exception {
        try (var server = new TruthServer(7)) {
            server.setMetadata(new ImageServerMetadata.Builder(server.getMetadata()).pixelSizeMicrons(Double.NaN, Double.NaN).build());
            var roi = rect(0, 0, 32, 8);
            var r = IfAnalysis.analyze(server, roi, profile(0), List.of(), () -> false, v -> {});
            Path dir = temporary.resolve("result");
            IfAnalysisExport.write(dir, "synthetic", roi, r, () -> false);
            assertFalse(Files.exists(dir.resolve("INCOMPLETE")));
            var manifest = GsonTools.getInstance().fromJson(Files.readString(dir.resolve("manifest.json")), com.google.gson.JsonObject.class);
            assertEquals("complete", manifest.get("status").getAsString());
            assertTrue(manifest.getAsJsonObject("measurementCoverage").get("measuredFraction").isJsonNull());
            assertEquals("no_cell_candidates",manifest.getAsJsonObject("measurementCoverage").get("status").getAsString());
            assertTrue(!manifest.has("sampledAreaMm2") || manifest.get("sampledAreaMm2").isJsonNull());
            String csv = Files.readString(dir.resolve("combinations.csv"));
            assertTrue(csv.contains("\"A+B\",3,inclusive,PA,64,\"positive:A\",128,0.500000000000"));
            assertTrue(csv.contains("\"A+B\",3,inclusive,PN,0,\"all_eligible_cells\",0,,,"));
            assertFalse(csv.contains("NaN"));
            assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> IfAnalysisExport.write(dir, "synthetic", roi, r, () -> false));
            var restored = GsonTools.getInstance().fromJson(Files.readString(dir.resolve("roi.json")), ROI.class);
            assertEquals(0, RoiTools.symDifference(roi, restored).getArea());
        }
    }

    @Test void realMrxsPipelineWorksAndReportsTimingWithoutBiologicalClaims() throws Exception {
        try (var server = new MrxsImageServer(TestSlides.fourChannelUri())) {
            int x = server.getWidth() / 2, y = server.getHeight() / 2;
            var roi = rect(x, y, Math.min(2048, server.getWidth() - x), Math.min(2048, server.getHeight() - y));
            var markers = java.util.stream.IntStream.range(0, server.nChannels()).mapToObj(c ->
                    new IfAnalysisProfile.Marker(c, server.getMetadata().getChannels().get(c).getName(), 30,30,30)).toList();
            var r = IfAnalysis.analyze(server, roi, new IfAnalysisProfile(1,0,markers), List.of(), () -> false, v -> {});
            assertEquals((long) roi.getArea(), r.roiPixels());
            assertEquals(15, r.combinations().size());
            System.out.printf(java.util.Locale.ROOT, "IF real ROI: %d pixels, %d tiles, %.3f s (area only; no biological ground truth)%n",
                    r.roiPixels(), r.tilesRead(), r.elapsedSeconds());
        }
    }

    /** Each four-pixel vertical stripe encodes a known A/B/C positivity mask. */
    static final class TruthServer extends AbstractTileableImageServer {
        private final ImageServerMetadata metadata;
        int reads;
        TruthServer(int tileSize) {
            metadata = new ImageServerMetadata.Builder(getClass(), "truth-" + tileSize,32,8)
                    .name("truth").rgb(false).pixelType(PixelType.UINT8).pixelSizeMicrons(0.5,0.75)
                    .channels(List.of(ImageChannel.getInstance("A",0xff0000),ImageChannel.getInstance("B",0x00ff00),
                            ImageChannel.getInstance("C",0x0000ff)))
                    .levelsFromDownsamples(1,2).preferredTileSize(tileSize,tileSize).build();
        }
        @Override protected BufferedImage readTile(TileRequest request) {
            reads++;
            int w=request.getTileWidth(),h=request.getTileHeight();
            var raster=Raster.createBandedRaster(DataBuffer.TYPE_BYTE,w,h,3,new Point());
            for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
                int sx=(int)((request.getTileX()+x+0.5)*request.getDownsample());
                for(int c=0;c<3;c++) raster.setSample(x,y,c,((sx/4)&(1<<c))!=0?200:20);
            }
            return new BufferedImage(ColorModelFactory.createColorModel(PixelType.UINT8,metadata.getChannels()),raster,false,null);
        }
        @Override public ImageServerMetadata getOriginalMetadata(){return metadata;}
        @Override protected String createID(){return "truth-"+System.identityHashCode(this);}
        @Override public String getServerType(){return "Synthetic IF ground truth";}
        @Override public Collection<URI> getURIs(){return List.of();}
        @Override protected ImageServerBuilder.ServerBuilder<BufferedImage> createServerBuilder(){return null;}
    }
}
