package io.github.xiaofengzhou.qupath.mrxs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import qupath.lib.regions.RegionRequest;

class MrxsPerformanceTest {

    @Test
    void spatialIndexAvoidsFullLevelScan() throws Exception {
        try (MrxsImageServer server = new MrxsImageServer(
                TestSlides.fourChannelUri()
        )) {
            MrxsImageServer.IndexStats stats = null;
            int foundX = -1;
            int foundY = -1;
            outer:
            for (int y = 0; y < server.getHeight(); y += 4096) {
                for (int x = 0; x < server.getWidth(); x += 4096) {
                    var candidate = server.indexStats(0, 0, x, y, 512, 512);
                    if (candidate.candidateImages() > 0) {
                        stats = candidate;
                        foundX = x;
                        foundY = y;
                        break outer;
                    }
                }
            }
            assertNotNull(stats);
            System.out.println("Spatial index candidates: "
                    + stats.candidateImages() + "/" + stats.totalImages()
                    + " at " + foundX + "," + foundY);
            assertTrue(stats.totalImages() > 1_000);
            assertTrue(stats.candidateImages() < stats.totalImages() / 10,
                    "Spatial query did not sufficiently reduce candidates");
        }
    }

    @Test
    void concurrentReadsAreDeterministicAndShareDecodedTiles() throws Exception {
        try (MrxsImageServer server = new MrxsImageServer(
                TestSlides.fourChannelUri()
        );
             var executor = Executors.newFixedThreadPool(6)) {
            double downsample = server.getDownsampleForResolution(7);
            int x = server.getWidth() / 4;
            int y = server.getHeight() / 4;
            RegionRequest request = RegionRequest.createInstance(
                    server.getPath(), downsample, x, y, 4096, 4096
            );
            Callable<int[]> read = () -> checksum(server.readRegion(request));
            var tasks = new ArrayList<Callable<int[]>>();
            for (int i = 0; i < 12; i++) {
                tasks.add(read);
            }
            var results = executor.invokeAll(tasks);
            int[] expected = results.getFirst().get();
            for (var result : results) {
                assertArrayEquals(expected, result.get());
            }
            assertEquals(4, expected.length);
            assertTrue(server.cacheStats().imageCount() > 0);
            assertTrue(server.cacheStats().cachedBytes()
                    <= server.cacheStats().maximumBytes());
            assertTrue(server.openDataFileCount() > 0);
            assertTrue(server.openDataFileCount() < 10);
            System.out.println("Concurrent cache: " + server.cacheStats()
                    + ", openDataFiles=" + server.openDataFileCount());
        }
    }

    private static int[] checksum(BufferedImage image) {
        int bands = image.getRaster().getNumBands();
        int[] checksums = new int[bands];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                for (int band = 0; band < bands; band++) {
                    checksums[band] = checksums[band] * 31
                            + image.getRaster().getSample(x, y, band);
                }
            }
        }
        return checksums;
    }
}
