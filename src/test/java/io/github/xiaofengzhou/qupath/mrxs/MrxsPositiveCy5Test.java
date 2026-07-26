package io.github.xiaofengzhou.qupath.mrxs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.Raster;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MrxsPositiveCy5Test {

    @Test
    void findsCy5SignalInAtLeastOneStoredPyramidLevel() throws Exception {
        MrxsMetadata metadata =
                SlidedatParser.parse(TestSlides.fiveChannelPositiveCy5());
        var cy5 = metadata.channels().stream()
                .filter(channel -> channel.name().equalsIgnoreCase("CY5"))
                .findFirst()
                .orElseThrow();
        List<LevelSignal> levels = new ArrayList<>();

        try (MiraxIndex index = MiraxIndex.open(metadata)) {
            var cache = new DecodedImageCache(index, 128L * 1024 * 1024);
            for (int level = 0; level < metadata.zoomLevels().size(); level++) {
                int[] maximum = new int[3];
                long[] nonZero = new long[3];
                long sampled = 0;
                var entries = index.readTiles(level, cy5.filterLevelIndex());
                for (var entry : entries) {
                    Raster raster = cache.get(entry).getRaster();
                    for (int y = 0; y < raster.getHeight(); y++) {
                        for (int x = 0; x < raster.getWidth(); x++) {
                            for (int band = 0;
                                 band < Math.min(3, raster.getNumBands());
                                 band++) {
                                int value = raster.getSample(x, y, band);
                                maximum[band] = Math.max(maximum[band], value);
                                if (value != 0) {
                                    nonZero[band]++;
                                }
                            }
                            sampled++;
                        }
                    }
                }
                levels.add(new LevelSignal(
                        level, entries.size(), maximum, nonZero, sampled
                ));
            }
        }

        levels.forEach(level -> System.out.println(
                "CY5 level " + level.level()
                        + ": tiles=" + level.tiles()
                        + ", max=" + java.util.Arrays.toString(level.maximum())
                        + ", nonZero=" + java.util.Arrays.toString(level.nonZero())
                        + "/" + level.sampled()
        ));
        assertTrue(
                levels.stream().anyMatch(
                        level -> level.maximum()[
                                MrxsImageServer.sourceBandForStoredComponent(
                                        cy5.storedComponent(), 3
                                )
                        ] > 0
                ),
                "Expected confirmed-positive CY5 signal in at least one pyramid level"
        );

        try (MrxsImageServer server = new MrxsImageServer(
                TestSlides.fiveChannelPositiveCy5().toUri()
        )) {
            var quality = server.channelQualityReport();
            var cy5Quality = quality.channels().stream()
                    .filter(channel -> channel.name().equalsIgnoreCase("CY5"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(cy5Quality.maximum() > 0);
            assertTrue(cy5Quality.nonZeroPixels() > 0);
            assertTrue(
                    cy5Quality.status()
                            == MrxsChannelQualityReport.SignalStatus.SIGNAL_PRESENT
            );
            System.out.println("Rendered lowest-level CY5: max="
                    + cy5Quality.maximum() + ", nonZero="
                    + cy5Quality.nonZeroPixels() + "/"
                    + cy5Quality.sampledPixels());
        }
    }

    private record LevelSignal(
            int level,
            int tiles,
            int[] maximum,
            long[] nonZero,
            long sampled
    ) {
    }
}
