package io.github.xiaofengzhou.qupath.mrxs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import qupath.lib.regions.RegionRequest;

class MrxsImageServerTest {

    @Test
    void readsFourChannelLowestResolutionImage() throws Exception {
        try (MrxsImageServer server = new MrxsImageServer(
                TestSlides.fourChannelUri()
        )) {
            assertEquals(4, server.nChannels());
            assertEquals(9, server.nResolutions());
            assertEquals("DAPI", server.getMetadata().getChannels().get(0).getName());
            assertEquals("CY5", server.getMetadata().getChannels().get(3).getName());
            assertTrue(server.compatibilityReport().isSupported());

            double downsample = server.getDownsampleForResolution(8);
            int width = (int) Math.ceil(server.getWidth() / downsample);
            int height = (int) Math.ceil(server.getHeight() / downsample);
            var image = server.readRegion(RegionRequest.createInstance(
                    server.getPath(), downsample, 0, 0,
                    server.getWidth(), server.getHeight()
            ));

            assertEquals(width, image.getWidth(), 1);
            assertEquals(height, image.getHeight(), 1);
            assertEquals(4, image.getRaster().getNumBands());
            int[] maxima = new int[4];
            for (int channel = 0; channel < 3; channel++) {
                maxima[channel] = maximum(image.getRaster(), channel);
                assertTrue(maxima[channel] > 0,
                        "Expected non-zero signal in channel " + channel);
            }
            maxima[3] = maximum(image.getRaster(), 3);
            System.out.println("Four-channel sample maxima: "
                    + java.util.Arrays.toString(maxima));

            var quality = server.channelQualityReport();
            assertEquals(4, quality.channels().size());
            assertTrue(quality.channels().get(0).maximum() > 0);
            assertTrue(server.diagnosticReport(true).contains("Channel quality sample"));
        }
    }

    @Test
    void readsFiveChannelSample() throws Exception {
        try (MrxsImageServer server = new MrxsImageServer(
                TestSlides.fiveChannelUri()
        )) {
            assertEquals(5, server.nChannels());
            assertEquals(10, server.nResolutions());
            double downsample = server.getDownsampleForResolution(9);
            var image = server.readRegion(RegionRequest.createInstance(
                    server.getPath(), downsample, 0, 0,
                    server.getWidth(), server.getHeight()
            ));
            assertEquals(5, image.getRaster().getNumBands());
            int[] maxima = new int[5];
            for (int channel = 0; channel < maxima.length; channel++) {
                maxima[channel] = maximum(image.getRaster(), channel);
            }
            System.out.println("Five-channel sample maxima: "
                    + java.util.Arrays.toString(maxima));
            for (int channel : new int[] {0, 1, 2, 4}) {
                assertTrue(maxima[channel] > 0,
                        "Expected non-zero signal in channel " + channel);
            }
            var quality = server.channelQualityReport();
            assertEquals(
                    MrxsChannelQualityReport.SignalStatus.ALL_ZERO_AT_SAMPLED_LEVEL,
                    quality.channels().get(3).status()
            );
        }
    }

    private static int maximum(java.awt.image.Raster raster, int band) {
        int maximum = 0;
        for (int y = 0; y < raster.getHeight(); y++) {
            for (int x = 0; x < raster.getWidth(); x++) {
                maximum = Math.max(maximum, raster.getSample(x, y, band));
            }
        }
        return maximum;
    }
}
