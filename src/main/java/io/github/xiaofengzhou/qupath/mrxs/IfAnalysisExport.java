package io.github.xiaofengzhou.qupath.mrxs;

import qupath.lib.io.GsonTools;
import qupath.lib.roi.interfaces.ROI;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.awt.image.BufferedImage;
import qupath.lib.images.servers.ImageServer;

/** Auditable tables; successful runs have manifest.json and no INCOMPLETE marker. */
final class IfAnalysisExport {
    private IfAnalysisExport() {}

    static void write(Path directory, String imageName, ROI roi, IfAnalysis.Result result,
                      BooleanSupplier cancelled) throws IOException {
        write(directory, imageName, roi, result, List.of(), java.util.Map.of("method", "not supplied"), cancelled);
    }

    static void write(Path directory, String imageName, ROI roi, IfAnalysis.Result result,
                      List<IfAnalysis.Cell> sourceCells, java.util.Map<String, Object> segmentation,
                      BooleanSupplier cancelled) throws IOException {
        begin(directory);
        writePrepared(directory,imageName,roi,result,sourceCells,segmentation,java.util.Map.of("exported",false),java.util.Map.of("status","unavailable"),cancelled);
    }

    /** Analysis, optional mask streaming and tables share one completion boundary. */
    static IfAnalysis.Result analyzeAndWrite(Path directory, ImageServer<BufferedImage> server, ROI roi,
            IfAnalysisProfile profile, List<IfAnalysis.Cell> cells, java.util.Map<String,Object> segmentation,
            boolean exportMasks, BooleanSupplier cancelled, DoubleConsumer progress) throws IOException {
        return analyzeAndWrite(directory,server,roi,profile,cells,segmentation,exportMasks,null,cancelled,progress);
    }

    static IfAnalysis.Result analyzeAndWrite(Path directory, ImageServer<BufferedImage> server, ROI roi,
            IfAnalysisProfile profile, List<IfAnalysis.Cell> cells, java.util.Map<String,Object> segmentation,
            boolean exportMasks, IfDisplayProfile reportDisplay, BooleanSupplier cancelled, DoubleConsumer progress) throws IOException {
        check(cancelled);begin(directory);
        var sourceUris=server.getURIs().stream().map(Object::toString).sorted().toList();
        var fingerprint=IfSourceFingerprint.capture(sourceUris,cancelled);
        try(var masks=exportMasks ? new IfAreaMasks(directory.resolve("area-masks"),profile.markers().size(),cancelled) : null) {
            var result=IfAnalysis.analyze(server,roi,profile,cells,cancelled,progress,masks);
            var metadata=masks==null ? java.util.Map.<String,Object>of("exported",false) : masks.finish(result,roi.getZ(),roi.getT());
            var source = new LinkedHashMap<String,Object>();
            source.put("status","locator_metadata_only");
            source.put("uris",sourceUris);
            if(fingerprint!=null)source.put("fileFingerprint",fingerprint);
            source.put("serverClass",server.getClass().getName());
            source.put("width",server.getWidth());source.put("height",server.getHeight());
            source.put("channels",server.getMetadata().getChannels().stream().map(c->c.getName()).toList());
            source.put("limitation","Locator/metadata with optional bounded file fingerprint; samples head/middle/tail, not all source bytes. Cannot establish unchanged source pixels or source authenticity.");
            source.put("reportFigure",IfReportFigure.write(directory,server,roi,reportDisplay,cancelled));
            if(!java.util.Objects.equals(fingerprint,IfSourceFingerprint.capture(sourceUris,cancelled)))
                throw new IOException("Source files changed during analysis; output remains incomplete");
            writePrepared(directory,server.getMetadata().getName(),roi,result,cells,segmentation,metadata,source,cancelled);
            return result;
        }
    }

    private static void begin(Path directory) throws IOException {
        Files.createDirectory(directory); // Never overwrite a previous analysis.
        Files.writeString(directory.resolve("INCOMPLETE"), "Output is incomplete until manifest.json exists and this marker is removed.\n");
    }

    private static void writePrepared(Path directory, String imageName, ROI roi, IfAnalysis.Result result,
                      List<IfAnalysis.Cell> sourceCells, java.util.Map<String,Object> segmentation,
                      java.util.Map<String,Object> areaMasks, java.util.Map<String,Object> source, BooleanSupplier cancelled) throws IOException {
        check(cancelled);
        try(var out=Files.newBufferedWriter(directory.resolve("excluded-cells.csv"),StandardCharsets.UTF_8)) {
            out.write("cell_id,stage,first_failure_reason\n");
            for(var cell:result.rejectedCells()) {
                check(cancelled);
                out.write(csv(cell.id())+","+csv(cell.stage())+","+csv(cell.reason())+"\n");
            }
        }
        Files.writeString(directory.resolve("profile.json"),
                GsonTools.getInstance(true).toJson(result.profile()), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("roi.json"),
                GsonTools.getInstance(true).toJson(IfGeometryExport.roi(roi)), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("segmentation.json"), GsonTools.getInstance(true).toJson(segmentation));
        var measured = result.cells().stream().collect(java.util.stream.Collectors.toMap(IfAnalysis.CellResult::id, c -> c));
        var objects = new java.util.ArrayList<qupath.lib.objects.PathObject>();
        for (var cell : sourceCells) {
            check(cancelled);
            var values = measured.get(cell.id());
            if (values == null) continue;
            var object = qupath.lib.objects.PathObjects.createCellObject(cell.cell(), cell.nucleus());
            try { object.setID(java.util.UUID.fromString(cell.id())); }
            catch (IllegalArgumentException ignored) { object.setName(cell.id()); }
            object.getMetadata().put("IF source cell ID", cell.id());
            object.getMeasurementList().put("IF: PN mask", values.pn());
            object.getMeasurementList().put("IF: NPN mask", values.npn());
            object.getMeasurementList().put("IF: CPN mask", values.cpn());
            objects.add(object);
        }
        IfGeometryExport.objects(directory.resolve("cells.geojson"),objects,cancelled);
        var rejectedById=result.rejectedCells().stream().collect(java.util.stream.Collectors.toMap(IfAnalysis.RejectedCell::id,c->c));
        var rejectedObjects=new java.util.ArrayList<qupath.lib.objects.PathObject>();
        int rejectedCellsWithGeometry=0;
        for(var cell:sourceCells) {
            check(cancelled);
            var rejection=rejectedById.get(cell.id());
            if(rejection==null)continue;
            int before=rejectedObjects.size();
            for(int compartment=0;compartment<2;compartment++) {
                ROI shape=compartment==0?cell.cell():cell.nucleus();
                if(shape==null || shape.isEmpty())continue;
                // Review annotations, deliberately not detections eligible for automated cell analysis.
                var object=qupath.lib.objects.PathObjects.createAnnotationObject(shape);
                object.setName("Excluded: "+cell.id()+" / "+(compartment==0?"cell":"nucleus"));
                object.getMetadata().put("IF source cell ID",cell.id());
                object.getMetadata().put("IF review compartment",compartment==0?"cell":"nucleus");
                object.getMetadata().put("IF exclusion stage",rejection.stage());
                object.getMetadata().put("IF exclusion reason",rejection.reason());
                object.getMetadata().put("IF status","Excluded from measurements; not a negative phenotype");
                rejectedObjects.add(object);
            }
            if(rejectedObjects.size()>before)rejectedCellsWithGeometry++;
        }
        IfGeometryExport.objects(directory.resolve("excluded-cells.geojson"),rejectedObjects,cancelled);
        try (var out = Files.newBufferedWriter(directory.resolve("combinations.csv"), StandardCharsets.UTF_8)) {
            out.write("combination,mask,mode,metric,numerator,denominator_type,denominator,ratio,density_per_mm2,positive_area_mm2\n");
            for (var combination : result.combinations()) {
                check(cancelled);
                for (String mode : List.of("inclusive", "exact")) {
                    for (String metric : List.of("PA", "NPN", "CPN", "PN")) {
                        long value = count(combination, metric, mode.equals("exact"));
                        long denominator = metric.equals("PA") ? result.roiPixels() : result.cells().size();
                        writeRow(out, combination, mode, metric, value,
                                metric.equals("PA") ? "all_sampled_roi_pixels" : "all_eligible_cells",
                                denominator, result);
                        for (int c = 0; c < result.profile().markers().size(); c++) {
                            int bit = 1 << c;
                            if ((combination.bits() & bit) == 0) continue;
                            var single = result.combinations().stream().filter(v -> v.bits() == bit).findFirst().orElseThrow();
                            writeRow(out, combination, mode, metric, value,
                                    "positive:" + result.profile().markers().get(c).name(),
                                    count(single, metric, false), result);
                        }
                    }
                }
            }
        }
        try (var out = Files.newBufferedWriter(directory.resolve("cells.csv"), StandardCharsets.UTF_8)) {
            out.write("cell_id,x_source_px,y_source_px,nucleus_sample_pixels,cytoplasm_sample_pixels,NPN_mask,CPN_mask,PN_mask");
            for (var marker : result.profile().markers())
                out.write("," + csv(marker.name() + ":nucleus_mean") + "," + csv(marker.name() + ":cytoplasm_mean"));
            out.write("\n");
            for (var cell : result.cells()) {
                check(cancelled);
                out.write(csv(cell.id()) + "," + number(cell.x()) + "," + number(cell.y())
                        + "," + cell.nucleusPixels() + "," + cell.cytoplasmPixels()
                        + "," + cell.npn() + "," + cell.cpn() + "," + cell.pn());
                for (int c = 0; c < result.profile().markers().size(); c++)
                    out.write("," + number(cell.nucleusMeans().get(c)) + "," + number(cell.cytoplasmMeans().get(c)));
                out.write("\n");
            }
        }
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("schemaVersion", 1);
        manifest.put("software", IfSoftwareProvenance.snapshot());
        manifest.put("status", "complete");
        manifest.put("createdAt", Instant.now().toString());
        manifest.put("imageName", imageName);
        manifest.put("source",source);
        manifest.put("algorithm", "raw-tile-if-v1");
        manifest.put("profile", result.profile());
        manifest.put("downsample", result.downsample());
        manifest.put("pixelWidthMicrons", finiteOrNull(result.pixelWidthMicrons()));
        manifest.put("pixelHeightMicrons", finiteOrNull(result.pixelHeightMicrons()));
        manifest.put("sampledAreaMm2", finiteOrNull(result.sampledAreaMm2()));
        manifest.put("roiPixels", result.roiPixels());
        manifest.put("inputCells", result.inputCells());
        manifest.put("excludedCells", result.excludedCells());
        manifest.put("unmeasurableCells", result.unmeasurableCells());
        manifest.put("cellExclusionReasonCounts", result.rejectedCells().stream().collect(
                java.util.stream.Collectors.groupingBy(IfAnalysis.RejectedCell::reason,
                        java.util.TreeMap::new,java.util.stream.Collectors.counting())));
        manifest.put("cellExclusionRule", "One first-failing reason per rejected cell; eligibility precedes measurement; see excluded-cells.csv. Not a biological negativity classification.");
        manifest.put("eligibleCells", result.cells().size());
        manifest.put("nuclearMeasurements",writeNuclear(directory,result,sourceCells,cancelled));
        manifest.put("measurementCoverage", IfMeasurementCoverage.from(result));
        manifest.put("tilesRead", result.tilesRead());
        manifest.put("indexCertifiedZeroTilesCountedWithoutRead",result.zeroTilesCounted());
        manifest.put("analysisSeconds", result.elapsedSeconds());
        manifest.put("segmentation", segmentation);
        manifest.put("areaMasks",areaMasks);
        manifest.put("exportedCellGeometries", objects.size());
        if (!objects.isEmpty()) {
            IfClassificationFigure.write(directory.resolve("classification-pn.svg"),roi,sourceCells,result,cancelled);
            manifest.put("classificationFigure",java.util.Map.of("file","classification-pn.svg",
                    "metric","PN","population","eligible_measured_cells","excludedCandidatesShown",false,
                    "sha256",IfClassificationFigure.sha256(directory.resolve("classification-pn.svg"),cancelled),
                    "roiSha256",IfClassificationFigure.sha256(directory.resolve("roi.json"),cancelled),
                    "profileSha256",IfClassificationFigure.sha256(directory.resolve("profile.json"),cancelled),
                    "cellsSha256",IfClassificationFigure.sha256(directory.resolve("cells.csv"),cancelled),
                    "description","Vector classification map, not fluorescence intensity; exact mask colors; see profile.json and roi.json"));
        }
        manifest.put("excludedCellsWithReviewGeometry",rejectedCellsWithGeometry);
        manifest.put("excludedReviewGeometryFeatures",rejectedObjects.size());
        manifest.put("excludedGeometryFormat","Separate cell/nucleus review annotations, not cell detections; linked by IF source cell ID. Missing/empty compartments omitted; CSV retains all rejected cells.");
        manifest.put("definitions", List.of(
                "PA: raw pixel intensity >= pixel threshold; area is sampled ROI, not tissue area",
                "NPN/CPN: mean raw nucleus/cytoplasm intensity >= corresponding threshold",
                "PN: nuclear OR cytoplasmic positivity per marker",
                "inclusive AB includes ABC; exact AB excludes all other selected markers",
                "cells must be fully contained in ROI and have sampled nucleus and cytoplasm",
                "overall NPN/CPN/PN denominators are eligible measured cells, not all DAPI nuclei; even NPN requires sampled cytoplasm",
                "PA pixel overlap and cell co-expression do not establish molecular colocalization or interaction",
                "empty denominators and unavailable physical calibration are blank in CSV and null in JSON",
                "analysis ignores display LUT, gamma, contrast and viewer zoom",
                "pixel-center sampling; coarse resolution can miss small objects; not vendor-equivalent validation"));
        check(cancelled);
        Files.writeString(directory.resolve("manifest.json"),
                GsonTools.getInstance(true).newBuilder().serializeNulls().create().toJson(manifest), StandardCharsets.UTF_8);
        Files.delete(directory.resolve("INCOMPLETE"));
    }

    private static java.util.Map<String,Object> writeNuclear(Path directory,IfAnalysis.Result result,
            List<IfAnalysis.Cell> sourceCells,BooleanSupplier cancelled)throws IOException {
        var nuclear=result.nuclearMeasurements();
        try(var out=Files.newBufferedWriter(directory.resolve("nuclei.csv"),StandardCharsets.UTF_8)) {
            out.write("cell_id,x_source_px,y_source_px,nucleus_sample_pixels,NPN_nucleus_only_mask");
            for(var marker:result.profile().markers())out.write(","+csv(marker.name()+":nucleus_mean"));
            out.write("\n");
            for(var n:nuclear.nuclei()) {
                check(cancelled);out.write(csv(n.id())+","+number(n.x())+","+number(n.y())+","+n.pixels()+","+n.mask());
                for(double mean:n.means())out.write(","+number(mean));out.write("\n");
            }
        }
        try(var out=Files.newBufferedWriter(directory.resolve("excluded-nuclei.csv"),StandardCharsets.UTF_8)) {
            out.write("cell_id,first_failure_reason\n");
            for(var n:nuclear.rejected()){check(cancelled);out.write(csv(n.id())+","+csv(n.reason())+"\n");}
        }
        var singles=new java.util.HashMap<Integer,Long>();
        for(var c:nuclear.combinations())if(Integer.bitCount(c.mask())==1)singles.put(c.mask(),c.inclusive());
        try(var out=Files.newBufferedWriter(directory.resolve("nuclear-combinations.csv"),StandardCharsets.UTF_8)) {
            out.write("combination,mask,mode,metric,numerator,denominator_type,denominator,ratio,density_per_mm2,positive_area_mm2\n");
            for(var c:nuclear.combinations())for(String mode:List.of("inclusive","exact")) {
                check(cancelled);long value=mode.equals("inclusive")?c.inclusive():c.exact();
                var row=new IfAnalysis.Combination(c.mask(),c.markers(),0,0,0,0,0,0,0,0);
                writeRow(out,row,mode,"NPN_NUCLEUS_ONLY",value,"all_measured_nuclei",nuclear.nuclei().size(),result);
                for(int i=0;i<result.profile().markers().size();i++)if((c.mask()&(1<<i))!=0)
                    writeRow(out,row,mode,"NPN_NUCLEUS_ONLY",value,"positive:"+result.profile().markers().get(i).name(),singles.get(1<<i),result);
            }
        }
        var measured=nuclear.nuclei().stream().collect(java.util.stream.Collectors.toMap(IfNuclearMeasurements.Measurement::id,n->n));
        var objects=new java.util.ArrayList<qupath.lib.objects.PathObject>();
        for(var cell:sourceCells) {
            check(cancelled);var n=measured.get(cell.id());if(n==null)continue;
            var object=qupath.lib.objects.PathObjects.createAnnotationObject(cell.nucleus());
            object.setName("Measured nucleus: "+cell.id());object.getMetadata().put("IF source cell ID",cell.id());
            object.getMetadata().put("IF status","Independent nuclear measurement, not a full-cell PN classification");
            object.getMeasurementList().put("IF: NPN nucleus-only mask",n.mask());objects.add(object);
        }
        IfGeometryExport.objects(directory.resolve("nuclei.geojson"),objects,cancelled);
        return java.util.Map.of("schemaVersion",1,"algorithm","raw-nucleus-only-v1",
                "measuredNuclei",nuclear.nuclei().size(),"excludedNuclei",nuclear.rejected().size(),
                "denominator","all_measured_nuclei","geometryFeatures",objects.size(),
                "files",List.of("nuclei.csv","excluded-nuclei.csv","nuclear-combinations.csv","nuclei.geojson"),
                "definition","Valid nuclei fully inside ROI and image with at least one sampled pixel; nucleus mean >= nuclear threshold. Independent of cell/cytoplasm validity. Overlapping objects are not deduplicated. Not all DAPI proposals, vendor equivalence or accuracy.",
                "legacyResults","combinations.csv, cells.csv and their NPN/CPN/PN denominators are unchanged. Current Word/Excel reports include the separately labeled nuclear supplement; PN overlays remain full-cell only.");
    }

    private static void writeRow(java.io.Writer out, IfAnalysis.Combination c, String mode,
                                 String metric, long value, String denominatorName, long denominator,
                                 IfAnalysis.Result r) throws IOException {
        double area = r.sampledAreaMm2();
        double density = !metric.equals("PA") && area > 0 ? value / area : Double.NaN;
        double positiveArea = metric.equals("PA") && r.roiPixels() > 0 ? area * value / r.roiPixels() : Double.NaN;
        out.write(csv(c.markers()) + "," + c.bits() + "," + mode + "," + metric + "," + value
                + "," + csv(denominatorName) + "," + denominator + ","
                + number(denominator == 0 ? Double.NaN : (double) value / denominator)
                + "," + number(density) + "," + number(positiveArea) + "\n");
    }

    private static long count(IfAnalysis.Combination c, String metric, boolean exact) {
        return switch (metric) {
            case "PA" -> exact ? c.paExactPixels() : c.paPixels();
            case "NPN" -> exact ? c.npnExact() : c.npn();
            case "CPN" -> exact ? c.cpnExact() : c.cpn();
            case "PN" -> exact ? c.pnExact() : c.pn();
            default -> throw new IllegalArgumentException(metric);
        };
    }

    private static String csv(String s) {
        // Prevent spreadsheet formula execution for user-supplied marker/image labels.
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String number(double v) { return Double.isFinite(v) ? String.format(Locale.ROOT, "%.12g", v) : ""; }
    private static Double finiteOrNull(double v) { return Double.isFinite(v) ? v : null; }
    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
}
