package io.github.xiaofengzhou.qupath.mrxs;

import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.viewer.QuPathViewer;

/**
 * Display-only controls that reduce resampling blur without changing source pixels.
 */
final class MrxsClarityControls {

    private static Boolean interpolationBeforeHighClarity;

    private MrxsClarityControls() {
    }

    static ClarityResult applyHighClarity(QuPathViewer viewer) {
        MrxsImageServer server = requireMrxs(viewer);
        boolean interpolationBefore = PathPrefs.viewerInterpolateBilinearProperty().get();
        if (interpolationBeforeHighClarity == null) {
            interpolationBeforeHighClarity = interpolationBefore;
        }
        PathPrefs.viewerInterpolateBilinearProperty().set(false);
        double previousDownsample = viewer.getDownsampleFactor();
        int level = server.closestResolution(previousDownsample);
        double nativeDownsample = server.getDownsampleForResolution(level);
        viewer.setDoFasterRepaint(false);
        viewer.setDownsampleFactor(
                nativeDownsample, -1, -1
        );
        viewer.repaintEntireImage();
        return new ClarityResult(
                previousDownsample, nativeDownsample, level,
                interpolationBefore, false
        );
    }

    static ClarityResult snapToNativeZoom(QuPathViewer viewer) {
        MrxsImageServer server = requireMrxs(viewer);
        double previousDownsample = viewer.getDownsampleFactor();
        int level = server.closestResolution(previousDownsample);
        double nativeDownsample = server.getDownsampleForResolution(level);
        boolean interpolation = PathPrefs.viewerInterpolateBilinearProperty().get();
        viewer.setDoFasterRepaint(false);
        viewer.setDownsampleFactor(
                nativeDownsample, -1, -1
        );
        viewer.repaintEntireImage();
        return new ClarityResult(
                previousDownsample, nativeDownsample, level,
                interpolation, interpolation
        );
    }

    static boolean restoreInterpolation() {
        if (interpolationBeforeHighClarity == null) {
            return false;
        }
        PathPrefs.viewerInterpolateBilinearProperty().set(
                interpolationBeforeHighClarity
        );
        interpolationBeforeHighClarity = null;
        return true;
    }

    static boolean restoreIfNotMrxs(QuPathViewer viewer) {
        if (interpolationBeforeHighClarity == null) {
            return false;
        }
        if (viewer != null && viewer.getServer() instanceof MrxsImageServer) {
            return false;
        }
        return restoreInterpolation();
    }

    static boolean isHighClarityActive() {
        return interpolationBeforeHighClarity != null;
    }

    private static MrxsImageServer requireMrxs(QuPathViewer viewer) {
        if (viewer == null || !(viewer.getServer() instanceof MrxsImageServer server)) {
            throw new IllegalArgumentException(
                    "The active viewer is not using QuPath MRXS Extension."
            );
        }
        return server;
    }

    record ClarityResult(
            double previousDownsample,
            double nativeDownsample,
            int nativeLevel,
            boolean interpolationBefore,
            boolean interpolationAfter
    ) {
    }
}
