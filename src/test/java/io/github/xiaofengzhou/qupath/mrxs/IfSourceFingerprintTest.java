package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class IfSourceFingerprintTest {
    @TempDir Path temp;
    @Test void detectsSameSizeSameTimestampReplacementAndSidecarInventoryChanges() throws Exception {
        Path anchor=temp.resolve("slide.mrxs");Files.writeString(anchor,"anchor");
        Path sidecar=Files.createDirectory(temp.resolve("slide"));
        Path data=sidecar.resolve("Data0000.dat");Files.write(data,new byte[400000]);
        var uris=List.of(anchor.toUri().toString());
        var original=IfSourceFingerprint.capture(uris,()->false);
        assertEquals(original,IfSourceFingerprint.capture(uris,()->false));
        var timestamp=Files.getLastModifiedTime(data);
        var changed=new byte[400000];changed[200000]=1;Files.write(data,changed);Files.setLastModifiedTime(data,timestamp);
        assertNotEquals(original,IfSourceFingerprint.capture(uris,()->false));
        Files.write(data,new byte[400000]);Files.setLastModifiedTime(data,timestamp);
        assertEquals(original,IfSourceFingerprint.capture(uris,()->false));
        Files.writeString(sidecar.resolve("extra.dat"),"extra");
        assertNotEquals(original,IfSourceFingerprint.capture(uris,()->false));
    }
    @Test void unsupportedSourcesAndCancellationAreExplicit() throws Exception {
        assertNull(IfSourceFingerprint.capture(List.of(),()->false));
        assertNull(IfSourceFingerprint.capture(List.of("https://example.org/slide"),()->false));
        assertThrows(java.util.concurrent.CancellationException.class,
                ()->IfSourceFingerprint.capture(List.of("file:///not-read"),()->true));
        Path anchor=temp.resolve("missing.mrxs");Files.writeString(anchor,"anchor");
        assertThrows(java.io.IOException.class,()->IfSourceFingerprint.capture(List.of(anchor.toUri().toString()),()->false));
    }
}
