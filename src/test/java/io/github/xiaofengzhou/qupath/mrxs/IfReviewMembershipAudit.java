package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.*;
import qupath.lib.io.PathIO;

/** Read-only audit of a saved native pointer-edit session. */
public final class IfReviewMembershipAudit {
    public static void main(String[] args) throws Exception {
        var hierarchy=PathIO.readHierarchy(Path.of(args[0]));
        var groups=hierarchy.getAnnotationObjects().stream().filter(o->o.getMetadata().containsKey(IfCellReview.GROUP)).toList();
        var result=new ArrayList<Map<String,Object>>();
        for(var group:groups) {
            var snapshot=IfCellReview.capture(group);
            var cells=snapshot.cells().stream().map(c->{
                var value=new LinkedHashMap<String,Object>();value.put("pairId",c.id());
                value.put("cellAreaPixels",c.cell()==null?null:c.cell().getArea());
                value.put("cellBounds",c.cell()==null?null:List.of(c.cell().getBoundsX(),c.cell().getBoundsY(),c.cell().getBoundsWidth(),c.cell().getBoundsHeight()));
                value.put("nucleusAreaPixels",c.nucleus()==null?null:c.nucleus().getArea());return value;
            }).toList();
            var entry=new LinkedHashMap<String,Object>();entry.put("groupId",group.getID().toString());
            entry.put("descendants",group.nDescendants());entry.put("cells",cells);entry.put("provenance",snapshot.provenance());result.add(entry);
        }
        Files.writeString(Path.of(args[1]),qupath.lib.io.GsonTools.getInstance(true).toJson(result),StandardOpenOption.CREATE_NEW);
        System.out.println("READ_ONLY_REVIEW_AUDIT_OK groups="+groups.size());
    }
}
