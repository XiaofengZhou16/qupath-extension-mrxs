package io.github.xiaofengzhou.qupath.mrxs;

import qupath.imagej.detect.cells.WatershedCellDetection;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.PathCellObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.plugins.AbstractTaskRunner;
import qupath.lib.plugins.SimpleProgressMonitor;
import qupath.lib.regions.ImageRegion;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** QuPath's classical watershed baseline, explicitly not an AI model. */
final class IfSegmentation {
    record Config(String channel, double threshold, double pixelSizeMicrons,
                  double minAreaMicrons, double maxAreaMicrons, double expansionMicrons) {
        Config {
            if (channel == null || channel.isBlank() || !Double.isFinite(threshold)
                    || !Double.isFinite(pixelSizeMicrons) || pixelSizeMicrons < 0
                    || !Double.isFinite(minAreaMicrons) || minAreaMicrons <= 0
                    || !Double.isFinite(maxAreaMicrons) || maxAreaMicrons <= minAreaMicrons
                    || !Double.isFinite(expansionMicrons) || expansionMicrons <= 0)
                throw new IllegalArgumentException("Invalid watershed parameters");
        }
    }
    record Result(List<IfAnalysis.Cell> cells, Map<String, Object> provenance) {}

    static Result detect(ImageServer<BufferedImage> server, ROI roi, Config config,
                         BooleanSupplier cancelled) {
        check(cancelled);
        if (server.isRGB() || !server.getPixelCalibration().hasPixelSizeMicrons())
            throw new IllegalArgumentException("Watershed requires separate channels and calibrated µm/pixel");
        if (server.getMetadata().getChannels().stream().filter(c -> c.getName().equals(config.channel())).count() != 1)
            throw new IllegalArgumentException("Detection channel must have a unique matching name");
        if (roi == null || !roi.isArea()) throw new IllegalArgumentException("Select an area ROI");
        // Isolate generated detections. Never close this ImageData: the server belongs to the viewer.
        var isolated = new ImageData<>(server, ImageData.ImageType.FLUORESCENCE);
        var annotation = PathObjects.createAnnotationObject(roi);
        isolated.getHierarchy().addObject(annotation);
        isolated.getHierarchy().getSelectionModel().setSelectedObject(annotation);
        var plugin = new WatershedCellDetection();
        var parameters = new LinkedHashMap<>(plugin.getDefaultParameterList(isolated).getKeyValueParameters(false));
        parameters.put("detectionImage", config.channel());
        parameters.put("requestedPixelSizeMicrons", config.pixelSizeMicrons());
        parameters.put("threshold", config.threshold());
        parameters.put("backgroundRadiusMicrons", 8.0);
        parameters.put("medianRadiusMicrons", 0.0);
        parameters.put("sigmaMicrons", 1.0);
        parameters.put("minAreaMicrons", config.minAreaMicrons());
        parameters.put("maxAreaMicrons", config.maxAreaMicrons());
        parameters.put("cellExpansionMicrons", config.expansionMicrons());
        parameters.put("includeNuclei", true);
        parameters.put("makeMeasurements", false); // The tiled raw analyzer measures once afterwards.
        var runner = new AbstractTaskRunner(2) {
            @Override protected SimpleProgressMonitor makeProgressMonitor() {
                return new SimpleProgressMonitor() {
                    public void startMonitoring(String message, int max, boolean mayCancel) {}
                    public void updateProgress(int delta, String message, ImageRegion region) {}
                    public void pluginCompleted(String message) {}
                    public boolean cancelled() { return cancelled.getAsBoolean(); }
                };
            }
        };
        long start = System.nanoTime();
        boolean success = plugin.runPlugin(runner, isolated, GsonTools.getInstance().toJson(parameters));
        check(cancelled);
        if (!success || runner.isCancelled()) throw new IllegalStateException("Watershed did not finish successfully");
        var cells = isolated.getHierarchy().getDetectionObjects().stream()
                .filter(PathCellObject.class::isInstance).map(PathCellObject.class::cast)
                .map(IfAnalysis.Cell::snapshot).toList();
        var provenance = new LinkedHashMap<String, Object>();
        provenance.put("method", "QuPath WatershedCellDetection (classical; not AI)");
        provenance.put("config", config);
        provenance.put("parameters", parameters);
        provenance.put("threads", 2);
        provenance.put("detectedCells", cells.size());
        provenance.put("elapsedSeconds", (System.nanoTime() - start) / 1e9);
        provenance.put("limitation", "Expanded nuclear boundaries approximate cells; review against membrane/cytoplasm markers");
        return new Result(cells, provenance);
    }

    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
}
