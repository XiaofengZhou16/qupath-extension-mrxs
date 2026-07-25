package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.Path;
import java.util.List;

record MrxsMetadata(
        Path anchorPath,
        Path slideDirectory,
        String slideId,
        String slideVersion,
        String currentSlideVersion,
        String slideType,
        int imageCountX,
        int imageCountY,
        int imageDivisionsPerSide,
        int storedBitDepth,
        int cameraBitDepth,
        double objectiveMagnification,
        List<HierarchyDimension> hierarchy,
        int zoomHierarchyIndex,
        int filterHierarchyIndex,
        int positionNonhierRecord,
        boolean compressedPositionRecord,
        List<ZoomLevel> zoomLevels,
        List<FluorescenceChannel> channels,
        List<String> dataFiles
) {
    MrxsMetadata {
        hierarchy = List.copyOf(hierarchy);
        zoomLevels = List.copyOf(zoomLevels);
        channels = List.copyOf(channels);
        dataFiles = List.copyOf(dataFiles);
    }

    record HierarchyDimension(
            int index,
            String name,
            int valueCount,
            int defaultValue,
            List<String> values,
            List<String> sections
    ) {
        HierarchyDimension {
            values = List.copyOf(values);
            sections = List.copyOf(sections);
        }
    }

    record ZoomLevel(
            int index,
            double pixelSizeXMicrons,
            double pixelSizeYMicrons,
            int imageWidth,
            int imageHeight,
            int concatFactor,
            double overlapX,
            double overlapY,
            String imageFormat
    ) {
    }

    record FluorescenceChannel(
            int index,
            String name,
            String filterLevel,
            int filterLevelIndex,
            int storedComponent,
            int colorRgb,
            double excitationNm,
            double emissionNm,
            int exposureMicros,
            boolean master
    ) {
    }
}
