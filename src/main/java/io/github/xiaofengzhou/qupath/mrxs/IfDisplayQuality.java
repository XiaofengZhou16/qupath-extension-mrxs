package io.github.xiaofengzhou.qupath.mrxs;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Display-range audit only: no inference about staining, sensor saturation or phenotype. */
final class IfDisplayQuality {
    record Channel(int sourceChannelIndex, String sourceChannelName, double effectiveMinimum,
                   double effectiveMaximum, long finitePixels, long nonFinitePixels,
                   long belowMinimum, long atMinimum, long aboveMaximum, long atMaximum,
                   Double observedMinimum, Double observedMaximum, Double belowMinimumFraction,
                   Double aboveMaximumFraction) {}
    record Report(long sampledRoiPixels, List<Channel> channels, List<String> definitions) {}

    static Report measure(BufferedImage raw, boolean[] inside, IfDisplayProfile profile, BooleanSupplier cancelled) {
        if(inside==null)throw new IllegalArgumentException("ROI mask required");
        return measure(raw,inside,profile,cancelled,false);
    }
    static Report measurePreview(BufferedImage raw,IfDisplayProfile profile,BooleanSupplier cancelled) {
        return measure(raw,null,profile,cancelled,true);
    }
    private static Report measure(BufferedImage raw,boolean[] inside,IfDisplayProfile profile,BooleanSupplier cancelled,boolean preview) {
        int width=raw.getWidth(), height=raw.getHeight();
        if(!preview&&inside.length!=Math.multiplyExact(width,height)) throw new IllegalArgumentException("ROI mask dimensions differ from raster");
        var selected=profile.selectedChannels();
        var raster=raw.getRaster();
        int n=selected.size();
        long[][] counts=new long[n][6]; // finite, nonfinite, below, equal min, above, equal max
        double[] minimum=new double[n],maximum=new double[n],observedMin=new double[n],observedMax=new double[n];
        for(int i=0;i<n;i++) {
            var c=selected.get(i);
            if(c.index()>=raster.getNumBands())throw new IllegalArgumentException("Channel outside raw raster");
            // QuPath display endpoints are floats, even when the JSON profile contains doubles.
            minimum[i]=(float)c.minimum(); maximum[i]=(float)c.maximum();
            observedMin[i]=Double.POSITIVE_INFINITY; observedMax[i]=Double.NEGATIVE_INFINITY;
        }
        long pixels=0;
        for(int y=0;y<height;y++) {
            if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())throw new CancellationException();
            for(int x=0;x<width;x++) {
                if(!preview&&!inside[y*width+x])continue;
                pixels++;
                for(int i=0;i<n;i++) {
                    double value=raster.getSampleDouble(x,y,selected.get(i).index());
                    var c=counts[i];
                    if(!Double.isFinite(value)){c[1]++;continue;}
                    c[0]++;observedMin[i]=Math.min(observedMin[i],value);observedMax[i]=Math.max(observedMax[i],value);
                    if(value<minimum[i])c[2]++;else if(value==minimum[i])c[3]++;
                    if(value>maximum[i])c[4]++;else if(value==maximum[i])c[5]++;
                }
            }
        }
        var result=new ArrayList<Channel>();
        for(int i=0;i<n;i++) {
            var c=counts[i];var source=selected.get(i);
            result.add(new Channel(source.index(),source.name(),minimum[i],maximum[i],c[0],c[1],c[2],c[3],c[4],c[5],
                    c[0]==0?null:observedMin[i],c[0]==0?null:observedMax[i],
                    c[0]==0?null:(double)c[2]/c[0],c[0]==0?null:(double)c[4]/c[0]));
        }
        return new Report(pixels,List.copyOf(result),List.of(
                preview?"Preview bounding-box raster, including outside-ROI pixels and holes; not native-resolution ROI quantification"
                        :"ROI pixel-center sampling at the exported native level; outside pixels and ROI holes excluded",
                "Counts use unmodified source-channel samples before color, gamma, grayscale or inversion",
                "Strict below/above counts indicate display-range clipping; equality at each endpoint is reported separately",
                "Fractions use finite source samples per channel; zero denominators are null, not zero",
                "Effective range endpoints use the same float conversion as QuPath display transforms",
                "These are not sensor-saturation, staining-positivity or AI-accuracy estimates; acquisition saturation cannot be inferred here",
                "Additive RGB-composite clipping and 8-bit rounding are not measured by this per-channel audit",
                preview?"Preview pyramid reduction may hide extremes; export QC must be checked separately"
                        :"Coarser native levels may average away extremes; this report describes the exported raster only"));
    }

    static String previewSummary(Report report) {
        var text=new StringBuilder("Preview display-range QC / 预览显示范围质控 — bounding-box pixels: ")
                .append(report.sampledRoiPixels()).append('\n');
        if(report.channels().isEmpty())text.append("No display channels selected.\n");
        for(var c:report.channels())text.append(c.sourceChannelName()).append(": below min ")
                .append(percent(c.belowMinimumFraction())).append("; above max ").append(percent(c.aboveMaximumFraction()))
                .append("; at min/max ").append(c.atMinimum()).append('/').append(c.atMaximum())
                .append("; nonfinite ").append(c.nonFinitePixels()).append('\n');
        return text.append("Preview only: includes ROI holes/outside pixels; reduced resolution may hide extremes. ")
                .append("Not staining positivity or sensor saturation. Export has separate ROI-masked QC.").toString();
    }

    static String summary(Report report) {
        var text=new StringBuilder("IF DISPLAY-RANGE QC / 显示范围质控\n\n");
        text.append("Sampled ROI pixels: ").append(report.sampledRoiPixels()).append("\n");
        for(var c:report.channels()) {
            text.append(String.format(Locale.ROOT,"\n[%d] %s — effective range %.8g to %.8g\n",
                    c.sourceChannelIndex(),c.sourceChannelName(),c.effectiveMinimum(),c.effectiveMaximum()));
            text.append("Finite: ").append(c.finitePixels()).append("; nonfinite: ").append(c.nonFinitePixels()).append('\n');
            text.append("Below minimum: ").append(c.belowMinimum()).append(" (").append(percent(c.belowMinimumFraction())).append("); at minimum: ").append(c.atMinimum()).append('\n');
            text.append("Above maximum: ").append(c.aboveMaximum()).append(" (").append(percent(c.aboveMaximumFraction())).append("); at maximum: ").append(c.atMaximum()).append('\n');
        }
        text.append("\nInterpretation / 解释\n显示范围外的像素不等于扫描时过曝，也不代表染色阳性。请结合原始数据审查；程序不会自动调整显示范围。\n");
        for(var definition:report.definitions())text.append("- ").append(definition).append('\n');
        return text.toString();
    }
    private static String percent(Double fraction){return fraction==null?"undefined":String.format(Locale.ROOT,"%.4f%%",fraction*100);}
}
