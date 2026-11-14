package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.*;
import qupath.lib.io.GsonTools;

/** Read-only saved-geometry audit; no inference, pixels, screenshots or eligibility changes. */
public class IfExclusionGeometryAudit {
    public static void main(String[] args)throws Exception {
        Path history=Path.of(args[1]),output=Path.of(args[2]);
        try(var server=new MrxsImageServer(Path.of(args[0]).toUri())){
            var entry=IfHistorySource.validate(history,IfHistorySource.capture(server),()->false);
            var items=IfHistoricalExclusions.load(entry,()->false);
            var counts=new TreeMap<String,Map<String,Long>>();
            var areas=new TreeMap<String,List<Double>>();
            var bounds=entry.roi().getGeometry().getEnvelopeInternal();
            for(var item:items){
                var c=counts.computeIfAbsent(item.reason(),k->new TreeMap<>());add(c,"records");
                if(item.cell()==null){add(c,"missingCell");continue;}
                var cell=item.cell().getGeometry();
                areas.computeIfAbsent(item.reason(),k->new ArrayList<>()).add(cell.getArea());
                var b=cell.getEnvelopeInternal();
                if(Math.abs(b.getMinX()-bounds.getMinX())<0.02||Math.abs(b.getMaxX()-bounds.getMaxX())<0.02
                    ||Math.abs(b.getMinY()-bounds.getMinY())<0.02||Math.abs(b.getMaxY()-bounds.getMaxY())<0.02)add(c,"touchesRoiBoundingEdgeWithin002Pixels");
                if(item.nucleus()==null){add(c,"missingNucleus");continue;}
                var nucleus=item.nucleus().getGeometry();
                if(cell.equalsTopo(nucleus))add(c,"cellEqualsNucleusTopologically");
                var cytoplasm=cell.difference(nucleus);
                if(cytoplasm.getArea()<=1e-9)add(c,"zeroGeometricCytoplasm");
                else {
                    add(c,"positiveGeometricCytoplasm");
                    double ds=server.getDownsampleForResolution(entry.profile().resolutionLevel());
                    long x0=(long)Math.floor(b.getMinX()/ds),x1=(long)Math.ceil(b.getMaxX()/ds);
                    long y0=(long)Math.floor(b.getMinY()/ds),y1=(long)Math.ceil(b.getMaxY()/ds);
                    if((x1-x0)*(y1-y0)>1_000_000)throw new IllegalArgumentException("Bounded geometry audit exceeded");
                    var prepared=org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(cytoplasm);
                    long pixels=0;
                    for(long y=y0;y<y1;y++)for(long x=x0;x<x1;x++){
                        var point=cell.getFactory().createPoint(new org.locationtech.jts.geom.Coordinate((x+0.5)*ds,(y+0.5)*ds));
                        if(prepared.contains(point))pixels++;
                    }
                    add(c,pixels==0?"positiveCytoplasmWithoutInteriorSampleCenters":"positiveCytoplasmWithInteriorSampleCenters");
                    c.merge("interiorCytoplasmSampleCenters",pixels,Long::sum);
                }
            }
            var distributions=new TreeMap<String,Object>();
            for(var e:areas.entrySet()){
                var a=e.getValue();Collections.sort(a);
                distributions.put(e.getKey(),Map.of("min",a.getFirst(),"median",a.get(a.size()/2),"max",a.getLast()));
            }
            var report=new LinkedHashMap<String,Object>();report.put("sourceRun",history.toAbsolutePath().toString());
            report.put("rejectedRecords",items.size());report.put("counts",counts);report.put("cellAreaSourcePixelsSquared",distributions);
            report.put("limitations","Saved exported geometry (coordinate precision may differ from in-memory ROI). Boundary contact is descriptive, not causal. No raw model label audit, biological truth, new inference, or source pixel reads requested. Exclusion rules unchanged.");
            Files.writeString(output,GsonTools.getInstance(true).toJson(report),StandardOpenOption.CREATE_NEW);
            System.out.println(GsonTools.getInstance().toJson(report));
        }
    }
    private static void add(Map<String,Long> values,String key){values.merge(key,1L,Long::sum);}
}
