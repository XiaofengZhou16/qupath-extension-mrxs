package io.github.xiaofengzhou.qupath.mrxs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class SlidedatParser {

    private static final String HIERARCHICAL = "HIERARCHICAL";
    private static final String GENERAL = "GENERAL";

    private SlidedatParser() {
    }

    static MrxsMetadata parse(Path input) throws IOException {
        Path anchor = input.toAbsolutePath().normalize();
        Path slideDirectory;
        if (Files.isDirectory(anchor)) {
            slideDirectory = anchor;
            anchor = Path.of(anchor + ".mrxs");
        } else {
            String filename = anchor.getFileName().toString();
            if (!filename.toLowerCase(Locale.ROOT).endsWith(".mrxs")) {
                throw new IllegalArgumentException("Expected an .mrxs file: " + anchor);
            }
            slideDirectory = anchor.resolveSibling(
                    filename.substring(0, filename.length() - ".mrxs".length())
            );
        }

        Path iniPath = slideDirectory.resolve("Slidedat.ini");
        if (!Files.isRegularFile(iniPath)) {
            throw new IOException("Missing MRXS sidecar file: " + iniPath);
        }
        IniFile ini = IniFile.read(iniPath);

        int hierarchyCount = ini.requiredInt(HIERARCHICAL, "HIER_COUNT");
        List<MrxsMetadata.HierarchyDimension> hierarchy = new ArrayList<>();
        int zoomHierarchyIndex = -1;
        int filterHierarchyIndex = -1;

        for (int i = 0; i < hierarchyCount; i++) {
            String prefix = "HIER_" + i;
            String name = ini.required(HIERARCHICAL, prefix + "_NAME");
            int count = ini.requiredInt(HIERARCHICAL, prefix + "_COUNT");
            int defaultValue = ini.optionalInt(HIERARCHICAL, prefix + "_DEFAULT", 0);
            List<String> values = new ArrayList<>();
            List<String> sections = new ArrayList<>();
            for (int valueIndex = 0; valueIndex < count; valueIndex++) {
                values.add(ini.required(
                        HIERARCHICAL,
                        prefix + "_VAL_" + valueIndex
                ));
                sections.add(ini.required(
                        HIERARCHICAL,
                        prefix + "_VAL_" + valueIndex + "_SECTION"
                ));
            }
            hierarchy.add(new MrxsMetadata.HierarchyDimension(
                    i, name, count, defaultValue, values, sections
            ));
            if ("slide zoom level".equalsIgnoreCase(name)) {
                zoomHierarchyIndex = i;
            } else if ("slide filter level".equalsIgnoreCase(name)) {
                filterHierarchyIndex = i;
            }
        }

        if (zoomHierarchyIndex < 0) {
            throw new IOException("MRXS hierarchy has no slide zoom level");
        }
        if (filterHierarchyIndex < 0) {
            throw new IOException("MRXS hierarchy has no slide filter level");
        }

        List<MrxsMetadata.ZoomLevel> zoomLevels = parseZoomLevels(
                ini, hierarchy.get(zoomHierarchyIndex)
        );
        List<MrxsMetadata.FluorescenceChannel> channels = parseChannels(
                ini, hierarchy.get(filterHierarchyIndex)
        );
        List<String> dataFiles = parseDataFiles(ini);
        PositionRecord positionRecord = findPositionRecord(ini);

        return new MrxsMetadata(
                anchor,
                slideDirectory,
                ini.required(GENERAL, "SLIDE_ID"),
                ini.optional(GENERAL, "SLIDE_VERSION", ""),
                ini.optional(GENERAL, "CURRENT_SLIDE_VERSION", ""),
                ini.optional(GENERAL, "SLIDE_TYPE", ""),
                ini.requiredInt(GENERAL, "IMAGENUMBER_X"),
                ini.requiredInt(GENERAL, "IMAGENUMBER_Y"),
                ini.optionalInt(GENERAL, "CameraImageDivisionsPerSide", 1),
                ini.optionalInt(GENERAL, "VIMSLIDE_SLIDE_BITDEPTH", 8),
                ini.optionalInt(GENERAL, "VIMSLIDE_CAMERA_REAL_BITDEPTH", 8),
                ini.optionalDouble(GENERAL, "OBJECTIVE_MAGNIFICATION", Double.NaN),
                hierarchy,
                zoomHierarchyIndex,
                filterHierarchyIndex,
                positionRecord.record(),
                positionRecord.compressed(),
                zoomLevels,
                channels,
                dataFiles
        );
    }

    private static List<MrxsMetadata.ZoomLevel> parseZoomLevels(
            IniFile ini,
            MrxsMetadata.HierarchyDimension zoomDimension
    ) {
        List<MrxsMetadata.ZoomLevel> levels = new ArrayList<>();
        for (int i = 0; i < zoomDimension.valueCount(); i++) {
            String section = zoomDimension.sections().get(i);
            levels.add(new MrxsMetadata.ZoomLevel(
                    i,
                    ini.requiredDouble(section, "MICROMETER_PER_PIXEL_X"),
                    ini.requiredDouble(section, "MICROMETER_PER_PIXEL_Y"),
                    ini.requiredInt(section, "DIGITIZER_WIDTH"),
                    ini.requiredInt(section, "DIGITIZER_HEIGHT"),
                    ini.optionalInt(section, "IMAGE_CONCAT_FACTOR", i),
                    ini.optionalDouble(section, "OVERLAP_X", 0),
                    ini.optionalDouble(section, "OVERLAP_Y", 0),
                    ini.optional(section, "IMAGE_FORMAT", "JPEG")
            ));
        }
        return levels;
    }

    private static List<MrxsMetadata.FluorescenceChannel> parseChannels(
            IniFile ini,
            MrxsMetadata.HierarchyDimension filterDimension
    ) {
        List<MrxsMetadata.FluorescenceChannel> channels = new ArrayList<>();
        for (int i = 0; i < filterDimension.valueCount(); i++) {
            String section = filterDimension.sections().get(i);
            if (!ini.hasSection(section)) {
                continue;
            }
            String name = ini.optional(section, "FILTER_NAME", null);
            if (name == null || name.isBlank()) {
                continue;
            }
            int red = ini.optionalInt(section, "COLOR_R", 255);
            int green = ini.optionalInt(section, "COLOR_G", 255);
            int blue = ini.optionalInt(section, "COLOR_B", 255);
            String filterLevel = ini.optional(
                    section,
                    "DATA_IN_THIS_FILTER_LEVEL",
                    filterDimension.values().get(i)
            );
            int filterLevelIndex = indexOfIgnoreCase(
                    filterDimension.values(), filterLevel
            );
            channels.add(new MrxsMetadata.FluorescenceChannel(
                    channels.size(),
                    name,
                    filterLevel,
                    filterLevelIndex < 0 ? i : filterLevelIndex,
                    ini.optionalInt(section, "STORING_CHANNEL_NUMBER", 0),
                    (red << 16) | (green << 8) | blue,
                    ini.optionalDouble(section, "EXCITATION_WAVELENGTH", Double.NaN),
                    ini.optionalDouble(section, "EMISSION_WAVELENGTH", Double.NaN),
                    ini.optionalInt(section, "EXPOSURE_TIME", 0),
                    Boolean.parseBoolean(
                            ini.optional(section, "IS_MASTER_FILTER", "false")
                    )
            ));
        }
        return channels;
    }

    private static int indexOfIgnoreCase(List<String> values, String target) {
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).equalsIgnoreCase(target)) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> parseDataFiles(IniFile ini) {
        int count = ini.requiredInt("DATAFILE", "FILE_COUNT");
        List<String> files = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            files.add(ini.required("DATAFILE", "FILE_" + i));
        }
        return files;
    }

    private static PositionRecord findPositionRecord(IniFile ini) {
        int count = ini.optionalInt(HIERARCHICAL, "NONHIER_COUNT", 0);
        int offset = 0;
        int stitchingOffset = -1;
        for (int i = 0; i < count; i++) {
            String name = ini.optional(HIERARCHICAL, "NONHIER_" + i + "_NAME", "");
            int valueCount = ini.optionalInt(
                    HIERARCHICAL, "NONHIER_" + i + "_COUNT", 0
            );
            if ("VIMSLIDE_POSITION_BUFFER".equalsIgnoreCase(name)) {
                return new PositionRecord(offset, false);
            }
            if ("StitchingIntensityLayer".equalsIgnoreCase(name)) {
                stitchingOffset = offset;
            }
            offset += valueCount;
        }
        return stitchingOffset < 0
                ? new PositionRecord(-1, false)
                : new PositionRecord(stitchingOffset, true);
    }

    private record PositionRecord(int record, boolean compressed) {
    }
}
