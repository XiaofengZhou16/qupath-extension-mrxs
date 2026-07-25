package io.github.xiaofengzhou.qupath.mrxs;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;

final class TestSlides {

    private TestSlides() {
    }

    static Path fourChannel() {
        return required("MRXS_TEST_SAMPLE_4C");
    }

    static URI fourChannelUri() {
        return fourChannel().toUri();
    }

    static URI fiveChannelUri() {
        return required("MRXS_TEST_SAMPLE_5C").toUri();
    }

    private static Path required(String variable) {
        String value = System.getenv(variable);
        Assumptions.assumeTrue(value != null && !value.isBlank(),
                () -> "Set " + variable + " to run private-slide integration tests");
        Path path = Path.of(value).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(path),
                () -> variable + " does not point to a readable file");
        return path;
    }
}
