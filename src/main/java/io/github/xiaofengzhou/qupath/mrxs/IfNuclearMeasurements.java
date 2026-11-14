package io.github.xiaofengzhou.qupath.mrxs;

import java.awt.Shape;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.index.strtree.STRtree;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.interfaces.ROI;

/** Independent nuclear validity, sharing the raw-tile pass. Not all DAPI proposals. */
public final class IfNuclearMeasurements {
    private IfNuclearMeasurements() {}
    public record Measurement(String id,double x,double y,long pixels,List<Double> means,int mask) {}
    public record Rejection(String id,String reason) {}
    public record Combination(int mask,String markers,long inclusive,long exact) {}
    public record Result(List<Measurement> nuclei,List<Rejection> rejected,List<Combination> combinations) {}
    static final class Collector {
        final IfAnalysisProfile profile; final BooleanSupplier cancelled;
        final Map<String,Measurement> measured=new HashMap<>();
        final Map<String,Rejection> rejected=new HashMap<>();
        final List<IfAnalysis.Cell> inputs;
        final STRtree tree=new STRtree(); final List<Pending> pending=new ArrayList<>();
        Collector(IfAnalysisProfile profile,List<IfAnalysis.Cell> inputs,BooleanSupplier cancelled) {
            this.profile=profile;this.inputs=inputs;this.cancelled=cancelled;
            var ids=new HashSet<String>();
            for(var cell:inputs) {check();if(cell.id()==null||!ids.add(cell.id()))throw new IllegalArgumentException("Nuclear measurements require unique non-null cell IDs");}
        }
        // Only legacy-ineligible objects require additional sampling; accepted cells reuse sums.
        void addFallback(IfAnalysis.Cell cell,ROI region,PreparedGeometry prepared,int width,int height) {
            check();ROI n=cell.nucleus();String reason=null;
            if(n==null)reason="missing_nucleus_roi";
            else if(!n.isArea()||n.isEmpty())reason="empty_or_nonarea_nucleus";
            else if(n.getZ()!=region.getZ()||n.getT()!=region.getT())reason="different_image_plane";
            else if(n.getBoundsX()<0||n.getBoundsY()<0||n.getBoundsX()+n.getBoundsWidth()>width||n.getBoundsY()+n.getBoundsHeight()>height)reason="nucleus_outside_image";
            else if(!IfAnalysis.validBoundary(n))reason="invalid_nucleus_geometry";
            else if(!prepared.covers(n.getGeometry()))reason="nucleus_not_fully_inside_roi";
            if(reason!=null){rejected.put(cell.id(),new Rejection(cell.id(),reason));return;}
            var p=new Pending(cell,RoiTools.getShape(n),new double[profile.markers().size()]);
            pending.add(p);tree.insert(n.getGeometry().getEnvelopeInternal(),p);
        }
        void build(){tree.build();}
        boolean intersects(Envelope e){return !pending.isEmpty()&&!tree.query(e).isEmpty();}
        void sample(double x0,double y0,int w,int h,double ds,boolean[] inside,double[][] values) {
            if(pending.isEmpty())return;
            for(Object item:tree.query(new Envelope(x0,x0+w*ds,y0,y0+h*ds))) {
                check();var p=(Pending)item;var b=p.shape.getBounds2D();
                int x1=Math.max(0,(int)Math.floor((b.getMinX()-x0)/ds)),x2=Math.min(w,(int)Math.ceil((b.getMaxX()-x0)/ds));
                int y1=Math.max(0,(int)Math.floor((b.getMinY()-y0)/ds)),y2=Math.min(h,(int)Math.ceil((b.getMaxY()-y0)/ds));
                for(int y=y1;y<y2;y++) {check();for(int x=x1;x<x2;x++) {
                    int index=y*w+x;
                    if(!inside[index]||!p.shape.contains(x0+(x+0.5)*ds,y0+(y+0.5)*ds))continue;
                    p.pixels++;for(int c=0;c<p.sums.length;c++)p.sums[c]+=values[c][index];
                }}
            }
        }
        void accept(IfAnalysis.Cell cell,long pixels,double[] sums) {
            check();if(pixels==0){rejected.put(cell.id(),new Rejection(cell.id(),"no_valid_nucleus_pixels"));return;}
            var means=new ArrayList<Double>();int mask=0;
            for(int c=0;c<sums.length;c++) {
                double mean=sums[c]/pixels;if(!Double.isFinite(mean))throw new IllegalArgumentException("Non-finite nuclear mean");
                means.add(mean);if(mean>=profile.markers().get(c).nucleusThreshold())mask|=1<<c;
            }
            measured.put(cell.id(),new Measurement(cell.id(),cell.nucleus().getCentroidX(),cell.nucleus().getCentroidY(),pixels,List.copyOf(means),mask));
        }
        Result finish() {
            for(var p:pending)accept(p.cell,p.pixels,p.sums);
            var nuclei=new ArrayList<Measurement>();var failures=new ArrayList<Rejection>();long[] exact=new long[1<<profile.markers().size()];
            for(var cell:inputs) {
                check();var value=measured.get(cell.id());
                if(value!=null){nuclei.add(value);exact[value.mask()]++;}
                else {var failure=rejected.get(cell.id());if(failure==null)throw new IllegalStateException("Unaccounted nuclear input");failures.add(failure);}
            }
            long[] inclusive=IfAnalysis.inclusive(exact);var combinations=new ArrayList<Combination>();
            for(int mask:MrxsCombinationExport.combinationMasks(profile.markers().size())) {
                var names=new ArrayList<String>();for(int c=0;c<profile.markers().size();c++)if((mask&(1<<c))!=0)names.add(profile.markers().get(c).name());
                combinations.add(new Combination(mask,String.join("+",names),inclusive[mask],exact[mask]));
            }
            return new Result(List.copyOf(nuclei),List.copyOf(failures),List.copyOf(combinations));
        }
        void check(){if(Thread.currentThread().isInterrupted()||cancelled.getAsBoolean())throw new CancellationException();}
    }
    private static final class Pending {
        final IfAnalysis.Cell cell;final Shape shape;final double[] sums;long pixels;
        Pending(IfAnalysis.Cell cell,Shape shape,double[] sums){this.cell=cell;this.shape=shape;this.sums=sums;}
    }
}
