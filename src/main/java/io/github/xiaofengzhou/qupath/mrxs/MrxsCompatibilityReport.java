package io.github.xiaofengzhou.qupath.mrxs;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A structured, non-destructive compatibility assessment for an MRXS dataset.
 */
record MrxsCompatibilityReport(
        String slideVersion,
        String slideType,
        int storedBitDepth,
        int cameraBitDepth,
        int channelCount,
        int resolutionCount,
        List<Finding> findings
) {
    MrxsCompatibilityReport {
        findings = List.copyOf(findings);
    }

    static MrxsCompatibilityReport assess(MrxsMetadata metadata) {
        List<Finding> findings = new ArrayList<>();

        if (!Files.isRegularFile(metadata.anchorPath())) {
            findings.add(Finding.error(
                    "MISSING_ANCHOR_FILE",
                    "The .mrxs anchor file is missing."
            ));
        }
        if (!Files.isRegularFile(metadata.slideDirectory().resolve("Index.dat"))) {
            findings.add(Finding.error(
                    "MISSING_INDEX",
                    "The companion directory does not contain Index.dat."
            ));
        }
        if (metadata.storedBitDepth() != 8) {
            findings.add(Finding.error(
                    "UNSUPPORTED_BIT_DEPTH",
                    "Stored bit depth is " + metadata.storedBitDepth()
                            + "; only 8-bit data is currently supported."
            ));
        }
        if (metadata.cameraBitDepth() != metadata.storedBitDepth()) {
            findings.add(Finding.warning(
                    "CAMERA_STORAGE_BIT_DEPTH_DIFFER",
                    "Camera bit depth is " + metadata.cameraBitDepth()
                            + " but stored bit depth is " + metadata.storedBitDepth() + "."
            ));
        }
        if (metadata.imageDivisionsPerSide() <= 0
                || metadata.imageCountX() % metadata.imageDivisionsPerSide() != 0
                || metadata.imageCountY() % metadata.imageDivisionsPerSide() != 0) {
            findings.add(Finding.error(
                    "INVALID_CAMERA_GEOMETRY",
                    "Camera image divisions do not evenly divide the image grid."
            ));
        }
        if (metadata.zoomLevels().isEmpty()) {
            findings.add(Finding.error(
                    "NO_RESOLUTION_LEVELS",
                    "No slide zoom levels were found."
            ));
        }
        for (var level : metadata.zoomLevels()) {
            if (!"JPEG".equalsIgnoreCase(level.imageFormat())) {
                findings.add(Finding.error(
                        "UNSUPPORTED_COMPRESSION",
                        "Zoom " + level.index() + " uses " + level.imageFormat()
                                + "; only JPEG is currently supported."
                ));
            }
            if (level.concatFactor() < 0 || level.concatFactor() > 20) {
                findings.add(Finding.error(
                        "INVALID_CONCAT_FACTOR",
                        "Zoom " + level.index() + " has concat exponent "
                                + level.concatFactor() + "."
                ));
            }
            if (!Double.isFinite(level.pixelSizeXMicrons())
                    || !Double.isFinite(level.pixelSizeYMicrons())
                    || level.pixelSizeXMicrons() <= 0
                    || level.pixelSizeYMicrons() <= 0) {
                findings.add(Finding.error(
                        "INVALID_PIXEL_SIZE",
                        "Zoom " + level.index() + " has an invalid pixel size."
                ));
            }
            if (level.imageWidth() <= 0 || level.imageHeight() <= 0) {
                findings.add(Finding.error(
                        "INVALID_TILE_SIZE",
                        "Zoom " + level.index() + " has a non-positive tile size."
                ));
            }
        }
        if (!metadata.zoomLevels().isEmpty()) {
            var base = metadata.zoomLevels().getFirst();
            long cumulative = 1;
            long baseCumulative = -1;
            double maximumDifference = 0;
            int maximumDifferenceLevel = -1;
            double maximumPhysicalDownsampleX = Double.NaN;
            double maximumPhysicalDownsampleY = Double.NaN;
            double maximumGeometryDownsample = Double.NaN;
            for (int i = 0; i < metadata.zoomLevels().size(); i++) {
                var level = metadata.zoomLevels().get(i);
                cumulative = Math.multiplyExact(cumulative, 1L << level.concatFactor());
                if (i == 0) {
                    baseCumulative = cumulative;
                }
                double geometryDownsample = (double) cumulative / baseCumulative;
                double physicalDownsampleX = level.pixelSizeXMicrons()
                        / base.pixelSizeXMicrons();
                double physicalDownsampleY = level.pixelSizeYMicrons()
                        / base.pixelSizeYMicrons();
                double relativeDifference = Math.max(
                        relativeDifference(geometryDownsample, physicalDownsampleX),
                        relativeDifference(geometryDownsample, physicalDownsampleY)
                );
                if (relativeDifference > maximumDifference) {
                    maximumDifference = relativeDifference;
                    maximumDifferenceLevel = level.index();
                    maximumPhysicalDownsampleX = physicalDownsampleX;
                    maximumPhysicalDownsampleY = physicalDownsampleY;
                    maximumGeometryDownsample = geometryDownsample;
                }
            }
            if (maximumDifference > 0.001) {
                findings.add(Finding.warning(
                        "PYRAMID_PIXEL_SIZE_MISMATCH",
                        String.format(
                                Locale.ROOT,
                                "Maximum geometry/physical pixel-size mismatch is "
                                        + "%.4f%% at zoom %d (geometry %.6f; physical "
                                        + "ratios %.6f x %.6f). Geometry controls tile "
                                        + "placement; both values are reported for validation.",
                                maximumDifference * 100.0, maximumDifferenceLevel,
                                maximumGeometryDownsample,
                                maximumPhysicalDownsampleX, maximumPhysicalDownsampleY
                        )
                ));
            }
        }

        if (metadata.channels().isEmpty()) {
            findings.add(Finding.error(
                    "NO_FLUORESCENCE_CHANNELS",
                    "No fluorescence channels were discovered."
            ));
        }
        int filterCount = metadata.filterHierarchyIndex() < 0
                ? 0
                : metadata.hierarchy().get(metadata.filterHierarchyIndex()).valueCount();
        Set<String> names = new HashSet<>();
        Set<String> storageMappings = new HashSet<>();
        for (var channel : metadata.channels()) {
            if (!names.add(channel.name().toLowerCase(Locale.ROOT))) {
                findings.add(Finding.warning(
                        "DUPLICATE_CHANNEL_NAME",
                        "Channel name is duplicated: " + channel.name()
                ));
            }
            if (channel.filterLevelIndex() < 0
                    || channel.filterLevelIndex() >= filterCount) {
                findings.add(Finding.error(
                        "INVALID_FILTER_LEVEL",
                        "Channel " + channel.name() + " maps to filter level "
                                + channel.filterLevelIndex() + "."
                ));
            }
            if (channel.storedComponent() < 0 || channel.storedComponent() > 2) {
                findings.add(Finding.error(
                        "UNSUPPORTED_PACKED_COMPONENT",
                        "Channel " + channel.name() + " uses packed component "
                                + channel.storedComponent() + "."
                ));
            }
            String mapping = channel.filterLevelIndex() + ":"
                    + channel.storedComponent();
            if (!storageMappings.add(mapping)) {
                findings.add(Finding.warning(
                        "DUPLICATE_STORAGE_MAPPING",
                        "Multiple channels map to stored component " + mapping + "."
                ));
            }
        }

        if (metadata.positionNonhierRecord() < 0) {
            findings.add(Finding.warning(
                    "NO_STITCHING_POSITIONS",
                    "No stitching-position record was found; regular-grid placement will be used."
            ));
        }
        for (String dataFile : metadata.dataFiles()) {
            if (!Files.isRegularFile(metadata.slideDirectory().resolve(dataFile))) {
                findings.add(Finding.error(
                        "MISSING_DATA_FILE",
                        "Required data file is missing: " + dataFile
                ));
            }
        }

        return new MrxsCompatibilityReport(
                metadata.currentSlideVersion().isBlank()
                        ? metadata.slideVersion()
                        : metadata.currentSlideVersion(),
                metadata.slideType(),
                metadata.storedBitDepth(),
                metadata.cameraBitDepth(),
                metadata.channels().size(),
                metadata.zoomLevels().size(),
                findings
        );
    }

    boolean isSupported() {
        return findings.stream().noneMatch(finding -> finding.severity() == Severity.ERROR);
    }

    void requireSupported() throws IOException {
        if (!isSupported()) {
            throw new MrxsCompatibilityException(this);
        }
    }

    String summary() {
        long errors = findings.stream()
                .filter(finding -> finding.severity() == Severity.ERROR).count();
        long warnings = findings.stream()
                .filter(finding -> finding.severity() == Severity.WARNING).count();
        return "supported=" + isSupported()
                + ", channels=" + channelCount
                + ", resolutions=" + resolutionCount
                + ", storedBitDepth=" + storedBitDepth
                + ", errors=" + errors
                + ", warnings=" + warnings;
    }

    String format() {
        StringBuilder text = new StringBuilder();
        text.append("MRXS compatibility\n")
                .append("==================\n")
                .append("Supported: ").append(isSupported() ? "YES" : "NO").append('\n')
                .append("Slide type: ").append(blankAsUnknown(slideType)).append('\n')
                .append("Slide version: ").append(blankAsUnknown(slideVersion)).append('\n')
                .append("Stored/camera bit depth: ")
                .append(storedBitDepth).append('/').append(cameraBitDepth).append('\n')
                .append("Channels: ").append(channelCount).append('\n')
                .append("Pyramid resolutions: ").append(resolutionCount).append('\n');
        if (findings.isEmpty()) {
            text.append("\nNo compatibility findings.\n");
        } else {
            text.append("\nFindings\n--------\n");
            for (Finding finding : findings) {
                text.append('[').append(finding.severity()).append("] ")
                        .append(finding.code()).append(": ")
                        .append(finding.message()).append('\n');
            }
        }
        return text.toString();
    }

    private static String blankAsUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static double relativeDifference(double first, double second) {
        if (!Double.isFinite(first) || !Double.isFinite(second)
                || first <= 0 || second <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.abs(first - second) / Math.max(first, second);
    }

    enum Severity {
        WARNING,
        ERROR
    }

    record Finding(Severity severity, String code, String message) {
        static Finding warning(String code, String message) {
            return new Finding(Severity.WARNING, code, message);
        }

        static Finding error(String code, String message) {
            return new Finding(Severity.ERROR, code, message);
        }
    }
}
