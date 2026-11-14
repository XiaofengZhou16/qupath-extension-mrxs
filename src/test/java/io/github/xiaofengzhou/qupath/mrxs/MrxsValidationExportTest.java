package io.github.xiaofengzhou.qupath.mrxs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.regions.RegionRequest;

class MrxsValidationExportTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void exportsUntransformedNativeChannelPngsAndManifest() throws Exception {
        Path fixture = SyntheticMrxsFixture.create(
                temporaryDirectory.resolve("raw-fixture")
        );
        try (MrxsImageServer server = new MrxsImageServer(fixture.toUri())) {
            int x = 0;
            int y = 0;
            int width = Math.min(1024, server.getWidth());
            int height = Math.min(1024, server.getHeight());
            double requested = 1.3;
            var result = MrxsValidationExport.export(
                    server, temporaryDirectory.resolve("export"),
                    x, y, width, height, requested
            );

            assertEquals(0, result.pyramidLevel());
            assertEquals(1.0, result.downsample());
            assertEquals(server.nChannels(), result.channels().size());
            assertTrue(Files.isRegularFile(result.manifest()));
            String manifest = Files.readString(result.manifest());
            assertTrue(JsonParser.parseString(manifest).isJsonObject());
            assertTrue(manifest.contains("\"displayTransformsApplied\": false"));
            assertTrue(manifest.contains("\"nativePyramidLevel\": 0"));
            assertFalse(manifest.contains(server.getURIs().iterator().next().toString()));

            var raw = server.readRegion(RegionRequest.createInstance(
                    server.getPath(), 1.0, x, y, width, height
            ));
            for (var channel : result.channels()) {
                Path path = result.outputDirectory().resolve(channel.fileName());
                assertTrue(Files.isRegularFile(path));
                assertEquals(64, channel.sha256().length());
                var png = ImageIO.read(path.toFile());
                assertEquals(raw.getWidth(), png.getWidth());
                assertEquals(raw.getHeight(), png.getHeight());
                for (int py = 0; py < png.getHeight(); py += 31) {
                    for (int px = 0; px < png.getWidth(); px += 29) {
                        assertEquals(
                                raw.getRaster().getSample(px, py, channel.index()),
                                png.getRaster().getSample(px, py, 0)
                        );
                    }
                }
            }
        }
    }

    @Test
    void recordsDisplaySnapshotWithoutChangingRawExports() throws Exception {
        Path fixture = SyntheticMrxsFixture.create(
                temporaryDirectory.resolve("snapshot-fixture")
        );
        try (MrxsImageServer server = new MrxsImageServer(fixture.toUri())) {
            var snapshot = new MrxsValidationExport.DisplaySnapshot(
                    1.25,
                    java.util.List.of(
                            new MrxsValidationExport.DisplayChannel(
                                    "DAPI", 2.0, 120.0, true
                            )
                    )
            );
            var result = MrxsValidationExport.export(
                    server, temporaryDirectory.resolve("snapshot"),
                    0, 0, Math.min(256, server.getWidth()),
                    Math.min(256, server.getHeight()), 1.0, snapshot
            );
            String manifest = Files.readString(result.manifest());
            assertTrue(JsonParser.parseString(manifest).isJsonObject());
            assertTrue(manifest.contains("\"gamma\": 1.25000000"));
            assertTrue(manifest.contains("\"minimum\": 2.00000000"));
            assertTrue(manifest.contains("\"selected\": true"));
            assertTrue(manifest.contains("\"displayTransformsApplied\": false"));
        }
    }

    @Test
    void reportsNativePyramidGeometry() throws Exception {
        Path fixture = SyntheticMrxsFixture.create(
                temporaryDirectory.resolve("pyramid-fixture")
        );
        try (MrxsImageServer server = new MrxsImageServer(fixture.toUri())) {
            var levels = server.pyramidLevelInfo();
            assertEquals(server.nResolutions(), levels.size());
            for (int i = 0; i < levels.size(); i++) {
                var level = levels.get(i);
                assertEquals(i, level.level());
                assertEquals(server.getDownsampleForResolution(i), level.downsample());
                assertEquals(
                        (int) Math.ceil(server.getWidth() / level.downsample()),
                        level.width()
                );
                assertEquals(
                        (int) Math.ceil(server.getHeight() / level.downsample()),
                        level.height()
                );
                assertTrue(level.storedTileWidth() > 0);
                assertTrue(level.storedTileHeight() > 0);
                assertTrue(level.storedImages() > 0);
                assertEquals(
                        level.pixelSizeXMicrons()
                                / levels.getFirst().pixelSizeXMicrons(),
                        level.physicalDownsampleX(), 1e-9
                );
            }
        }
    }

    @Test
    void exportsMatchedRegionAcrossEveryNativePyramidLevel() throws Exception {
        Path fixture = SyntheticMrxsFixture.create(
                temporaryDirectory.resolve("all-level-fixture")
        );
        try (MrxsImageServer server = new MrxsImageServer(fixture.toUri())) {
            Path output = temporaryDirectory.resolve("all-level-export");
            var result = MrxsValidationExport.exportAllPyramidLevels(
                    server, output, 0, 0, server.getWidth(), server.getHeight(), null
            );

            assertEquals(server.nResolutions(), result.levels().size());
            assertTrue(Files.isRegularFile(result.manifest()));
            String manifest = Files.readString(result.manifest());
            assertTrue(JsonParser.parseString(manifest).isJsonObject());
            assertTrue(manifest.contains("\"levelCount\": 2"));
            assertTrue(manifest.contains("\"displayTransformsApplied\": false"));
            assertFalse(manifest.contains(server.getURIs().iterator().next().toString()));
            for (int level = 0; level < server.nResolutions(); level++) {
                var exported = result.levels().get(level);
                assertEquals(level, exported.pyramidLevel());
                assertEquals(server.getDownsampleForResolution(level), exported.downsample());
                assertTrue(Files.isRegularFile(exported.manifest()));
                assertEquals(server.nChannels(), exported.channels().size());
                for (var channel : exported.channels()) {
                    assertTrue(Files.isRegularFile(
                            exported.outputDirectory().resolve(channel.fileName())
                    ));
                }
            }
        }
    }

    @Test
    void exportsRealFourChannelRegionAcrossEveryNativePyramidLevel()
            throws Exception {
        try (MrxsImageServer server = new MrxsImageServer(
                TestSlides.fourChannelUri()
        )) {
            var result = MrxsValidationExport.exportAllPyramidLevels(
                    server,
                    temporaryDirectory.resolve("real-all-level-export"),
                    0,
                    0,
                    Math.min(512, server.getWidth()),
                    Math.min(512, server.getHeight()),
                    null
            );
            assertEquals(server.nResolutions(), result.levels().size());
            assertEquals(4, result.levels().getFirst().channels().size());
            assertTrue(Files.isRegularFile(result.manifest()));
            for (int level = 0; level < server.nResolutions(); level++) {
                assertEquals(level, result.levels().get(level).pyramidLevel());
                assertEquals(
                        server.getDownsampleForResolution(level),
                        result.levels().get(level).downsample()
                );
            }
        }
    }
}
