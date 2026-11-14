package io.github.xiaofengzhou.qupath.mrxs;

import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathCellObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/** Optional bridge to the official extension; no AI runtime or model is bundled. */
final class IfInstanSeg {
    static final String CORE_CLASS = "qupath.ext.instanseg.core.InstanSeg";

    static ClassLoader extensionLoader() {
        try {
            Object manager = qupath.lib.gui.QuPathGUI.class.getMethod("getExtensionCatalogManager").invoke(null);
            return (ClassLoader) call(manager, "getExtensionClassLoader");
        } catch (ReflectiveOperationException | LinkageError e) {
            return IfInstanSeg.class.getClassLoader();
        }
    }

    record Config(Path modelDirectory, String device, int threads, int tileSize, int padding,
                  List<Integer> inputChannels) {
        Config {
            if (modelDirectory == null || !List.of("cpu", "mps").contains(device)
                    || threads < 1 || threads > 4 || !List.of(256, 512, 1024).contains(tileSize)
                    || padding < 16 || padding >= tileSize / 2 || inputChannels == null || inputChannels.isEmpty()
                    || inputChannels.stream().anyMatch(c -> c == null || c < 0)
                    || inputChannels.stream().distinct().count() != inputChannels.size())
                throw new IllegalArgumentException("Invalid InstanSeg settings");
            modelDirectory = modelDirectory.toAbsolutePath().normalize();
            inputChannels = List.copyOf(inputChannels);
        }
    }

    static boolean isAvailable(ClassLoader loader) {
        try { Class.forName(CORE_CLASS, false, loader); return true; }
        catch (ClassNotFoundException | LinkageError e) { return false; }
    }

    static IfSegmentation.Result detect(ImageServer<BufferedImage> server, ROI roi, Config config,
                                        ClassLoader loader, BooleanSupplier cancelled,
                                        DoubleConsumer progress) throws Exception {
        long bridgeStart=System.nanoTime();
        var runtimeSettings=runtimeSettings();
        check(cancelled);
        if (server.isRGB() || !server.getPixelCalibration().hasPixelSizeMicrons())
            throw new IllegalArgumentException("InstanSeg IF requires separate channels and calibrated µm/pixel");
        if (roi == null || !roi.isArea() || roi.isEmpty()) throw new IllegalArgumentException("Select a nonempty area ROI");
        for (int channel : config.inputChannels()) if (channel >= server.nChannels())
            throw new IllegalArgumentException("InstanSeg input channel outside image");
        Path weights = config.modelDirectory().resolve("instanseg.pt");
        Path rdf = config.modelDirectory().resolve("rdf.yaml");
        if (!Files.isRegularFile(weights) || !Files.isRegularFile(rdf))
            throw new IllegalArgumentException("Choose a trusted model folder containing instanseg.pt and rdf.yaml");
        String weightHash = sha256(weights, cancelled), rdfHash = sha256(rdf, cancelled);
        Class<?> core;
        try { core = Class.forName(CORE_CLASS, true, loader); }
        catch (ClassNotFoundException | LinkageError e) {
            throw new IOException("Install the official InstanSeg and DJL extensions, then restart QuPath", e);
        }
        Object model = Class.forName("qupath.ext.instanseg.core.InstanSegModel", true, loader)
                .getMethod("fromPath", Path.class).invoke(null, config.modelDirectory());
        String modelName = (String) call(model, "getName");
        if (!"fluorescence_nuclei_and_cells".equals(modelName))
            throw new IllegalArgumentException("This IF workflow currently requires fluorescence_nuclei_and_cells");
        // Keep generated cells separate from the live project until the user explicitly imports them.
        var isolated = new ImageData<>(server, ImageData.ImageType.FLUORESCENCE);
        var parent = PathObjects.createAnnotationObject(roi);
        isolated.getHierarchy().addObject(parent);
        isolated.getHierarchy().getSelectionModel().setSelectedObject(parent);
        var scheduledTiles = new java.util.concurrent.atomic.AtomicInteger();
        var taskTimings=new java.util.ArrayList<java.util.Map<String,Object>>();
        var runner = runner(config.threads(), cancelled, progress, scheduledTiles,taskTimings);
        Object builder = core.getMethod("builder").invoke(null);
        call(builder, "modelPath", Path.class, config.modelDirectory());
        call(builder, "device", String.class, config.device());
        call(builder, "tileDims", int.class, config.tileSize());
        call(builder, "interTilePadding", int.class, config.padding());
        call(builder, "inputChannelIndices", Collection.class, config.inputChannels());
        call(builder, "outputCells");
        call(builder, "makeMeasurements", boolean.class, false);
        call(builder, "randomColors", boolean.class, false);
        call(builder, "taskRunner", TaskRunner.class, runner);
        Object engine = call(builder, "build");
        check(cancelled);
        Object result;
        double preparationSeconds=(System.nanoTime()-bridgeStart)/1e9;
        long engineStart=System.nanoTime();
        try {
            result = engine.getClass().getMethod("detectObjects", ImageData.class, Collection.class)
                    .invoke(engine, isolated, List.of(parent));
        } catch (InvocationTargetException e) {
            throw new IOException("InstanSeg inference failed: " + e.getCause(), e.getCause());
        }
        double engineSeconds=(System.nanoTime()-engineStart)/1e9;
        long auditStart=System.nanoTime();
        check(cancelled);
        int tiles = ((Number) call(result, "nTilesProcessed")).intValue();
        int failed = ((Number) call(result, "nTilesFailed")).intValue();
        boolean interrupted = (Boolean) call(result, "wasInterrupted");
        validateOutcome(tiles, failed, interrupted || runner.isCancelled());
        validateTileCoverage(scheduledTiles.get(), tiles);
        var detections = isolated.getHierarchy().getDetectionObjects();
        var outputAudit=auditOutput(detections,cancelled);
        var cells = detections.stream().filter(PathCellObject.class::isInstance).map(PathCellObject.class::cast)
                .map(IfAnalysis.Cell::snapshot).toList();
        if (cells.size() != detections.size() || (!cells.isEmpty() && cells.stream().allMatch(c -> c.nucleus() == null)))
            throw new IOException("Model output lacks paired cell/nucleus objects: " + detections.size()
                    + " detections, " + cells.size() + " cells, " + cells.stream().filter(c -> c.nucleus()!=null).count()
                    + " with nuclei; no statistics exported");
        var provenance = new LinkedHashMap<String, Object>();
        provenance.put("method", "Official QuPath InstanSeg AI nucleus and cell segmentation");
        provenance.put("modelName", modelName);
        provenance.put("weightsSha256", weightHash);
        provenance.put("rdfSha256", rdfHash);
        provenance.put("deviceRequested", config.device());
        provenance.put("threads", config.threads());
        provenance.put("tileSize", config.tileSize());
        provenance.put("padding", config.padding());
        provenance.put("inputChannelIndices", config.inputChannels());
        provenance.put("inputChannelNames", config.inputChannels().stream().map(c -> server.getMetadata().getChannels().get(c).getName()).toList());
        provenance.put("preferredDownsample", call(model, "getPreferredDownsample",
                qupath.lib.images.servers.PixelCalibration.class, server.getPixelCalibration()));
        provenance.put("tilesProcessed", tiles);
        provenance.put("tilesScheduled", scheduledTiles.get());
        provenance.put("tilesFailed", failed);
        provenance.put("pixelsProcessedIncludingPadding", call(result, "nPixelsProcessed"));
        provenance.put("elapsedSeconds", ((Number)call(result, "processingTimeMillis")).doubleValue() / 1000);
        provenance.put("detectedCells", cells.size());
        provenance.put("officialOutputGeometry",outputAudit);
        provenance.put("cellsWithoutNucleiExcludedFromMeasurements", cells.stream().filter(c -> c.nucleus() == null).count());
        provenance.put("extensionVersion", String.valueOf(core.getPackage().getImplementationVersion()));
        var engineType = Class.forName("ai.djl.engine.Engine", true, loader);
        Object pytorch = engineType.getMethod("getEngine", String.class).invoke(null, "PyTorch");
        provenance.put("pytorchVersion", call(pytorch, "getVersion"));
        provenance.put("djlVersion", engineType.getMethod("getDjlVersion").invoke(null));
        if(!runtimeSettings.equals(runtimeSettings()))throw new IOException("InstanSeg runtime settings changed during inference");
        provenance.putAll(runtimeSettings);
        provenance.put("bridgeTiming",java.util.Map.of("preparationSeconds",preparationSeconds,
                "engineCallSeconds",engineSeconds,"outputAuditAndProvenanceSeconds",(System.nanoTime()-auditStart)/1e9,
                "taskBatches",java.util.List.copyOf(taskTimings),
                "scope","Engine call includes native model loading, tile preprocessing/prediction and merging. Task batches measure runner wall time including completion callbacks; not pure GPU compute or summed thread CPU time."));
        provenance.put("normalization", "Official extension preprocessing; independent of viewer LUT and raw measurement thresholds");
        provenance.put("limitation", "Pretrained model; local tissue accuracy requires annotated validation");
        progress.accept(1);
        return new IfSegmentation.Result(cells, provenance);
    }

    static void validateOutcome(int tiles, int failures, boolean interrupted) throws IOException {
        if (interrupted) throw new CancellationException("InstanSeg interrupted; partial results rejected");
        // Official API catches some engine exceptions and returns an empty result: never call it a successful negative slide.
        if (tiles <= 0 || failures != 0) throw new IOException("InstanSeg incomplete: " + tiles
                + " tiles processed, " + failures + " failed. Check QuPath log and DJL engine/model setup.");
    }

    /** After official tile merging, before bridge conversion; never repairs or drops geometry. */
    static java.util.Map<String,Object> auditOutput(Collection<? extends qupath.lib.objects.PathObject> objects,BooleanSupplier cancelled){
        long cellObjects=0,missingCell=0,missingNucleus=0,equalCompartments=0;
        for(var object:objects){
            check(cancelled);if(!(object instanceof PathCellObject cell))continue;cellObjects++;
            var outer=cell.getROI();var nucleus=cell.getNucleusROI();
            if(outer==null)missingCell++;if(nucleus==null)missingNucleus++;
            if(outer!=null&&nucleus!=null&&outer.getGeometry().equalsTopo(nucleus.getGeometry()))equalCompartments++;
            var snapshot=IfAnalysis.Cell.snapshot(cell);
            if(snapshot.cell()!=outer||snapshot.nucleus()!=nucleus||!snapshot.id().equals(cell.getID().toString()))
                throw new IllegalStateException("Bridge snapshot changed official output identity/geometry");
        }
        check(cancelled);
        return java.util.Map.of("stage","official extension output after tile merging, before bridge snapshot",
                "objects",objects.size(),"cellObjects",cellObjects,"missingCellROI",missingCell,
                "missingNucleusROI",missingNucleus,"cellEqualsNucleusTopologically",equalCompartments,
                "snapshotPreservesIdentityAndROIReferences",true,
                "limitation","Not raw model tensors, pre-merge labels or biological accuracy; no geometry repaired");
    }

    static void validateTileCoverage(int scheduled, int processed) throws IOException {
        if (scheduled <= 0 || processed != scheduled)
            throw new IOException("InstanSeg tile coverage incomplete: scheduled=" + scheduled + ", processed=" + processed);
    }

    private static TaskRunner runner(int threads, BooleanSupplier cancelled, DoubleConsumer progress,
                                             java.util.concurrent.atomic.AtomicInteger scheduledTiles,java.util.List<java.util.Map<String,Object>> timings) {
        var delegate = new IfInferenceRunner(threads, cancelled, progress);
        return new TaskRunner() {
            @Override public boolean isCancelled() { return delegate.isCancelled(); }
            @Override public synchronized void runTasks(String message, Collection<? extends Runnable> tasks) {
                // QuPath 0.7 uses PathTasks for inference tiles and plain Runnables for merging.
                // Count requests as well as reported predictions: preprocessing/read failures may
                // otherwise be logged by PixelProcessor without incrementing InstanSeg's failure counter.
                scheduledTiles.addAndGet((int)tasks.stream().filter(qupath.lib.plugins.PathTask.class::isInstance).count());
                long start=System.nanoTime();boolean completed=false;
                try{delegate.runTasks(message, tasks);completed=true;}
                finally{timings.add(java.util.Map.of("message",String.valueOf(message),"tasks",tasks.size(),
                        "pathTasks",tasks.stream().filter(qupath.lib.plugins.PathTask.class::isInstance).count(),
                        "seconds",(System.nanoTime()-start)/1e9,"returnedNormally",completed));}
            }
        };
    }
    static java.util.Map<String,String> runtimeSettings() {
        var settings=new java.util.TreeMap<String,String>();
        settings.put("graphOptimizer",System.getProperty("ai.djl.pytorch.graph_optimizer","true"));
        settings.put("numPredictors",System.getProperty("instanseg.numPredictors","1"));
        settings.put("padToInputSize",System.getProperty("instanseg.padToInputSize","false"));
        return java.util.Collections.unmodifiableMap(settings);
    }
    private static Object call(Object target, String name) throws ReflectiveOperationException {
        return target.getClass().getMethod(name).invoke(target);
    }
    private static Object call(Object target, String name, Class<?> type, Object argument) throws ReflectiveOperationException {
        return target.getClass().getMethod(name, type).invoke(target, argument);
    }
    private static String sha256(Path path, BooleanSupplier cancelled) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) >= 0) { check(cancelled); digest.update(buffer,0,count); }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
}
