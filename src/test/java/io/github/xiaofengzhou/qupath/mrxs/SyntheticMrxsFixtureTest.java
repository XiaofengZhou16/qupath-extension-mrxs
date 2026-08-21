package io.github.xiaofengzhou.qupath.mrxs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.regions.RegionRequest;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticMrxsFixtureTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void exercisesPublicParserIndexAndMultichannelServerPipeline() throws Exception {
        Path fixture = SyntheticMrxsFixture.create(temporaryDirectory);
        MrxsMetadata metadata = SlidedatParser.parse(fixture);
        assertEquals(4, metadata.channels().size());
        assertEquals(1, metadata.zoomLevels().size());
        assertEquals(
                java.util.List.of("DAPI", "Green", "Orange", "CY5"),
                metadata.channels().stream().map(
                        MrxsMetadata.FluorescenceChannel::name
                ).toList()
        );

        try (MiraxIndex index = MiraxIndex.open(metadata)) {
            assertEquals(1, index.readTiles(0, 0).size());
            assertEquals(1, index.readTiles(0, 3).size());
        }

        try (MrxsImageServer server = new MrxsImageServer(fixture.toUri())) {
            assertEquals(4, server.nChannels());
            assertEquals(1, server.nResolutions());
            var image = server.readRegion(RegionRequest.createInstance(
                    server.getPath(), 1.0, 0, 0, 16, 16
            ));
            var raster = image.getRaster();
            int dapi = raster.getSample(8, 8, 0);
            int green = raster.getSample(8, 8, 1);
            int orange = raster.getSample(8, 8, 2);
            int cy5 = raster.getSample(8, 8, 3);
            assertTrue(dapi > green, "BGR component 0 should decode from the blue JPEG band");
            assertTrue(green > orange, "BGR component 2 should decode from the red JPEG band");
            assertTrue(cy5 > 180, "CY5 should be non-zero in its separate stored filter");
        }
    }
}
