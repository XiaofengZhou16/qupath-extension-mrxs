package io.github.xiaofengzhou.qupath.mrxs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SlidedatParserTest {

    @Test
    void parsesAlternativeHierarchyLayout() throws Exception {
        MrxsMetadata metadata = SlidedatParser.parse(TestSlides.fourChannel());

        assertEquals("24728484E8AF4DDEB39FD525173204E9", metadata.slideId());
        assertEquals(9, metadata.zoomLevels().size());
        assertEquals(1, metadata.filterHierarchyIndex());
        assertEquals(4, metadata.channels().size());
        assertEquals(31, metadata.dataFiles().size());
        assertEquals(8, metadata.imageDivisionsPerSide());
        assertEquals(
                java.util.List.of("DAPI", "SPorange", "SpGreen", "CY5"),
                metadata.channels().stream().map(
                        MrxsMetadata.FluorescenceChannel::name
                ).toList()
        );
        assertEquals(1, metadata.channels().get(3).filterLevelIndex());
        assertTrue(metadata.slideType().contains("FLUORESCENCE"));
    }
}
