package io.github.xiaofengzhou.qupath.mrxs;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IfSoftwareProvenanceTest {
    @Test void identityIsCachedImmutableAndDoesNotInventJarHash() {
        var value=IfSoftwareProvenance.snapshot();
        assertSame(value,IfSoftwareProvenance.snapshot());
        assertEquals("camera-subtiles-base-concat-v2",value.get("extensionMrxsReaderRevision"));
        assertNotNull(value.get("extensionVersion"));
        if(value.get("artifactSha256Status").equals("available"))
            assertTrue(value.get("artifactSha256").matches("[0-9a-f]{64}"));
        else assertFalse(value.containsKey("artifactSha256"));
        assertThrows(UnsupportedOperationException.class,()->value.put("artifactSha256","fake"));
    }
}
