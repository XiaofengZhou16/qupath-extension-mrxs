package io.github.xiaofengzhou.qupath.mrxs;

import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.roi.interfaces.ROI;
import java.util.*;

/** Detached editable annotation copies; never mutate input cells or a live hierarchy. */
final class IfCellReview {
    static final String GROUP="IF editable review group";
    static final String PAIR="IF review pair ID";
    static final String COMPARTMENT="IF review compartment";
    private IfCellReview() {}

    /** Resolve navigation only; does not expand measurement scope or mutate selection. */
    static PathObject owningGroup(PathObject selected) {
        if(selected==null)throw new IllegalArgumentException("Select a review group, batch or compartment first");
        String owner=selected.getMetadata().get("IF review owner");
        for(var object=selected;object!=null;object=object.getParent()){
            String id=object.getMetadata().get(GROUP);
            if(id!=null){
                if(owner!=null&&!owner.equals(id))throw new IllegalArgumentException("Compartment owner does not match its enclosing review group");
                return object;
            }
        }
        if(owner!=null) {
            var matches=tree(selected).stream().filter(o->owner.equals(o.getMetadata().get(GROUP))).toList();
            if(matches.size()==1)return matches.getFirst();
            throw new IllegalArgumentException("Review owner is missing or ambiguous in this image hierarchy");
        }
        throw new IllegalArgumentException("Selected object is not inside an IF editable review group");
    }

    /** Native ROI editing can reparent annotations; identity is retained in owner metadata. */
    private static List<PathObject> tree(PathObject object) {
        while(object.getParent()!=null)object=object.getParent();
        var result=new ArrayList<PathObject>();
        var pending=new ArrayDeque<PathObject>();pending.add(object);
        while(!pending.isEmpty()) {
            var next=pending.removeFirst();result.add(next);pending.addAll(next.getChildObjects());
        }
        return result;
    }

    private static List<PathObject> ownedCompartments(PathObject group) {
        String owner=group.getMetadata().get(GROUP);
        var objects=tree(group);
        if(objects.stream().filter(o->owner.equals(o.getMetadata().get(GROUP))).count()!=1)
            throw new IllegalArgumentException("Review group identity is ambiguous in this image hierarchy");
        var owned=objects.stream().filter(o->owner.equals(o.getMetadata().get("IF review owner"))).toList();
        for(var object:owned) {
            // A compartment inside a different review group needs explicit correction.
            for(var parent=object.getParent();parent!=null;parent=parent.getParent()) {
                if(parent.getMetadata().containsKey(GROUP)) {
                    if(parent!=group)throw new IllegalArgumentException("Review compartment is enclosed by a different review group");
                    break;
                }
            }
        }
        return owned;
    }

    static PathObject create(ROI region,List<IfAnalysis.Cell> cells) {
        return create(region,cells,()->false,p->{});
    }
    static PathObject create(ROI region,List<IfAnalysis.Cell> cells,java.util.function.BooleanSupplier cancelled,
                             java.util.function.DoubleConsumer progress) {
        if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
        var group=PathObjects.createAnnotationObject(region);
        group.setName("IF editable review — not validated ground truth");
        String groupId=UUID.randomUUID().toString();
        group.getMetadata().put(GROUP,groupId);
        int completed=0;
        for(var cell:cells) {
            if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
            String pairId=UUID.randomUUID().toString();
            for(int i=0;i<2;i++) {
                var roi=i==0?cell.cell():cell.nucleus();
                if(roi==null || roi.isEmpty())continue;
                var copy=PathObjects.createAnnotationObject(roi);
                copy.setName((i==0?"Cell ":"Nucleus ")+cell.id());
                copy.getMetadata().put(PAIR,pairId);
                copy.getMetadata().put(COMPARTMENT,i==0?"cell":"nucleus");
                copy.getMetadata().put("IF source cell ID",cell.id());
                copy.getMetadata().put("IF review owner",groupId);
                group.addChildObject(copy);
            }
            if(++completed%1024==0)progress.accept((double)completed/cells.size());
        }
        if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
        progress.accept(1);
        return group;
    }

    static List<IfAnalysis.Cell> snapshot(PathObject group) {
        return capture(group).cells();
    }

    record ReviewSnapshot(List<IfAnalysis.Cell> cells,Map<String,Object> provenance) {}

    /** Capture geometry references and copied provenance together on the UI thread, before worker launch. */
    static ReviewSnapshot capture(PathObject group) {
        String owner=group.getMetadata().get(GROUP);
        if(owner==null || owner.isBlank())throw new IllegalArgumentException("Select an IF editable review group");
        var pairs=new LinkedHashMap<String,ROI[]>();
        var compartments=new ArrayList<Map<String,Object>>();
        var owned=ownedCompartments(group);
        for(var object:owned) {
            String pair=object.getMetadata().get(PAIR),part=object.getMetadata().get(COMPARTMENT);
            if(pair==null || pair.isBlank() || part==null || !List.of("cell","nucleus").contains(part))
                throw new IllegalArgumentException("Review object has missing pair/compartment metadata");
            if(!object.isAnnotation() || !object.hasROI() || !object.getROI().isArea() || object.getROI().isEmpty())
                throw new IllegalArgumentException("Review compartments must be nonempty area annotations");
            var rois=pairs.computeIfAbsent(pair,key->new ROI[2]);int index=part.equals("cell")?0:1;
            if(rois[index]!=null)throw new IllegalArgumentException("Duplicate review "+part+" for pair "+pair);
            rois[index]=object.getROI();
            var metadata=new LinkedHashMap<String,String>();
            for(var key:List.of("IF source cell ID","IF manual source annotation ID",
                    "IF manual compartment source ID","IF review action","IF historical exclusion stage","IF historical exclusion reason")) {
                var value=object.getMetadata().get(key);if(value!=null)metadata.put(key,value);
            }
            compartments.add(Map.of("annotationId",object.getID().toString(),"pairId",pair,
                    "compartment",part,"sourceMetadata",Map.copyOf(metadata)));
        }
        var cells=pairs.entrySet().stream().map(e->new IfAnalysis.Cell(e.getKey(),e.getValue()[0],e.getValue()[1])).toList();
        var provenance=new LinkedHashMap<String,Object>(Map.of(
                "method","editable IF review annotations; manual validation not inferred",
                "reviewGroupId",owner,"reviewGroupAnnotationId",group.getID().toString(),
                "reviewSnapshotSchemaVersion",2,
                "reviewMembership","owner metadata across the current image hierarchy; conflicting enclosing groups rejected",
                "reviewSnapshotMeaning","State at analysis launch; not a complete edit history or human validation record",
                "reviewGeometryFiles",List.of("cells.geojson","excluded-cells.geojson"),
                "reviewCompartments",List.copyOf(compartments)));
        var history=group.getMetadata().get(IfHistoricalReview.LINEAGE);
        if(history!=null)provenance.put("historicalReview",com.google.gson.JsonParser.parseString(history));
        return new ReviewSnapshot(cells,Collections.unmodifiableMap(provenance));
    }

    static PathObject prepareMissingNucleus(PathObject group,PathObject reviewedCell,PathObject handDrawnNucleus) {
        String owner=group.getMetadata().get(GROUP),pair=reviewedCell.getMetadata().get(PAIR);
        if(owner==null || !owner.equals(reviewedCell.getMetadata().get("IF review owner"))
                || !"cell".equals(reviewedCell.getMetadata().get(COMPARTMENT)))
            throw new IllegalArgumentException("Select a paired cell from this review group");
        var current=snapshot(group).stream().filter(c->c.id().equals(pair)).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Paired cell is not inside the selected review group"));
        if(current.nucleus()!=null)throw new IllegalArgumentException("This pair already has a nucleus; edit it instead of adding another");
        if(!handDrawnNucleus.isAnnotation() || !handDrawnNucleus.hasROI() || !handDrawnNucleus.getROI().isArea()
                || handDrawnNucleus.getROI().isEmpty() || handDrawnNucleus.getMetadata().containsKey(PAIR))
            throw new IllegalArgumentException("Select a new hand-drawn nuclear area annotation");
        var nucleus=handDrawnNucleus.getROI();var cell=current.cell();
        if(cell==null || !cell.getImagePlane().equals(nucleus.getImagePlane()) || !cell.getImagePlane().equals(group.getROI().getImagePlane())
                || !cell.getGeometry().covers(nucleus.getGeometry()) || cell.getArea()<=nucleus.getArea()
                || !group.getROI().getGeometry().covers(cell.getGeometry()))
            throw new IllegalArgumentException("Nucleus must fit inside the review cell, leaving cytoplasm, on the same plane within the review ROI");
        var copy=PathObjects.createAnnotationObject(nucleus);copy.setName("Manual nucleus repair "+pair);
        copy.getMetadata().put(PAIR,pair);copy.getMetadata().put(COMPARTMENT,"nucleus");
        copy.getMetadata().put("IF review owner",owner);
        copy.getMetadata().put("IF source cell ID",reviewedCell.getMetadata().getOrDefault("IF source cell ID",pair));
        copy.getMetadata().put("IF manual compartment source ID",handDrawnNucleus.getID().toString());
        copy.getMetadata().put("IF review action","added previously missing nucleus; validation not inferred");
        return copy;
    }

    /** Prepare copies only; caller confirms before attaching them to a live review group. */
    static List<PathObject> prepareManualPair(PathObject group,PathObject first,PathObject second) {
        String owner=group.getMetadata().get(GROUP);
        if(owner==null || !group.hasROI() || !group.getROI().isArea())throw new IllegalArgumentException("Select one editable review group");
        for(var object:List.of(first,second)) {
            if(object==group || !object.isAnnotation() || !object.hasROI() || !object.getROI().isArea() || object.getROI().isEmpty()
                    || object.getMetadata().containsKey(PAIR))throw new IllegalArgumentException("Select two new nonempty area annotations, not existing paired compartments");
            if(!object.getROI().getImagePlane().equals(group.getROI().getImagePlane()))throw new IllegalArgumentException("Review group and compartments must use the same image plane");
        }
        var a=first.getROI().getGeometry();var b=second.getROI().getGeometry();
        PathObject cell,nucleus;
        if(a.covers(b)&&a.getArea()>b.getArea()){cell=first;nucleus=second;}
        else if(b.covers(a)&&b.getArea()>a.getArea()){cell=second;nucleus=first;}
        else throw new IllegalArgumentException("One annotation must contain the other and leave nonzero cytoplasm");
        if(!group.getROI().getGeometry().covers(cell.getROI().getGeometry()))throw new IllegalArgumentException("Cell must be fully inside review ROI");
        String sourceId=cell.getID().toString();
        for(var object:ownedCompartments(group)) {
            if(sourceId.equals(object.getMetadata().get("IF manual source annotation ID")))throw new IllegalArgumentException("This annotation is already paired in this review group");
        }
        String pair=UUID.randomUUID().toString();var copies=new ArrayList<PathObject>();
        for(var object:List.of(cell,nucleus)) {
            var copy=PathObjects.createAnnotationObject(object.getROI());
            String part=object==cell?"cell":"nucleus";
            copy.setName("Manual "+part+" "+pair);
            copy.getMetadata().put(PAIR,pair);copy.getMetadata().put(COMPARTMENT,part);
            copy.getMetadata().put("IF review owner",owner);
            copy.getMetadata().put("IF source cell ID",pair);
            copy.getMetadata().put("IF manual source annotation ID",sourceId);
            copy.getMetadata().put("IF manual compartment source ID",object.getID().toString());
            copies.add(copy);
        }
        return List.copyOf(copies);
    }
}
