package io.github.xiaofengzhou.qupath.mrxs;

import qupath.lib.analysis.stats.Histogram;
import qupath.lib.display.ChannelDisplayInfo;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.prefs.PathPrefs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

final class MrxsDisplayPresets {

    private static final double CASEVIEWER_LOW_PERCENTILE = 0.01;
    private static final double CASEVIEWER_HIGH_PERCENTILE = 0.998;
    private static final Map<QuPathViewer, DisplayState> SAVED_STATES =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private MrxsDisplayPresets() {
    }

    static List<ChannelRange> applyRawLinear(QuPathViewer viewer) {
        requireMrxs(viewer);
        saveInitialState(viewer);
        setViewerLocalGamma(viewer, 1.0);
        ImageDisplay display = viewer.getImageDisplay();
        display.setUseGrayscaleLuts(false);
        display.setUseInvertedBackground(false);
        List<ChannelRange> result = new ArrayList<>();
        for (ChannelDisplayInfo channel : display.availableChannels()) {
            display.setMinMaxDisplay(channel, 0.0f, 255.0f);
            result.add(new ChannelRange(channel.getName(), 0.0f, 255.0f));
        }
        viewer.repaintEntireImage();
        return List.copyOf(result);
    }

    static List<ChannelRange> applyCaseViewerLike(QuPathViewer viewer) {
        requireMrxs(viewer);
        saveInitialState(viewer);
        setViewerLocalGamma(viewer, 1.0);
        ImageDisplay display = viewer.getImageDisplay();
        display.setUseGrayscaleLuts(false);
        display.setUseInvertedBackground(false);
        List<ChannelRange> result = new ArrayList<>();
        for (ChannelDisplayInfo channel : display.availableChannels()) {
            Histogram histogram = display.getHistogram(channel);
            float minimum = percentile(histogram, CASEVIEWER_LOW_PERCENTILE);
            float maximum = percentile(histogram, CASEVIEWER_HIGH_PERCENTILE);
            if (!(maximum > minimum)) {
                minimum = channel.getMinAllowed();
                maximum = channel.getMaxAllowed();
            }
            display.setMinMaxDisplay(channel, minimum, maximum);
            result.add(new ChannelRange(channel.getName(), minimum, maximum));
        }
        viewer.repaintEntireImage();
        return List.copyOf(result);
    }

    static List<ChannelRange> currentRanges(QuPathViewer viewer) {
        requireMrxs(viewer);
        List<ChannelRange> result = new ArrayList<>();
        for (ChannelDisplayInfo channel : viewer.getImageDisplay().availableChannels()) {
            result.add(new ChannelRange(
                    channel.getName(), channel.getMinDisplay(), channel.getMaxDisplay()
            ));
        }
        return List.copyOf(result);
    }

    static List<ChannelRange> reset(QuPathViewer viewer) {
        requireMrxs(viewer);
        DisplayState state = SAVED_STATES.remove(viewer);
        if (state == null) {
            throw new IllegalStateException(
                    "No pre-preset display state is stored for the active viewer"
            );
        }
        ImageDisplay display = viewer.getImageDisplay();
        if (viewer.gammaProperty().isBound()) {
            viewer.gammaProperty().unbind();
        }
        viewer.setGamma(state.gamma());
        display.setUseGrayscaleLuts(state.grayscaleLuts());
        display.setUseInvertedBackground(state.invertedBackground());
        var channels = display.availableChannels();
        for (int i = 0; i < channels.size() && i < state.ranges().size(); i++) {
            var range = state.ranges().get(i);
            display.setMinMaxDisplay(channels.get(i), range.minimum(), range.maximum());
            display.setChannelSelected(channels.get(i), state.selected().get(i));
        }
        viewer.repaintEntireImage();
        if (state.gammaBound()) {
            viewer.gammaProperty().bind(PathPrefs.viewerGammaProperty());
        }
        return currentRanges(viewer);
    }

    private static void saveInitialState(QuPathViewer viewer) {
        SAVED_STATES.computeIfAbsent(viewer, ignored -> {
            ImageDisplay display = viewer.getImageDisplay();
            List<ChannelRange> ranges = new ArrayList<>();
            List<Boolean> selected = new ArrayList<>();
            for (ChannelDisplayInfo channel : display.availableChannels()) {
                ranges.add(new ChannelRange(
                        channel.getName(), channel.getMinDisplay(), channel.getMaxDisplay()
                ));
                selected.add(display.selectedChannels().contains(channel));
            }
            return new DisplayState(
                    viewer.getGamma(), viewer.gammaProperty().isBound(),
                    display.useGrayscaleLuts(),
                    display.useInvertedBackground(),
                    List.copyOf(ranges), List.copyOf(selected)
            );
        });
    }

    private static void setViewerLocalGamma(QuPathViewer viewer, double gamma) {
        if (viewer.gammaProperty().isBound()) {
            viewer.gammaProperty().unbind();
        }
        viewer.setGamma(gamma);
    }

    private static float percentile(Histogram histogram, double percentile) {
        if (histogram == null || histogram.nBins() <= 0 || histogram.getCountSum() <= 0) {
            return Float.NaN;
        }
        double target = histogram.getCountSum() * percentile;
        double cumulative = 0;
        for (int i = 0; i < histogram.nBins(); i++) {
            cumulative += histogram.getCountsForBin(i);
            if (cumulative >= target) {
                return (float) histogram.getBinLeftEdge(i);
            }
        }
        return (float) histogram.getEdgeMax();
    }

    private static void requireMrxs(QuPathViewer viewer) {
        if (viewer == null || !(viewer.getServer() instanceof MrxsImageServer)) {
            throw new IllegalArgumentException(
                    "The active viewer is not using QuPath MRXS Extension"
            );
        }
    }

    record ChannelRange(String name, float minimum, float maximum) {
    }

    private record DisplayState(
            double gamma,
            boolean gammaBound,
            boolean grayscaleLuts,
            boolean invertedBackground,
            List<ChannelRange> ranges,
            List<Boolean> selected
    ) {
    }
}
