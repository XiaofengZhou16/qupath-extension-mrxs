package io.github.xiaofengzhou.qupath.mrxs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IniFileTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesBomCommentsWhitespaceAndTypedValues() throws Exception {
        Path ini = temporaryDirectory.resolve("fixture.ini");
        Files.writeString(
                ini,
                "\ufeff; comment\n"
                        + "[General]\n"
                        + " Name = fluorescence\n"
                        + "Count=4\n"
                        + "Scale=0.25\n"
                        + "# ignored\n"
                        + "[Empty]\n"
        );

        IniFile parsed = IniFile.read(ini);

        assertTrue(parsed.hasSection("General"));
        assertTrue(parsed.hasSection("Empty"));
        assertFalse(parsed.hasSection("Missing"));
        assertEquals("fluorescence", parsed.required("General", "Name"));
        assertEquals(4, parsed.requiredInt("General", "Count"));
        assertEquals(0.25, parsed.requiredDouble("General", "Scale"));
        assertEquals("fallback", parsed.optional("Missing", "Value", "fallback"));
        assertThrows(
                IllegalArgumentException.class,
                () -> parsed.required("General", "Missing")
        );
    }
}
