package io.github.xiaofengzhou.qupath.mrxs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MrxsServerBuilderTest {

    @Test
    void claimsFluorescenceMrxsButNotUnrelatedFiles() throws Exception {
        var builder = new MrxsServerBuilder();
        var sample = TestSlides.fourChannel();
        var support = builder.checkImageSupport(sample.toUri());
        assertEquals(4.0f, support.getSupportLevel());
        var server = builder.buildServer(sample.toUri());
        assertNotNull(server);
        server.close();
        assertEquals(0.0f, builder.checkImageSupport(
                Path.of("README.md").toAbsolutePath().toUri()
        ).getSupportLevel());
        assertNull(builder.buildServer(
                Path.of("README.md").toAbsolutePath().toUri()
        ));
    }
}
