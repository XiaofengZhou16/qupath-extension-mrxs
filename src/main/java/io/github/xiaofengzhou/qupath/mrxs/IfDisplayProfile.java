package io.github.xiaofengzhou.qupath.mrxs;

import java.util.HashSet;
import java.util.List;
import qupath.lib.images.servers.ImageServer;

/** Reproducible display-only settings; source names and indices remain immutable. */
public record IfDisplayProfile(int schemaVersion, double gamma, boolean grayscale,
                               boolean inverted, List<Channel> channels) {
    public IfDisplayProfile {
        if (schemaVersion != 1 || !Double.isFinite(gamma) || gamma < 0.01 || gamma > 10)
            throw new IllegalArgumentException("Invalid display profile schema or gamma (0.01–10)");
        if (channels == null || channels.isEmpty()) throw new IllegalArgumentException("No display channels");
        channels = List.copyOf(channels);
        var seen = new HashSet<Integer>();
        for (var channel : channels) if (!seen.add(channel.index())) throw new IllegalArgumentException("Duplicate display channel index");
    }
    public record Channel(int index, String name, int color, double minimum, double maximum, boolean selected) {
        public Channel {
            if (index < 0 || name == null || name.isBlank() || !Double.isFinite(minimum)
                    || !Double.isFinite(maximum) || maximum <= minimum
                    || Math.abs(minimum)>Float.MAX_VALUE || Math.abs(maximum)>Float.MAX_VALUE
                    || (float)maximum <= (float)minimum)
                throw new IllegalArgumentException("Invalid display range/channel; maximum must exceed minimum");
            color &= 0xffffff;
        }
    }
    public void validateFor(ImageServer<?> server) {
        if (server.isRGB() || channels.size()!=server.nChannels())
            throw new IllegalArgumentException("Profile requires the same separate-channel image layout");
        for (var c : channels) if (c.index()>=server.nChannels()
                || !c.name().equals(server.getChannel(c.index()).getName()))
            throw new IllegalArgumentException("Profile channel mapping does not match image: " + c.name());
    }
    public List<Channel> selectedChannels() { return channels.stream().filter(Channel::selected).toList(); }
}
