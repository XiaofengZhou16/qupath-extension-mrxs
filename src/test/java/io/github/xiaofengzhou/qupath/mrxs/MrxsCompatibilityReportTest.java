package io.github.xiaofengzhou.qupath.mrxs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MrxsCompatibilityReportTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsSupportedMetadataWithoutWarnings() throws Exception {
        MrxsCompatibilityReport report =
                MrxsCompatibilityReport.assess(metadata(8, "JPEG", true));

        assertTrue(report.isSupported());
        assertTrue(report.findings().isEmpty());
        assertTrue(report.format().contains("Supported: YES"));
    }

    @Test
    void aggregatesActionableCompatibilityErrors() throws Exception {
        MrxsCompatibilityReport report =
                MrxsCompatibilityReport.assess(metadata(16, "JPEG2000", false));

        assertFalse(report.isSupported());
        assertTrue(hasCode(report, "UNSUPPORTED_BIT_DEPTH"));
        assertTrue(hasCode(report, "UNSUPPORTED_COMPRESSION"));
        assertTrue(hasCode(report, "MISSING_DATA_FILE"));
        assertThrows(MrxsCompatibilityException.class, report::requireSupported);
    }

    private MrxsMetadata metadata(
            int storedBitDepth,
            String imageFormat,
            boolean createDataFile
    ) throws Exception {
        Path anchor = temporaryDirectory.resolve("fixture.mrxs");
        Path directory = temporaryDirectory.resolve("fixture");
        Files.createDirectories(directory);
        Files.createFile(anchor);
        Files.createFile(directory.resolve("Index.dat"));
        if (createDataFile) {
            Files.createFile(directory.resolve("Data0000.dat"));
        }
        var zoom = new MrxsMetadata.HierarchyDimension(
                0, "Slide zoom level", 1, 0, List.of("0"), List.of("Zoom0")
        );
        var filter = new MrxsMetadata.HierarchyDimension(
                1, "Slide filter level", 1, 0, List.of("DAPI"), List.of("DAPI")
        );
        return new MrxsMetadata(
                anchor,
                directory,
                "01234567890123456789012345678901",
                "1",
                "1",
                "FLUORESCENCE",
                8,
                8,
                1,
                storedBitDepth,
                storedBitDepth,
                20,
                List.of(zoom, filter),
                0,
                1,
                0,
                false,
                List.of(new MrxsMetadata.ZoomLevel(
                        0, 0.25, 0.25, 256, 256, 0, 0, 0, imageFormat
                )),
                List.of(new MrxsMetadata.FluorescenceChannel(
                        0, "DAPI", "DAPI", 0, 0, 0x0000ff,
                        405, 460, 100, true
                )),
                List.of("Data0000.dat")
        );
    }

    private static boolean hasCode(MrxsCompatibilityReport report, String code) {
        return report.findings().stream().anyMatch(
                finding -> finding.code().equals(code)
        );
    }
}
