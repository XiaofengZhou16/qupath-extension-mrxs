package io.github.xiaofengzhou.qupath.mrxs;

import java.util.HashSet;
import java.util.List;

/** Raw-intensity thresholds, intentionally independent of viewer display settings. */
public record IfAnalysisProfile(int schemaVersion, int resolutionLevel, List<Marker> markers) {
    public IfAnalysisProfile {
        if (schemaVersion != 1 || resolutionLevel < 0)
            throw new IllegalArgumentException("Unsupported profile schema or resolution level");
        if (markers == null || markers.isEmpty() || markers.size() > 8)
            throw new IllegalArgumentException("Select between 1 and 8 phenotype markers");
        markers = List.copyOf(markers);
        var indices = new HashSet<Integer>();
        var names = new HashSet<String>();
        for (var m : markers) {
            if (!indices.add(m.channel()) || !names.add(m.name()))
                throw new IllegalArgumentException("Marker channels and names must be unique");
        }
    }

    public record Marker(int channel, String name, double pixelThreshold,
                         double nucleusThreshold, double cytoplasmThreshold) {
        public Marker {
            if (channel < 0 || name == null || name.isBlank())
                throw new IllegalArgumentException("Invalid marker channel/name");
            if (!Double.isFinite(pixelThreshold) || !Double.isFinite(nucleusThreshold)
                    || !Double.isFinite(cytoplasmThreshold))
                throw new IllegalArgumentException("Thresholds must be finite");
        }
    }
}
