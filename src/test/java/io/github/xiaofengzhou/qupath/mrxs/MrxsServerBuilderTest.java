package io.github.xiaofengzhou.qupath.mrxs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MrxsServerBuilderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void claimsFluorescenceMrxs() throws Exception {
        var builder = new MrxsServerBuilder();
        var sample = SyntheticMrxsFixture.create(temporaryDirectory.resolve("supported"));
        var support = builder.checkImageSupport(sample.toUri());
        assertEquals(4.0f, support.getSupportLevel());
        var server = builder.buildServer(sample.toUri());
        assertNotNull(server);
        server.close();
    }

    @Test
    void declinesParseableButUnsupportedMrxsLayouts() throws Exception {
        var builder = new MrxsServerBuilder();
        var highBitDepth = SyntheticMrxsFixture.create(
                temporaryDirectory.resolve("high-bit-depth"), 16, "JPEG"
        );
        var unsupportedCompression = SyntheticMrxsFixture.create(
                temporaryDirectory.resolve("png"), 8, "PNG"
        );

        for (Path sample : List.of(highBitDepth, unsupportedCompression)) {
            assertEquals(0.0f, builder.checkImageSupport(
                    sample.toUri()
            ).getSupportLevel());
            assertNull(builder.buildServer(sample.toUri()));
        }
    }

    @Test
    void neverClaimsOtherWholeSlideFormats() {
        var builder = new MrxsServerBuilder();
        for (String name : List.of(
                "slide.svs", "slide.tif", "slide.tiff", "slide.ome.tif",
                "slide.ome.tiff", "slide.ndpi", "slide.czi", "README.md"
        )) {
            var uri = Path.of(name).toAbsolutePath().toUri();
            assertEquals(0.0f, builder.checkImageSupport(uri).getSupportLevel(), name);
            assertNull(builder.buildServer(uri), name);
        }
    }
}
