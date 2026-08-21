package io.github.xiaofengzhou.qupath.mrxs;

import qupath.lib.regions.RegionRequest;
import qupath.lib.display.ChannelDisplayInfo;
import qupath.lib.display.ChannelDisplayMode;
import qupath.lib.display.ImageDisplay;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.LookupOp;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

final class MrxsValidationExport {

    private static final long MAX_OUTPUT_PIXELS = 100_000_000L;

    private MrxsValidationExport() {
    }

    static ExportResult export(
            MrxsImageServer server,
            Path outputDirectory,
            int x,
            int y,
            int width,
            int height,
            double requestedDownsample
    ) throws IOException {
        return export(
                server, outputDirectory, x, y, width, height,
                requestedDownsample, null
        );
    }

    static ExportResult export(
            MrxsImageServer server,
            Path outputDirectory,
            int x,
            int y,
            int width,
            int height,
            double requestedDownsample,
            DisplaySnapshot displaySnapshot
    ) throws IOException {
        validateRegion(server, x, y, width, height);
        int level = server.closestResolution(requestedDownsample);
        double downsample = server.getDownsampleForResolution(level);
        long outputPixels = (long) Math.ceil(width / downsample)
                * (long) Math.ceil(height / downsample);
        if (outputPixels > MAX_OUTPUT_PIXELS) {
            throw new IOException(
                    "Validation export would contain " + outputPixels
                            + " pixels per channel; reduce the viewer area or zoom out."
            );
        }

        Files.createDirectories(outputDirectory);
        RegionRequest request = RegionRequest.createInstance(
                server.getPath(), downsample, x, y, width, height
        );
        BufferedImage raw = server.readRegion(request);
        Raster raster = raw.getRaster();
        List<ChannelExport> channels = new ArrayList<>(server.nChannels());
        for (int channel = 0; channel < server.nChannels(); channel++) {
            String name = server.getMetadata().getChannels().get(channel).getName();
            Path path = outputDirectory.resolve(String.format(
                    Locale.ROOT, "channel-%02d-%s.png", channel + 1, safeName(name)
            ));
            BufferedImage grayscale = grayscale(raster, channel);
            if (!ImageIO.write(grayscale, "PNG", path.toFile())) {
                throw new IOException("No PNG writer is available");
            }
            channels.add(new ChannelExport(
                    channel, name, path.getFileName().toString(), sha256(path),
                    statistics(raster, channel)
            ));
        }

        String manifest = manifestJson(
                server, x, y, width, height, requestedDownsample,
                level, downsample, raw.getWidth(), raw.getHeight(), channels,
                displaySnapshot
        );
        Path manifestPath = outputDirectory.resolve("manifest.json");
        Files.writeString(manifestPath, manifest, StandardCharsets.UTF_8);
        return new ExportResult(
                outputDirectory, manifestPath, level, downsample,
                raw.getWidth(), raw.getHeight(), channels
        );
    }

    static Path exportDisplayComposite(
            MrxsImageServer server,
            Path outputDirectory,
            int x,
            int y,
            int width,
            int height,
            double requestedDownsample,
            List<? extends ChannelDisplayInfo> selectedChannels,
            LookupOp gammaOperation
    ) throws IOException {
        validateRegion(server, x, y, width, height);
        int level = server.closestResolution(requestedDownsample);
        double downsample = server.getDownsampleForResolution(level);
        RegionRequest request = RegionRequest.createInstance(
                server.getPath(), downsample, x, y, width, height
        );
        BufferedImage raw = server.readRegion(request);
        BufferedImage composite = ImageDisplay.applyTransforms(
                raw, null, selectedChannels, ChannelDisplayMode.COLOR
        );
        if (gammaOperation != null) {
            composite = gammaOperation.filter(composite, null);
        }
        Files.createDirectories(outputDirectory);
        Path path = outputDirectory.resolve("display-composite.png");
        if (!ImageIO.write(composite, "PNG", path.toFile())) {
            throw new IOException("No PNG writer is available");
        }
        return path;
    }

    private static void validateRegion(
            MrxsImageServer server, int x, int y, int width, int height
    ) {
        if (x < 0 || y < 0 || width <= 0 || height <= 0
                || x + (long) width > server.getWidth()
                || y + (long) height > server.getHeight()) {
            throw new IllegalArgumentException("Validation region is outside the image bounds");
        }
    }

    private static BufferedImage grayscale(Raster source, int band) {
        int width = source.getWidth();
        int height = source.getHeight();
        byte[] values = new byte[width * height];
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            source.getSamples(0, y, width, 1, band, row);
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                values[offset + x] = (byte) row[x];
            }
        }
        DataBufferByte buffer = new DataBufferByte(values, values.length);
        WritableRaster raster = Raster.createInterleavedRaster(
                buffer, width, height, width, 1, new int[] {0}, null
        );
        return new BufferedImage(
                new java.awt.image.ComponentColorModel(
                        java.awt.color.ColorSpace.getInstance(
                                java.awt.color.ColorSpace.CS_GRAY
                        ),
                        new int[] {8}, false, false,
                        java.awt.Transparency.OPAQUE, DataBuffer.TYPE_BYTE
                ),
                raster, false, null
        );
    }

    private static ChannelStatistics statistics(Raster raster, int band) {
        int minimum = 255;
        int maximum = 0;
        long sum = 0;
        long nonZero = 0;
        long count = (long) raster.getWidth() * raster.getHeight();
        for (int y = 0; y < raster.getHeight(); y++) {
            for (int x = 0; x < raster.getWidth(); x++) {
                int value = raster.getSample(x, y, band);
                minimum = Math.min(minimum, value);
                maximum = Math.max(maximum, value);
                sum += value;
                if (value != 0) {
                    nonZero++;
                }
            }
        }
        return new ChannelStatistics(
                count == 0 ? 0 : minimum,
                maximum,
                count == 0 ? 0 : (double) sum / count,
                nonZero,
                count
        );
    }

    private static String manifestJson(
            MrxsImageServer server,
            int x,
            int y,
            int width,
            int height,
            double requestedDownsample,
            int level,
            double downsample,
            int outputWidth,
            int outputHeight,
            List<ChannelExport> channels,
            DisplaySnapshot displaySnapshot
    ) throws IOException {
        StringBuilder json = new StringBuilder();
        json.append("{\n")
                .append("  \"schemaVersion\": 1,\n")
                .append("  \"createdUtc\": \"").append(Instant.now()).append("\",\n")
                .append("  \"serverType\": \"")
                .append(jsonEscape(server.getServerType())).append("\",\n")
                .append("  \"sourceFile\": \"")
                .append(jsonEscape(Path.of(server.getURIs().iterator().next())
                        .getFileName().toString())).append("\",\n")
                .append("  \"sourceWidth\": ").append(server.getWidth()).append(",\n")
                .append("  \"sourceHeight\": ").append(server.getHeight()).append(",\n")
                .append("  \"region\": {\"x\": ").append(x)
                .append(", \"y\": ").append(y)
                .append(", \"width\": ").append(width)
                .append(", \"height\": ").append(height).append("},\n")
                .append("  \"requestedDownsample\": ")
                .append(format(requestedDownsample)).append(",\n")
                .append("  \"nativePyramidLevel\": ").append(level).append(",\n")
                .append("  \"nativeDownsample\": ").append(format(downsample)).append(",\n")
                .append("  \"outputWidth\": ").append(outputWidth).append(",\n")
                .append("  \"outputHeight\": ").append(outputHeight).append(",\n")
                .append("  \"pixelSizeMicronsX\": ")
                .append(format(server.getPixelCalibration().getPixelWidthMicrons()
                        * downsample)).append(",\n")
                .append("  \"pixelSizeMicronsY\": ")
                .append(format(server.getPixelCalibration().getPixelHeightMicrons()
                        * downsample)).append(",\n")
                .append("  \"displayTransformsApplied\": false,\n")
                .append("  \"displaySnapshot\": ");
        if (displaySnapshot == null) {
            json.append("null,\n");
        } else {
            json.append("{\"gamma\": ").append(format(displaySnapshot.gamma()))
                    .append(", \"channels\": [");
            for (int i = 0; i < displaySnapshot.channels().size(); i++) {
                DisplayChannel channel = displaySnapshot.channels().get(i);
                json.append("{\"name\": \"").append(jsonEscape(channel.name()))
                        .append("\", \"minimum\": ").append(format(channel.minimum()))
                        .append(", \"maximum\": ").append(format(channel.maximum()))
                        .append(", \"selected\": ").append(channel.selected())
                        .append('}');
                if (i + 1 < displaySnapshot.channels().size()) {
                    json.append(',');
                }
            }
            json.append("]},\n");
        }
        json
                .append("  \"channels\": [\n");
        for (int i = 0; i < channels.size(); i++) {
            ChannelExport channel = channels.get(i);
            ChannelStatistics statistics = channel.statistics();
            json.append("    {\"index\": ").append(channel.index())
                    .append(", \"name\": \"").append(jsonEscape(channel.name()))
                    .append("\", \"file\": \"").append(jsonEscape(channel.fileName()))
                    .append("\", \"sha256\": \"").append(channel.sha256())
                    .append("\", \"minimum\": ").append(statistics.minimum())
                    .append(", \"maximum\": ").append(statistics.maximum())
                    .append(", \"mean\": ").append(format(statistics.mean()))
                    .append(", \"nonZeroPixels\": ").append(statistics.nonZeroPixels())
                    .append(", \"pixelCount\": ").append(statistics.pixelCount())
                    .append('}');
            if (i + 1 < channels.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        json.append("  ],\n  \"pyramid\": [\n");
        List<MrxsImageServer.PyramidLevelInfo> pyramid = server.pyramidLevelInfo();
        for (int i = 0; i < pyramid.size(); i++) {
            var info = pyramid.get(i);
            json.append("    {\"level\": ").append(info.level())
                    .append(", \"downsample\": ").append(format(info.downsample()))
                    .append(", \"width\": ").append(info.width())
                    .append(", \"height\": ").append(info.height())
                    .append(", \"pixelSizeMicronsX\": ")
                    .append(format(info.pixelSizeXMicrons()))
                    .append(", \"pixelSizeMicronsY\": ")
                    .append(format(info.pixelSizeYMicrons()))
                    .append(", \"physicalDownsampleX\": ")
                    .append(format(info.physicalDownsampleX()))
                    .append(", \"physicalDownsampleY\": ")
                    .append(format(info.physicalDownsampleY()))
                    .append(", \"storedTileWidth\": ").append(info.storedTileWidth())
                    .append(", \"storedTileHeight\": ").append(info.storedTileHeight())
                    .append(", \"storedImages\": ").append(info.storedImages())
                    .append(", \"format\": \"").append(jsonEscape(info.imageFormat()))
                    .append("\"}");
            if (i + 1 < pyramid.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        return json.append("  ]\n}\n").toString();
    }

    private static String safeName(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return safe.isBlank() ? "channel" : safe;
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String format(double value) {
        if (!Double.isFinite(value)) {
            return "null";
        }
        return String.format(Locale.ROOT, "%.9g", value);
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 must be available", e);
        }
    }

    record ExportResult(
            Path outputDirectory,
            Path manifest,
            int pyramidLevel,
            double downsample,
            int outputWidth,
            int outputHeight,
            List<ChannelExport> channels
    ) {
        ExportResult {
            channels = List.copyOf(channels);
        }
    }

    record ChannelExport(
            int index,
            String name,
            String fileName,
            String sha256,
            ChannelStatistics statistics
    ) {
    }

    record ChannelStatistics(
            int minimum,
            int maximum,
            double mean,
            long nonZeroPixels,
            long pixelCount
    ) {
    }

    record DisplaySnapshot(double gamma, List<DisplayChannel> channels) {
        DisplaySnapshot {
            channels = List.copyOf(channels);
        }
    }

    record DisplayChannel(
            String name,
            double minimum,
            double maximum,
            boolean selected
    ) {
    }
}
