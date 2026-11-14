package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.*;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.plugins.PathTask;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.io.GsonTools;

/** Version-specific diagnostic only. Observes completed tile proxies before official merging. */
public class IfPremergeAuditSmoke {
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args[2]);if(Files.exists(output))throw new IllegalArgumentException("Use a new output file");
        var reports=new ArrayList<Map<String,Object>>();
        boolean labels=args.length>3&&args[3].equals("labels");
        var labelReports=Collections.synchronizedList(new ArrayList<Map<String,Object>>());
        try(var server=new MrxsImageServer(Path.of(args[0]).toUri())){
            var data=new ImageData<>(server,ImageData.ImageType.FLUORESCENCE);
            var parent=PathObjects.createAnnotationObject(ROIs.createRectangleROI(32768,81920,4096,4096,ImagePlane.getDefaultPlane()));
            data.getHierarchy().addObject(parent);
            var delegate=new IfInferenceRunner(1,()->false,p->{});
            TaskRunner runner=new TaskRunner(){
                public boolean isCancelled(){return false;}
                public void runTasks(String message,Collection<? extends Runnable> tasks){
                    if(labels)for(var task:tasks)if(task instanceof PathTask)observeLabels(task,labelReports);
                    delegate.runTasks(message,tasks);
                    var tileObjects=new ArrayList<PathObject>();int tiles=0;
                    for(var task:tasks)if(task instanceof PathTask){
                        try{
                            // Explicitly tied to QuPath 0.7; fail rather than silently change observation stage.
                            if(!task.getClass().getName().equals("qupath.lib.experimental.pixels.PixelProcessor$ProcessorTask"))throw new IllegalStateException("Unexpected tile task class");
                            var field=task.getClass().getDeclaredField("parentProxy");field.setAccessible(true);
                            var proxy=(PathObject)field.get(task);if(proxy==null)throw new IllegalStateException("Missing tile proxy");
                            tileObjects.addAll(proxy.getChildObjects());tiles++;
                        }catch(ReflectiveOperationException e){throw new IllegalStateException("Premerge inspection unavailable",e);}
                    }
                    if(tiles>0){
                        if(tileObjects.isEmpty())throw new IllegalStateException("No premerge objects observed");
                        var audit=new LinkedHashMap<>(IfInstanSeg.auditOutput(tileObjects,()->false));
                        audit.put("stage","completed per-tile output proxies before official global merge/overlap fix");
                        audit.put("tiles",tiles);reports.add(audit);
                        System.out.println("IF_PREMERGE "+GsonTools.getInstance().toJson(audit));
                    }
                }
            };
            var core=Class.forName(IfInstanSeg.CORE_CLASS);var builder=core.getMethod("builder").invoke(null);
            set(builder,"modelPath",Path.class,Path.of(args[1]));set(builder,"device",String.class,"mps");
            set(builder,"tileDims",int.class,512);set(builder,"interTilePadding",int.class,32);
            set(builder,"inputChannelIndices",Collection.class,List.of(0,1,2));
            builder.getClass().getMethod("outputCells").invoke(builder);
            set(builder,"makeMeasurements",boolean.class,false);set(builder,"randomColors",boolean.class,false);
            set(builder,"taskRunner",TaskRunner.class,runner);
            var engine=builder.getClass().getMethod("build").invoke(builder);
            var result=core.getMethod("detectObjects",ImageData.class,Collection.class).invoke(engine,data,List.of(parent));
            int failed=((Number)result.getClass().getMethod("nTilesFailed").invoke(result)).intValue();
            int processed=((Number)result.getClass().getMethod("nTilesProcessed").invoke(result)).intValue();
            IfInstanSeg.validateOutcome(processed,failed,(Boolean)result.getClass().getMethod("wasInterrupted").invoke(result));
            if(reports.size()!=1||((Number)reports.getFirst().get("tiles")).intValue()!=processed)throw new IllegalStateException("Unexpected premerge observation coverage");
            if(labels&&labelReports.size()!=processed)throw new IllegalStateException("Label observation coverage mismatch");
            var report=Map.of("returnedLabelMaps",labelReports,"beforeGlobalMerge",reports,"afterGlobalMerge",IfInstanSeg.auditOutput(data.getHierarchy().getDetectionObjects(),()->false),
                    "limitations","Premerge objects already underwent per-tile label conversion, pruning and ROI masking; not raw model tensors. Overlapping tile objects can duplicate cells. Counts cannot be subtracted as matched losses. Test-only private-field inspection; source hierarchy isolated; no screenshots.");
            Files.writeString(output,GsonTools.getInstance(true).toJson(report),StandardOpenOption.CREATE_NEW);
            System.out.println("IF_PREMERGE_AUDIT_OK "+GsonTools.getInstance().toJson(report));
        }
    }
    private static void set(Object target,String name,Class<?> type,Object value)throws Exception {target.getClass().getMethod(name,type).invoke(target,value);}

    @SuppressWarnings({"rawtypes","unchecked"})
    private static void observeLabels(Runnable task,List<Map<String,Object>> reports){
        try{
            if(!task.getClass().getName().equals("qupath.lib.experimental.pixels.PixelProcessor$ProcessorTask"))throw new IllegalStateException("Unexpected task class");
            var field=task.getClass().getDeclaredField("processor");field.setAccessible(true);
            var original=(qupath.lib.experimental.pixels.Processor)field.get(task);
            qupath.lib.experimental.pixels.Processor observer=parameters->{
                Object output=original.process(parameters);
                if(!(output instanceof org.bytedeco.opencv.opencv_core.Mat[] mats)||mats.length==0)throw new java.io.IOException("No model label output");
                var audit=new LinkedHashMap<>(auditLabels(mats[0]));var request=parameters.getRegionRequest();
                audit.put("region",List.of(request.getX(),request.getY(),request.getWidth(),request.getHeight()));
                audit.put("downsample",request.getDownsample());reports.add(audit);
                return output; // Same array and Mats; no writes, conversion or replacement.
            };
            field.set(task,observer);
        }catch(ReflectiveOperationException e){throw new IllegalStateException("Label observer unavailable",e);}
    }

    static Map<String,Object> auditLabels(org.bytedeco.opencv.opencv_core.Mat labels){
        if(labels.channels()!=2||(long)labels.rows()*labels.cols()>1_048_576)throw new IllegalArgumentException("Expected bounded two-channel label map");
        var nuclei=new HashSet<Long>();var cells=new HashSet<Long>();var different=new HashSet<Long>();
        try(var index=labels.createIndexer()){
            for(int y=0;y<labels.rows();y++)for(int x=0;x<labels.cols();x++){
                double n=index.getDouble(y,x,0),c=index.getDouble(y,x,1);
                if(!Double.isFinite(n)||!Double.isFinite(c)||n<0||c<0||n!=Math.rint(n)||c!=Math.rint(c))throw new IllegalArgumentException("Noninteger/invalid model labels");
                if(n>0)nuclei.add((long)n);if(c>0)cells.add((long)c);
                if(n!=c){if(n>0)different.add((long)n);if(c>0)different.add((long)c);}
            }
        }
        var absent=new HashSet<>(cells);absent.removeAll(nuclei);
        var nucleusOnly=new HashSet<>(nuclei);nucleusOnly.removeAll(cells);
        var same=new HashSet<>(cells);same.retainAll(nuclei);same.removeAll(different);
        return Map.of("width",labels.cols(),"height",labels.rows(),"nuclearLabels",nuclei.size(),"cellLabels",cells.size(),
                "cellLabelsWithoutSameIdNucleus",absent.size(),"nuclearLabelsWithoutSameIdCell",nucleusOnly.size(),"identicalLabelPixelSets",same.size(),
                "stage","returned model label Mat before contour conversion/pruning/global merge; channels 0=nuclei,1=cells per model RDF");
    }
}
