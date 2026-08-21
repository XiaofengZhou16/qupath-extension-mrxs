package io.github.xiaofengzhou.qupath.mrxs;

import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.LookupOp;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class MrxsCombinationExport {

    static final int MAX_CHANNELS = 8;
    static final long MAX_OUTPUT_PIXELS = 25_000_000L;
    private static final long MAX_TOTAL_OUTPUT_PIXELS = 500_000_000L;

    private MrxsCombinationExport() {
    }

    static ExportResult export(
            MrxsImageServer server,
            Path outputDirectory,
            ExportRegion region,
            double requestedDownsample,
            List<ChannelSelection> channels,
            LookupOp gammaOperation
    ) throws IOException {
        if (channels.isEmpty()) {
            throw new IllegalArgumentException("Select at least one channel");
        }
        if (channels.size() > MAX_CHANNELS) {
            throw new IllegalArgumentException(
                    "All-combination export supports at most " + MAX_CHANNELS + " channels"
            );
        }
        int level = server.closestResolution(requestedDownsample);
        double downsample = server.getDownsampleForResolution(level);
        int combinationCount = combinationCount(channels.size());
        validateExportSize(
                region.width(), region.height(), downsample, combinationCount
        );

        Files.createDirectories(outputDirectory);
        Path rawDirectory = outputDirectory.resolve("raw-single-channels");
        Path combinationsDirectory = outputDirectory.resolve("combinations");
        Files.createDirectories(rawDirectory);
        Files.createDirectories(combinationsDirectory);

        BufferedImage raw = server.readRegion(RegionRequest.createInstance(
                server.getPath(), downsample,
                region.x(), region.y(), region.width(), region.height()
        ));
        boolean[] mask = createMask(
                region.roi(), region.x(), region.y(), downsample,
                raw.getWidth(), raw.getHeight()
        );

        List<OutputFile> outputs = new ArrayList<>();
        for (ChannelSelection channel : channels) {
            BufferedImage grayscale = rawGrayscale(
                    raw.getRaster(), channel.serverChannelIndex(), mask
            );
            String filename = String.format(
                    Locale.ROOT, "%02d-%s-raw.png",
                    channel.serverChannelIndex() + 1, safeName(channel.name())
            );
            Path path = rawDirectory.resolve(filename);
            writePng(grayscale, path);
            outputs.add(new OutputFile(
                    "raw", List.of(channel.name()),
                    outputDirectory.relativize(path).toString()
            ));
        }

        List<Integer> masks = combinationMasks(channels.size());
        for (int sequence = 0; sequence < masks.size(); sequence++) {
            int bits = masks.get(sequence);
            List<ChannelSelection> selectedChannels = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (int i = 0; i < channels.size(); i++) {
                if ((bits & (1 << i)) != 0) {
                    selectedChannels.add(channels.get(i));
                    names.add(channels.get(i).name());
                }
            }
            BufferedImage composite = composite(raw.getRaster(), selectedChannels);
            if (gammaOperation != null) {
                composite = gammaOperation.filter(composite, null);
            }
            applyMask(composite, mask);
            String filename = String.format(
                    Locale.ROOT, "%02d-%s.png", sequence + 1,
                    safeName(String.join("+", names))
            );
            Path path = combinationsDirectory.resolve(filename);
            writePng(composite, path);
            outputs.add(new OutputFile(
                    "composite", List.copyOf(names),
                    outputDirectory.relativize(path).toString()
            ));
        }

        Path manifest = outputDirectory.resolve("manifest.json");
        Files.writeString(
                manifest,
                manifest(server, region, level, downsample, raw, channels, outputs),
                StandardCharsets.UTF_8
        );
        return new ExportResult(
                outputDirectory, manifest, level, downsample,
                raw.getWidth(), raw.getHeight(), combinationCount,
                List.copyOf(outputs)
        );
    }

    static ExportSize validateExportSize(
            int sourceWidth,
            int sourceHeight,
            double downsample,
            int combinationCount
    ) throws IOException {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw new IllegalArgumentException("Export dimensions must be positive");
        }
        if (!Double.isFinite(downsample) || downsample <= 0) {
            throw new IllegalArgumentException("Downsample must be finite and positive");
        }
        if (combinationCount <= 0) {
            throw new IllegalArgumentException("Combination count must be positive");
        }
        long outputWidth = (long) Math.ceil(sourceWidth / downsample);
        long outputHeight = (long) Math.ceil(sourceHeight / downsample);
        try {
            long outputPixels = Math.multiplyExact(outputWidth, outputHeight);
            long totalPixels = Math.multiplyExact(outputPixels, combinationCount);
            if (outputPixels > MAX_OUTPUT_PIXELS) {
                throw new IOException(String.format(
                        Locale.ROOT,
                        "Each exported image would contain about %.2f million pixels "
                                + "(limit %.2f million). Reduce the ROI or zoom out.",
                        outputPixels / 1_000_000.0,
                        MAX_OUTPUT_PIXELS / 1_000_000.0
                ));
            }
            if (totalPixels > MAX_TOTAL_OUTPUT_PIXELS) {
                throw new IOException(String.format(
                        Locale.ROOT,
                        "Export would create %d combinations and about %.2f billion "
                                + "output pixels. Reduce the ROI, zoom out, or select fewer channels.",
                        combinationCount, totalPixels / 1_000_000_000.0
                ));
            }
            return new ExportSize(outputWidth, outputHeight, outputPixels, totalPixels);
        } catch (ArithmeticException e) {
            throw new IOException(
                    "Export dimensions are too large to calculate safely; reduce the ROI.", e
            );
        }
    }

    static int combinationCount(int channelCount) {
        if (channelCount < 1 || channelCount > MAX_CHANNELS) {
            throw new IllegalArgumentException("Channel count must be between 1 and 8");
        }
        return (1 << channelCount) - 1;
    }

    static List<Integer> combinationMasks(int channelCount) {
        int count = combinationCount(channelCount);
        List<Integer> masks = new ArrayList<>(count);
        for (int mask = 1; mask <= count; mask++) {
            masks.add(mask);
        }
        masks.sort((first, second) -> {
            int bySize = Integer.compare(
                    Integer.bitCount(second), Integer.bitCount(first)
            );
            return bySize != 0 ? bySize : Integer.compare(first, second);
        });
        return List.copyOf(masks);
    }

    private static boolean[] createMask(
            ROI roi, int regionX, int regionY, double downsample,
            int width, int height
    ) {
        if (roi == null) {
            return null;
        }
        boolean[] mask = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            double sourceY = regionY + (y + 0.5) * downsample;
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                double sourceX = regionX + (x + 0.5) * downsample;
                mask[offset + x] = roi.contains(sourceX, sourceY);
            }
        }
        return mask;
    }

    private static BufferedImage rawGrayscale(
            Raster source, int band, boolean[] mask
    ) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage image = new BufferedImage(
                width, height, BufferedImage.TYPE_BYTE_GRAY
        );
        var target = image.getRaster();
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            source.getSamples(0, y, width, 1, band, row);
            if (mask != null) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    if (!mask[offset + x]) {
                        row[x] = 0;
                    }
                }
            }
            target.setSamples(0, y, width, 1, 0, row);
        }
        return image;
    }

    private static BufferedImage composite(
            Raster source, List<ChannelSelection> channels
    ) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int red = 0;
                int green = 0;
                int blue = 0;
                for (ChannelSelection channel : channels) {
                    int value = source.getSample(x, y, channel.serverChannelIndex());
                    double scaled = rescale(
                            value, channel.minimumDisplay(), channel.maximumDisplay()
                    );
                    red = Math.min(255, red + (int) Math.round(
                            scaled * ((channel.colorRgb() >> 16) & 0xff)
                    ));
                    green = Math.min(255, green + (int) Math.round(
                            scaled * ((channel.colorRgb() >> 8) & 0xff)
                    ));
                    blue = Math.min(255, blue + (int) Math.round(
                            scaled * (channel.colorRgb() & 0xff)
                    ));
                }
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        return image;
    }

    private static double rescale(int value, double minimum, double maximum) {
        if (!(maximum > minimum)) {
            return 0;
        }
        return Math.max(0, Math.min(1, (value - minimum) / (maximum - minimum)));
    }

    private static void applyMask(BufferedImage image, boolean[] mask) {
        if (mask == null) {
            return;
        }
        int black = 0xff000000;
        for (int y = 0; y < image.getHeight(); y++) {
            int offset = y * image.getWidth();
            for (int x = 0; x < image.getWidth(); x++) {
                if (!mask[offset + x]) {
                    image.setRGB(x, y, black);
                }
            }
        }
    }

    private static void writePng(BufferedImage image, Path path) throws IOException {
        if (!ImageIO.write(image, "PNG", path.toFile())) {
            throw new IOException("No PNG writer is available");
        }
    }

    private static String manifest(
            MrxsImageServer server,
            ExportRegion region,
            int level,
            double downsample,
            BufferedImage image,
            List<ChannelSelection> channels,
            List<OutputFile> outputs
    ) {
        StringBuilder json = new StringBuilder();
        json.append("{\n")
                .append("  \"schemaVersion\": 1,\n")
                .append("  \"createdUtc\": \"").append(Instant.now()).append("\",\n")
                .append("  \"sourceFile\": \"")
                .append(escape(Path.of(server.getURIs().iterator().next())
                        .getFileName().toString())).append("\",\n")
                .append("  \"regionSource\": \"")
                .append(region.roi() == null ? "visible-view" : "selected-roi")
                .append("\",\n")
                .append("  \"regionName\": \"").append(escape(region.name()))
                .append("\",\n")
                .append("  \"region\": {\"x\": ").append(region.x())
                .append(", \"y\": ").append(region.y())
                .append(", \"width\": ").append(region.width())
                .append(", \"height\": ").append(region.height()).append("},\n")
                .append("  \"nativePyramidLevel\": ").append(level).append(",\n")
                .append("  \"downsample\": ").append(number(downsample)).append(",\n")
                .append("  \"outputWidth\": ").append(image.getWidth()).append(",\n")
                .append("  \"outputHeight\": ").append(image.getHeight()).append(",\n")
                .append("  \"outsideRoi\": \"black\",\n")
                .append("  \"channels\": [\n");
        for (int i = 0; i < channels.size(); i++) {
            ChannelSelection channel = channels.get(i);
            json.append("    {\"serverIndex\": ")
                    .append(channel.serverChannelIndex())
                    .append(", \"name\": \"").append(escape(channel.name()))
                    .append("\", \"minimum\": ")
                    .append(number(channel.minimumDisplay()))
                    .append(", \"maximum\": ")
                    .append(number(channel.maximumDisplay()))
                    .append(", \"colorRgb\": ")
                    .append(channel.colorRgb()).append('}');
            if (i + 1 < channels.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        json.append("  ],\n  \"outputs\": [\n");
        for (int i = 0; i < outputs.size(); i++) {
            OutputFile output = outputs.get(i);
            json.append("    {\"type\": \"").append(output.type())
                    .append("\", \"channels\": [");
            for (int j = 0; j < output.channels().size(); j++) {
                json.append('"').append(escape(output.channels().get(j))).append('"');
                if (j + 1 < output.channels().size()) {
                    json.append(',');
                }
            }
            json.append("], \"file\": \"").append(escape(output.file()))
                    .append("\"}");
            if (i + 1 < outputs.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        return json.append("  ]\n}\n").toString();
    }

    private static String safeName(String value) {
        String safe = value.replaceAll("[^\\p{L}\\p{N}._+-]+", "-")
                .replaceAll("^-+|-+$", "");
        return safe.isBlank() ? "channels" : safe;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String number(double value) {
        return Double.isFinite(value)
                ? String.format(Locale.ROOT, "%.9g", value)
                : "null";
    }

    record ExportRegion(int x, int y, int width, int height, ROI roi, String name) {
        ExportRegion {
            name = name == null || name.isBlank() ? "ROI" : name;
        }
    }

    record ChannelSelection(
            int serverChannelIndex,
            String name,
            int colorRgb,
            double minimumDisplay,
            double maximumDisplay
    ) {
    }

    record OutputFile(String type, List<String> channels, String file) {
        OutputFile {
            channels = List.copyOf(channels);
        }
    }

    record ExportSize(
            long outputWidth,
            long outputHeight,
            long outputPixels,
            long totalPixels
    ) {
    }

    record ExportResult(
            Path outputDirectory,
            Path manifest,
            int pyramidLevel,
            double downsample,
            int outputWidth,
            int outputHeight,
            int combinationCount,
            List<OutputFile> outputs
    ) {
        ExportResult {
            outputs = List.copyOf(outputs);
        }
    }
}
