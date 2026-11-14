package io.github.xiaofengzhou.qupath.mrxs;

import java.util.List;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CancellationException;
import qupath.lib.roi.interfaces.ROI;

/** Session-local review cursor; never changes source objects or measurement eligibility. */
final class IfRejectedNavigation {
    record Target(String id, String reason, ROI roi) {}
    private final List<Target> all;
    private final Map<String,List<Target>> byReason;
    private List<Target> filtered;
    private int cursor = -1;

    IfRejectedNavigation(List<IfAnalysis.Cell> cells, List<IfAnalysis.RejectedCell> rejected) {
        this(cells,rejected,()->false);
    }
    IfRejectedNavigation(List<IfAnalysis.Cell> cells, List<IfAnalysis.RejectedCell> rejected,
                         BooleanSupplier cancelled) {
        check(cancelled);
        var reasons=new HashMap<String,String>();
        for(var c:rejected){check(cancelled);reasons.put(c.id(),c.reason());}
        var targets=new ArrayList<Target>();
        var grouped=new HashMap<String,List<Target>>();
        for(var c:cells) {
            check(cancelled);
            if(!reasons.containsKey(c.id()) || c.cell()==null || c.cell().isEmpty()
                    || !Double.isFinite(c.cell().getCentroidX()) || !Double.isFinite(c.cell().getCentroidY()))continue;
            var target=new Target(c.id(),reasons.get(c.id()),c.cell());
            targets.add(target);
            grouped.computeIfAbsent(target.reason(),key->new ArrayList<>()).add(target);
        }
        check(cancelled);
        // These lists are private, built once and never mutated after publication.
        // Avoid an additional full copy of large lists during finalization.
        all=java.util.Collections.unmodifiableList(targets);
        byReason=java.util.Collections.unmodifiableMap(grouped);
        filtered=all;
    }
    private static void check(BooleanSupplier cancelled) {
        if(cancelled.getAsBoolean())throw new CancellationException();
    }
    void filter(String reason) {
        filtered=reason==null?all:byReason.getOrDefault(reason,List.of());
        cursor=-1;
    }
    Target move(int direction) {
        if(direction!=1 && direction!=-1)throw new IllegalArgumentException("Direction must be +1 or -1");
        if(filtered.isEmpty())return null;
        cursor=cursor<0?(direction>0?0:filtered.size()-1):Math.floorMod(cursor+direction,filtered.size());
        return filtered.get(cursor);
    }
    int size(){return filtered.size();}
    int position(){return cursor+1;}
}
