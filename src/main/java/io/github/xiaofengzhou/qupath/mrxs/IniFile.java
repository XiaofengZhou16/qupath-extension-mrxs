package io.github.xiaofengzhou.qupath.mrxs;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal, case-preserving INI reader for 3DHISTECH {@code Slidedat.ini}.
 */
final class IniFile {

    private final Map<String, Map<String, String>> sections;

    private IniFile(Map<String, Map<String, String>> sections) {
        this.sections = sections;
    }

    static IniFile read(Path path) throws IOException {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        Map<String, String> current = null;

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine && !line.isEmpty() && line.charAt(0) == '\ufeff') {
                    line = line.substring(1);
                }
                firstLine = false;
                line = line.trim();
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    String name = line.substring(1, line.length() - 1).trim();
                    current = sections.computeIfAbsent(name, ignored -> new LinkedHashMap<>());
                    continue;
                }
                int equals = line.indexOf('=');
                if (equals < 0 || current == null) {
                    continue;
                }
                String key = line.substring(0, equals).trim();
                String value = line.substring(equals + 1).trim();
                current.put(key, value);
            }
        }
        return new IniFile(sections);
    }

    Map<String, String> section(String name) {
        Map<String, String> values = sections.get(name);
        if (values == null) {
            throw new IllegalArgumentException("Missing INI section [" + name + "]");
        }
        return Collections.unmodifiableMap(values);
    }

    boolean hasSection(String name) {
        return sections.containsKey(name);
    }

    String required(String section, String key) {
        String value = section(section).get(key);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Missing INI value [" + section + "] " + key
            );
        }
        return value;
    }

    String optional(String section, String key, String fallback) {
        Map<String, String> values = sections.get(section);
        return values == null ? fallback : values.getOrDefault(key, fallback);
    }

    int requiredInt(String section, String key) {
        return Integer.parseInt(required(section, key));
    }

    int optionalInt(String section, String key, int fallback) {
        String value = optional(section, key, null);
        return value == null ? fallback : Integer.parseInt(value);
    }

    double requiredDouble(String section, String key) {
        return Double.parseDouble(required(section, key));
    }

    double optionalDouble(String section, String key, double fallback) {
        String value = optional(section, key, null);
        return value == null ? fallback : Double.parseDouble(value);
    }
}
