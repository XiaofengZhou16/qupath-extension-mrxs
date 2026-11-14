package io.github.xiaofengzhou.qupath.mrxs;

import qupath.lib.roi.interfaces.ROI;

/** Constant-space estimate, without enumerating tiles or reading image pixels. */
record IfAnalysisWorkload(long measurementBoundingPixels,long nativeBoundingPixels,int markerCount,double downsample) {
    static IfAnalysisWorkload estimate(int width,int height,ROI roi,double downsample,int markerCount) {
        if(width<1 || height<1 || roi==null || !roi.isArea() || roi.isEmpty()
                || !Double.isFinite(downsample) || downsample<1 || markerCount<1 || markerCount>8)
            throw new IllegalArgumentException("Select a valid area ROI and 1–8 markers");
        double x0=Math.max(0,roi.getBoundsX()),y0=Math.max(0,roi.getBoundsY());
        double x1=Math.min(width,roi.getBoundsX()+roi.getBoundsWidth()),y1=Math.min(height,roi.getBoundsY()+roi.getBoundsHeight());
        long sampled=centers(x0,x1,downsample)*centers(y0,y1,downsample);
        long nativePixels=centers(x0,x1,1)*centers(y0,y1,1);
        return new IfAnalysisWorkload(sampled,nativePixels,markerCount,downsample);
    }
    private static long centers(double low,double high,double ds) {
        return Math.max(0,(long)Math.ceil(high/ds-0.5)-(long)Math.ceil(low/ds-0.5));
    }
    boolean requiresConfirmation(boolean segmentation) {
        return measurementBoundingPixels>100_000_000L || (segmentation && nativeBoundingPixels>100_000_000L);
    }
    String description(boolean segmentation) {
        return String.format(java.util.Locale.ROOT,
                "Measurement bounding box: %,d sampled pixel centers × %d markers (downsample %.3f).\n"
                +"Native bounding box: %,d pixel centers.\n"
                +"This is an upper bound on ROI membership, not an ETA or memory estimate; holes and blank tissue are not inferred. Tile borders may add reading work.\n%s",
                measurementBoundingPixels,markerCount,downsample,nativeBoundingPixels,
                segmentation?"Segmentation has separate resolution settings: lowering measurement resolution does not reduce AI inference work."
                        :"No automatic resolution change will be made.");
    }
}
