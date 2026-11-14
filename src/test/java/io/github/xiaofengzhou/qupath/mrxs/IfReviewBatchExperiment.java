package io.github.xiaofengzhou.qupath.mrxs;

import java.util.ArrayList;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;

/** Test-only layout experiment. Never called by extension production code. */
final class IfReviewBatchExperiment {
    static int regroup(PathObject group,int maxObjects) {
        if(maxObjects<1)throw new IllegalArgumentException("Positive batch size required");
        var originals=new ArrayList<>(group.getChildObjects());
        group.clearChildObjects();int batches=0;
        for(int offset=0;offset<originals.size();offset+=maxObjects){
            var folder=new PathAnnotationObject();
            folder.setName("EXPERIMENTAL review batch "+(++batches));
            folder.addChildObjects(originals.subList(offset,Math.min(originals.size(),offset+maxObjects)));
            group.addChildObject(folder);
        }
        return batches;
    }
}
