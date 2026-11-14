package io.github.xiaofengzhou.qupath.mrxs;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathCellObject;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.interfaces.ROI;

import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/** One native-tile pass, raw values only. No viewer state or hierarchy mutation. */
public final class IfAnalysis {
    private IfAnalysis() {}

    public record Cell(String id, ROI cell, ROI nucleus) {
        public static Cell snapshot(PathCellObject object) {
            return new Cell(object.getID().toString(), object.getROI(), object.getNucleusROI());
        }
    }

    public record CellResult(String id, double x, double y, long nucleusPixels,
                             long cytoplasmPixels, List<Double> nucleusMeans,
                             List<Double> cytoplasmMeans, int npn, int cpn, int pn) {}

    /** First failing eligibility rule; one record per rejected input cell. */
    public record RejectedCell(String id, String stage, String reason) {}

    public record Combination(int bits, String markers, long paPixels, long paExactPixels,
                              long npn, long npnExact, long cpn, long cpnExact,
                              long pn, long pnExact) {}

    /** Synchronous consumer of one native-level label tile; no second source-image read.
     * Codes: 0 outside ROI; otherwise 1 + marker-positivity bitmask (up to 256).
     * levelX/levelY are coordinates in the chosen pyramid level, not full-resolution pixels. */
    @FunctionalInterface
    public interface MaskSink {
        void accept(int levelX, int levelY, BufferedImage labels) throws IOException;
        /** Exact constant labels; default preserves existing sinks without a new storage contract. */
        default void acceptConstant(int levelX,int levelY,int width,int height,int code)throws IOException {
            if(width<1||height<1||(long)width*height>4_194_304L||code<0||code>256)
                throw new IOException("Invalid constant mask tile");
            var labels=new BufferedImage(width,height,BufferedImage.TYPE_USHORT_GRAY);
            java.util.Arrays.fill(((java.awt.image.DataBufferUShort)labels.getRaster().getDataBuffer()).getData(),(short)code);
            accept(levelX,levelY,labels);
        }
    }

    public record Result(IfAnalysisProfile profile, double downsample,
                         double pixelWidthMicrons, double pixelHeightMicrons,
                         long roiPixels, int inputCells, int excludedCells,
                         int unmeasurableCells, List<CellResult> cells,
                         List<Combination> combinations, int tilesRead,
                         double elapsedSeconds, List<RejectedCell> rejectedCells, int zeroTilesCounted,
                         IfNuclearMeasurements.Result nuclearMeasurements) {
        public double sampledAreaMm2() {
            return roiPixels * downsample * downsample
                    * pixelWidthMicrons * pixelHeightMicrons / 1_000_000.0;
        }
    }

    public static Result analyze(ImageServer<BufferedImage> server, ROI roi,
                                 IfAnalysisProfile profile, List<Cell> cells,
                                 BooleanSupplier cancelled, DoubleConsumer progress) throws IOException {
        return analyze(server, roi, profile, cells, cancelled, progress, null);
    }

    public static Result analyze(ImageServer<BufferedImage> server, ROI roi,
                                 IfAnalysisProfile profile, List<Cell> cells,
                                 BooleanSupplier cancelled, DoubleConsumer progress, MaskSink masks) throws IOException {
        return analyze(server,roi,profile,cells,cancelled,progress,masks,true);
    }

    /** Package-private forced-pixel reference path for regression tests. */
    static Result analyze(ImageServer<BufferedImage> server,ROI roi,IfAnalysisProfile profile,List<Cell> cells,
                          BooleanSupplier cancelled,DoubleConsumer progress,MaskSink masks,boolean allowZeroFastPath) throws IOException {
        long start = System.nanoTime();
        if (roi == null || !roi.isArea() || roi.isEmpty())
            throw new IllegalArgumentException("Select a non-empty area annotation");
        var regionGeometry=roi.getGeometry();
        if(!validBoundary(roi))
            throw new IllegalArgumentException("Analysis ROI has invalid geometry; review its boundary before analysis");
        if (server.isRGB()) throw new IllegalArgumentException("Analysis requires separate fluorescence channels");
        if (profile.resolutionLevel() >= server.nResolutions())
            throw new IllegalArgumentException("The profile resolution does not exist in this image");
        if (roi.getZ() < 0 || roi.getZ() >= server.nZSlices() || roi.getT() < 0 || roi.getT() >= server.nTimepoints())
            throw new IllegalArgumentException("ROI plane is outside the image");
        for (var marker : profile.markers()) {
            if (marker.channel() >= server.nChannels()
                    || !server.getMetadata().getChannels().get(marker.channel()).getName().equals(marker.name()))
                throw new IllegalArgumentException("Channel mapping changed for " + marker.name()
                        + "; review or recreate the profile");
        }
        double ds = server.getDownsampleForResolution(profile.resolutionLevel());
        var request = RegionRequest.createInstance(server.getPath(), ds, roi);
        var tiles = server.getTileRequestManager().getTileRequests(request);
        Shape roiShape = RoiTools.getShape(roi);
        var prepared = PreparedGeometryFactory.prepare(regionGeometry);
        int k = profile.markers().size();
        long[] areaHist = new long[1 << k];
        List<Accumulator> accumulators = new ArrayList<>();
        List<RejectedCell> rejected = new ArrayList<>();
        var nuclear=new IfNuclearMeasurements.Collector(profile,cells,cancelled);
        var tree = new STRtree();
        for (Cell cell : cells) {
            checkCancel(cancelled);
            String reason=null;
            if(cell.cell()==null) reason="missing_cell_roi";
            else if(cell.nucleus()==null) reason="missing_nucleus_roi";
            else if(!cell.cell().isArea() || !cell.nucleus().isArea() || cell.cell().isEmpty() || cell.nucleus().isEmpty()) reason="empty_or_nonarea_compartment";
            else if(cell.cell().getZ()!=roi.getZ() || cell.cell().getT()!=roi.getT()
                    || cell.nucleus().getZ()!=roi.getZ() || cell.nucleus().getT()!=roi.getT()) reason="different_image_plane";
            else if(cell.cell().getBoundsX()<0 || cell.cell().getBoundsY()<0
                    || cell.cell().getBoundsX()+cell.cell().getBoundsWidth()>server.getWidth()
                    || cell.cell().getBoundsY()+cell.cell().getBoundsHeight()>server.getHeight()) reason="cell_outside_image";
            else if(!validBoundary(cell.cell())) reason="invalid_cell_geometry";
            else if(!validBoundary(cell.nucleus())) reason="invalid_nucleus_geometry";
            else if(!prepared.covers(cell.cell().getGeometry())) reason="cell_not_fully_inside_roi";
            else if(!cell.cell().getGeometry().covers(cell.nucleus().getGeometry())) reason="nucleus_not_inside_cell";
            if(reason!=null) {
                rejected.add(new RejectedCell(cell.id(),"eligibility",reason));
                nuclear.addFallback(cell,roi,prepared,server.getWidth(),server.getHeight());continue;
            }
            var acc = new Accumulator(cell, k);
            accumulators.add(acc);
            tree.insert(cell.cell().getGeometry().getEnvelopeInternal(), acc);
        }
        tree.build();
        nuclear.build();
        int read = 0;
        int zeroTilesCounted=0;
        long roiPixels = 0;
        // Reuse analysis-owned buffers between native tiles. Never retain or alter reader rasters.
        double[][] values = new double[k][];
        boolean[] inside = new boolean[0];
        for (var tile : tiles) {
            checkCancel(cancelled);
            if (tile.getLevel() != profile.resolutionLevel())
                throw new IOException("Server chose a different pyramid level than requested");
            if ((long) tile.getTileWidth() * tile.getTileHeight() > 4_194_304L)
                throw new IOException("Reader tiles exceed the analysis memory limit; use a tiled image server");
            double x0 = tile.getTileX() * ds, y0 = tile.getTileY() * ds;
            if (!roiShape.intersects(x0, y0, tile.getTileWidth() * ds, tile.getTileHeight() * ds)) continue;
            int w = tile.getTileWidth(), h = tile.getTileHeight();
            // Only index-certified zeros, entirely inside the ROI and without cells.
            // Avoid constructing known-zero rasters; report these separately from actual reads.
            double clippedWidth=Math.min(w*ds,server.getWidth()-x0);
            double clippedHeight=Math.min(h*ds,server.getHeight()-y0);
            if(allowZeroFastPath && server instanceof MrxsImageServer mrxs
                    && roiShape.contains(x0,y0,clippedWidth,clippedHeight)
                    && tree.query(new Envelope(x0,x0+w*ds,y0,y0+h*ds)).isEmpty()
                    && !nuclear.intersects(new Envelope(x0,x0+w*ds,y0,y0+h*ds))
                    && !mrxs.hasStoredImage(tile)) {
                long count=zeroTilePixelCount(x0,y0,w,h,ds,server.getWidth(),server.getHeight());
                int zeroMask=zeroPixelMask(profile);
                roiPixels+=count;areaHist[zeroMask]+=count;
                if(masks!=null) {
                    int nx=(int)Math.max(0,Math.min(w,Math.ceil((server.getWidth()-x0)/ds-0.5)));
                    int ny=(int)Math.max(0,Math.min(h,Math.ceil((server.getHeight()-y0)/ds-0.5)));
                    checkCancel(cancelled);
                    if(nx==w&&ny==h)masks.acceptConstant(tile.getTileX(),tile.getTileY(),w,h,zeroMask+1);
                    else {
                        var labels=new BufferedImage(w,h,BufferedImage.TYPE_USHORT_GRAY);
                        short[] codes=((java.awt.image.DataBufferUShort)labels.getRaster().getDataBuffer()).getData();
                        for(int y=0;y<ny;y++)java.util.Arrays.fill(codes,y*w,y*w+nx,(short)(zeroMask+1));
                        checkCancel(cancelled);masks.accept(tile.getTileX(),tile.getTileY(),labels);
                    }
                }
                zeroTilesCounted++;progress.accept((double)(read+zeroTilesCounted)/Math.max(1,tiles.size()));continue;
            }
            var image = server.readRegion(tile.getRegionRequest());
            if (image == null || image.getWidth() != w || image.getHeight() != h)
                throw new IOException("Reader returned unexpected native tile dimensions");
            int pixels=w*h;
            for (int c = 0; c < k; c++) {
                if(values[c]==null || values[c].length<pixels)values[c]=new double[pixels];
                image.getRaster().getSamples(0,0,w,h,profile.markers().get(c).channel(),values[c]);
            }
            if(inside.length<pixels)inside=new boolean[pixels];
            else java.util.Arrays.fill(inside,0,pixels,false);
            var labels = masks == null ? null : new BufferedImage(w, h, BufferedImage.TYPE_USHORT_GRAY);
            // This newly allocated image is contiguous, offset-zero and analysis-owned.
            // Never use this direct-storage path on a reader raster or a subimage.
            short[] labelCodes=labels==null?null:((java.awt.image.DataBufferUShort)labels.getRaster().getDataBuffer()).getData();
            for (int y = 0; y < h; y++) {
                checkCancel(cancelled);
                double sy = y0 + (y + 0.5) * ds;
                for (int x = 0; x < w; x++) {
                    double sx = x0 + (x + 0.5) * ds;
                    int p = y * w + x;
                    if (sx >= server.getWidth() || sy >= server.getHeight() || !roiShape.contains(sx, sy)) continue;
                    inside[p] = true;
                    roiPixels++;
                    int mask = 0;
                    for (int c = 0; c < k; c++) {
                        if (!Double.isFinite(values[c][p])) throw new IOException("Non-finite raw pixel in ROI");
                        if (values[c][p] >= profile.markers().get(c).pixelThreshold()) mask |= 1 << c;
                    }
                    areaHist[mask]++;
                    if (labelCodes != null) labelCodes[p]=(short)(mask+1);
                }
            }
            for (Object item : tree.query(new Envelope(x0, x0 + w * ds, y0, y0 + h * ds))) {
                checkCancel(cancelled);
                var acc = (Accumulator) item;
                var bounds = acc.cellShape.getBounds2D();
                int minX = Math.max(0, (int) Math.floor((bounds.getMinX() - x0) / ds));
                int maxX = Math.min(w, (int) Math.ceil((bounds.getMaxX() - x0) / ds));
                int minY = Math.max(0, (int) Math.floor((bounds.getMinY() - y0) / ds));
                int maxY = Math.min(h, (int) Math.ceil((bounds.getMaxY() - y0) / ds));
                for (int y = minY; y < maxY; y++) {
                    checkCancel(cancelled);
                    for (int x = minX; x < maxX; x++) {
                        int p = y * w + x;
                        double sx = x0 + (x + 0.5) * ds, sy = y0 + (y + 0.5) * ds;
                        if (!inside[p] || !acc.cellShape.contains(sx, sy)) continue;
                        boolean insideNucleus = acc.nucleusShape.contains(sx, sy);
                        if (insideNucleus) acc.nucleusPixels++; else acc.cytoplasmPixels++;
                        double[] sums = insideNucleus ? acc.nucleusSums : acc.cytoplasmSums;
                        for (int c = 0; c < k; c++) sums[c] += values[c][p];
                    }
                }
            }
            nuclear.sample(x0,y0,w,h,ds,inside,values);
            if (labels != null) { checkCancel(cancelled); masks.accept(tile.getTileX(), tile.getTileY(), labels); }
            read++;
            progress.accept((double) (read+zeroTilesCounted) / Math.max(1, tiles.size()));
        }
        checkCancel(cancelled);
        if (roiPixels == 0) throw new IllegalArgumentException("No image pixel centers inside ROI at this resolution");
        long[] npnHist = new long[1 << k], cpnHist = new long[1 << k], pnHist = new long[1 << k];
        List<CellResult> results = new ArrayList<>();
        int missing = 0;
        for (var acc : accumulators) {
            nuclear.accept(acc.cell,acc.nucleusPixels,acc.nucleusSums);
            // Both compartments are required so PN/NPN/CPN share the same denominator.
            if (acc.nucleusPixels == 0 || acc.cytoplasmPixels == 0) {
                missing++;
                String reason=acc.nucleusPixels==0 && acc.cytoplasmPixels==0 ? "no_valid_compartment_pixels"
                        : acc.nucleusPixels==0 ? "no_valid_nucleus_pixels" : "no_valid_cytoplasm_pixels";
                rejected.add(new RejectedCell(acc.cell.id(),"measurement",reason));continue;
            }
            int n = 0, c = 0;
            List<Double> nm = new ArrayList<>(), cm = new ArrayList<>();
            for (int j = 0; j < k; j++) {
                double nv = acc.nucleusSums[j] / acc.nucleusPixels;
                double cv = acc.cytoplasmSums[j] / acc.cytoplasmPixels;
                nm.add(nv); cm.add(cv);
                if (nv >= profile.markers().get(j).nucleusThreshold()) n |= 1 << j;
                if (cv >= profile.markers().get(j).cytoplasmThreshold()) c |= 1 << j;
            }
            npnHist[n]++; cpnHist[c]++; pnHist[n | c]++;
            results.add(new CellResult(acc.cell.id(), acc.cell.nucleus().getCentroidX(),
                    acc.cell.nucleus().getCentroidY(), acc.nucleusPixels, acc.cytoplasmPixels,
                    List.copyOf(nm), List.copyOf(cm), n, c, n | c));
        }
        long[] ai = inclusive(areaHist), ni = inclusive(npnHist), ci = inclusive(cpnHist), pi = inclusive(pnHist);
        List<Combination> combinations = new ArrayList<>();
        for (int mask : MrxsCombinationExport.combinationMasks(k)) {
            List<String> names = new ArrayList<>();
            for (int c = 0; c < k; c++) if ((mask & (1 << c)) != 0) names.add(profile.markers().get(c).name());
            combinations.add(new Combination(mask, String.join("+", names), ai[mask], areaHist[mask],
                    ni[mask], npnHist[mask], ci[mask], cpnHist[mask], pi[mask], pnHist[mask]));
        }
        var nuclearResult=nuclear.finish();
        progress.accept(1.0);
        var cal = server.getPixelCalibration();
        return new Result(profile, ds, cal.getPixelWidthMicrons(), cal.getPixelHeightMicrons(),
                roiPixels, cells.size(), cells.size() - accumulators.size(), missing, List.copyOf(results),
                List.copyOf(combinations), read, (System.nanoTime() - start) / 1e9, List.copyOf(rejected),zeroTilesCounted,nuclearResult);
    }

    static long zeroTilePixelCount(double x,double y,int w,int h,double ds,int imageWidth,int imageHeight) {
        long nx=Math.max(0,Math.min(w,(long)Math.ceil((imageWidth-x)/ds-0.5)));
        long ny=Math.max(0,Math.min(h,(long)Math.ceil((imageHeight-y)/ds-0.5)));
        return nx*ny;
    }

    static int zeroPixelMask(IfAnalysisProfile profile) {
        int mask=0;
        for(int c=0;c<profile.markers().size();c++)if(0>=profile.markers().get(c).pixelThreshold())mask|=1<<c;
        return mask;
    }

    /** Superset zeta transform: O(k 2^k), independent of image size. */
    static long[] inclusive(long[] exact) {
        long[] result = exact.clone();
        for (int bit = 1; bit < result.length; bit <<= 1)
            for (int mask = 0; mask < result.length; mask++)
                if ((mask & bit) == 0) result[mask] += result[mask | bit];
        return result;
    }

    static boolean validBoundary(ROI roi) {
        // PolygonROI.getGeometry() may normalize self-intersections. Validate the stored
        // vertices before relying on that geometry for eligibility and the raw Shape for sampling.
        if(roi instanceof qupath.lib.roi.PolygonROI) {
            var points=roi.getAllPoints();
            if(points.size()<3)return false;
            var coordinates=new org.locationtech.jts.geom.Coordinate[points.size()+1];
            for(int i=0;i<points.size();i++) {
                var point=points.get(i);
                if(!Double.isFinite(point.getX())||!Double.isFinite(point.getY()))return false;
                coordinates[i]=new org.locationtech.jts.geom.Coordinate(point.getX(),point.getY());
            }
            coordinates[points.size()]=coordinates[0].copy();
            return new org.locationtech.jts.geom.GeometryFactory().createPolygon(coordinates).isValid();
        }
        return roi.getGeometry().isValid();
    }

    private static void checkCancel(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new CancellationException();
    }

    private static final class Accumulator {
        final Cell cell;
        final Shape cellShape, nucleusShape;
        final double[] nucleusSums, cytoplasmSums;
        long nucleusPixels, cytoplasmPixels;
        Accumulator(Cell cell, int channels) {
            this.cell = cell;
            cellShape = RoiTools.getShape(cell.cell());
            nucleusShape = RoiTools.getShape(cell.nucleus());
            nucleusSums = new double[channels]; cytoplasmSums = new double[channels];
        }
    }
}
