package io.github.xiaofengzhou.qupath.mrxs;

import org.junit.jupiter.api.Test;
import org.bytedeco.opencv.opencv_core.Mat;
import static org.bytedeco.opencv.global.opencv_core.CV_32SC2;
import static org.junit.jupiter.api.Assertions.*;

class IfLabelAuditTest {
    @Test void distinguishesMissingIdsAndIdenticalMasksWithoutEditingLabels(){
        try(var labels=new Mat(2,3,CV_32SC2)){
            long[][] values={{1,1},{0,1},{0,2},{3,3},{4,0},{0,0}};
            try(var index=labels.createIndexer()){
                for(int p=0;p<6;p++)for(int c=0;c<2;c++)index.putDouble(new long[]{p/3,p%3,c},values[p][c]);
            }
            var result=IfPremergeAuditSmoke.auditLabels(labels);
            assertEquals(3,result.get("nuclearLabels"));assertEquals(3,result.get("cellLabels"));
            assertEquals(1,result.get("cellLabelsWithoutSameIdNucleus"));assertEquals(1,result.get("nuclearLabelsWithoutSameIdCell"));
            assertEquals(1,result.get("identicalLabelPixelSets"));
            try(var index=labels.createIndexer()){
                for(int p=0;p<6;p++)for(int c=0;c<2;c++)assertEquals(values[p][c],index.getDouble(p/3,p%3,c));
                index.putDouble(new long[]{0,0,0},-1);
            }
            assertThrows(IllegalArgumentException.class,()->IfPremergeAuditSmoke.auditLabels(labels));
        }
    }
}
