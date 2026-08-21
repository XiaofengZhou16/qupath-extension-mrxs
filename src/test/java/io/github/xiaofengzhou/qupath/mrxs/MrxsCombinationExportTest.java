package io.github.xiaofengzhou.qupath.mrxs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.roi.ROIs;
import qupath.lib.regions.ImagePlane;

class MrxsCombinationExportTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void enumeratesLargestCombinationsFirst() {
        assertEquals(7, MrxsCombinationExport.combinationCount(3));
        assertEquals(List.of(7, 3, 5, 6, 1, 2, 4),
                MrxsCombinationExport.combinationMasks(3));
        assertEquals(31, MrxsCombinationExport.combinationCount(5));
    }

    @Test
    void exportsAllCombinationsAndMasksOutsideRoi() throws Exception {
        Path fixture = SyntheticMrxsFixture.create(
                temporaryDirectory.resolve("fixture")
        );
        try (MrxsImageServer server = new MrxsImageServer(fixture.toUri())) {
            List<MrxsCombinationExport.ChannelSelection> channels = List.of(
                    new MrxsCombinationExport.ChannelSelection(
                            0, "A", 0x0000ff, 0, 255
                    ),
                    new MrxsCombinationExport.ChannelSelection(
                            1, "B", 0x00ff00, 0, 255
                    ),
                    new MrxsCombinationExport.ChannelSelection(
                            2, "C", 0xff0000, 0, 255
                    )
            );
            int size = 16;
            var roi = ROIs.createEllipseROI(
                    2, 2, 12, 12, ImagePlane.getDefaultPlane()
            );
            var result = MrxsCombinationExport.export(
                    server, temporaryDirectory.resolve("combinations"),
                    new MrxsCombinationExport.ExportRegion(
                            0, 0, size, size, roi, "ellipse"
                    ),
                    1.0, channels, null
            );

            assertEquals(7, result.combinationCount());
            assertEquals(10, result.outputs().size());
            assertTrue(Files.isRegularFile(result.manifest()));
            try (var files = Files.list(
                    result.outputDirectory().resolve("combinations")
            )) {
                assertEquals(7, files.count());
            }
            try (var files = Files.list(
                    result.outputDirectory().resolve("raw-single-channels")
            )) {
                assertEquals(3, files.count());
            }
            String manifest = Files.readString(result.manifest());
            assertTrue(manifest.contains("\"regionSource\": \"selected-roi\""));
            assertTrue(manifest.contains("\"outsideRoi\": \"black\""));

            Path allChannels;
            try (var files = Files.list(
                    result.outputDirectory().resolve("combinations")
            )) {
                allChannels = files
                        .filter(path -> path.getFileName().toString().startsWith("01-"))
                        .findFirst().orElseThrow();
            }
            var composite = ImageIO.read(allChannels.toFile());
            assertEquals(0xff000000, composite.getRGB(0, 0));
            Path rawPath = result.outputDirectory().resolve(
                    result.outputs().getFirst().file()
            );
            var raw = ImageIO.read(rawPath.toFile());
            assertEquals(0, raw.getRaster().getSample(0, 0, 0));
        }
    }

    @Test
    void rejectsUnsafeSingleImageAndCumulativeExportSizes() {
        var singleImage = assertThrows(
                java.io.IOException.class,
                () -> MrxsCombinationExport.validateExportSize(5001, 5001, 1.0, 1)
        );
        assertTrue(singleImage.getMessage().contains("Each exported image"));

        var cumulative = assertThrows(
                java.io.IOException.class,
                () -> MrxsCombinationExport.validateExportSize(2000, 2000, 1.0, 255)
        );
        assertTrue(cumulative.getMessage().contains("255 combinations"));

        var overflow = assertThrows(
                java.io.IOException.class,
                () -> MrxsCombinationExport.validateExportSize(
                        Integer.MAX_VALUE, Integer.MAX_VALUE, 0.1, 255
                )
        );
        assertTrue(overflow.getMessage().contains("calculate safely"));
    }
}
