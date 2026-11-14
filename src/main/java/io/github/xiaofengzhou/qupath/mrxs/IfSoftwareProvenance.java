package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Identity of the loaded extension, not an inferred identity of the source image server. */
final class IfSoftwareProvenance {
    private IfSoftwareProvenance() {}
    static Map<String, String> snapshot() { return Identity.VALUE; }

    private static final class Identity {
        // Hash a loaded JAR once, never per tile/channel/output PNG.
        private static final Map<String, String> VALUE = read();
    }

    private static Map<String, String> read() {
        var result = new LinkedHashMap<String, String>();
        String version = IfSoftwareProvenance.class.getPackage().getImplementationVersion();
        result.put("extensionVersion", version == null ? "unavailable" : version);
        result.put("extensionMrxsReaderRevision", "camera-subtiles-base-concat-v2");
        result.put("artifactSha256Status", "unavailable-not-jar");
        try {
            var source = IfSoftwareProvenance.class.getProtectionDomain().getCodeSource();
            if (source != null && "file".equalsIgnoreCase(source.getLocation().getProtocol())) {
                Path path = Path.of(source.getLocation().toURI());
                if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")) {
                    // Split-package test/extension loaders may define the Package
                    // before this JAR is loaded; consult this artifact directly.
                    try (var jar = new java.util.jar.JarFile(path.toFile())) {
                        var manifest = jar.getManifest();
                        String declared = manifest == null ? null : manifest.getMainAttributes()
                                .getValue("Implementation-Version");
                        if (declared != null && !declared.isBlank()) result.put("extensionVersion", declared);
                    }
                    var digest = MessageDigest.getInstance("SHA-256");
                    try (var input = Files.newInputStream(path)) {
                        byte[] buffer = new byte[65536];
                        int count;
                        while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
                    }
                    result.put("artifactSha256", HexFormat.of().formatHex(digest.digest()));
                    result.put("artifactSha256Status", "available");
                }
            }
        } catch (Exception e) {
            // Provenance failure must be explicit, not fabricated and not an export failure.
            result.put("artifactSha256Status", "unavailable-" + e.getClass().getSimpleName());
        }
        return Map.copyOf(result);
    }
}
