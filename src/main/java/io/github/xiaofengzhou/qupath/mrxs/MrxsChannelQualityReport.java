package io.github.xiaofengzhou.qupath.mrxs;

import java.util.List;

record MrxsChannelQualityReport(
        int resolution,
        double downsample,
        int width,
        int height,
        List<ChannelStatistics> channels
) {
    MrxsChannelQualityReport {
        channels = List.copyOf(channels);
    }

    String format() {
        StringBuilder text = new StringBuilder();
        text.append("Channel quality sample\n")
                .append("======================\n")
                .append("Resolution: ").append(resolution)
                .append(" (downsample ").append(String.format("%.3f", downsample))
                .append(")\n")
                .append("Sampled image: ").append(width).append(" x ").append(height)
                .append(" pixels\n\n");
        for (ChannelStatistics channel : channels) {
            text.append(channel.index() + 1).append(". ")
                    .append(channel.name())
                    .append(": min=").append(channel.minimum())
                    .append(", max=").append(channel.maximum())
                    .append(", mean=").append(String.format("%.3f", channel.mean()))
                    .append(", nonZero=")
                    .append(String.format("%.3f%%", channel.nonZeroFraction() * 100))
                    .append(", status=").append(channel.status())
                    .append('\n');
        }
        text.append("\nSignal statistics describe stored pixel values only; ")
                .append("they apply only to the sampled pyramid level and do not establish ")
                .append("whole-slide absence or biological staining specificity.\n");
        return text.toString();
    }

    record ChannelStatistics(
            int index,
            String name,
            int minimum,
            int maximum,
            double mean,
            long nonZeroPixels,
            long sampledPixels,
            SignalStatus status
    ) {
        double nonZeroFraction() {
            return sampledPixels == 0 ? 0 : (double) nonZeroPixels / sampledPixels;
        }
    }

    enum SignalStatus {
        ALL_ZERO_AT_SAMPLED_LEVEL,
        LOW_DYNAMIC_RANGE_AT_SAMPLED_LEVEL,
        SIGNAL_PRESENT
    }
}
