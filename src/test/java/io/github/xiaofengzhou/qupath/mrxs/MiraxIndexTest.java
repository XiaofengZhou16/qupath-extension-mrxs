package io.github.xiaofengzhou.qupath.mrxs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class MiraxIndexTest {

    @Test
    void readsBothStoredFilterLevelsAndJpegPayload() throws Exception {
        MrxsMetadata metadata = SlidedatParser.parse(TestSlides.fourChannel());
        try (MiraxIndex index = MiraxIndex.open(metadata)) {
            var filter0 = index.readTiles(7, 0);
            var filter1 = index.readTiles(7, 1);

            assertEquals(21, filter0.size());
            assertEquals(21, filter1.size());
            assertTrue(filter0.getFirst().length() > 0);
            assertNotNull(ImageIO.read(new ByteArrayInputStream(
                    index.readPayload(filter0.getFirst())
            )));
            assertNotNull(ImageIO.read(new ByteArrayInputStream(
                    index.readPayload(filter1.getFirst())
            )));
        }
    }
}
