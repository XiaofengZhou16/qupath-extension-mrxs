package io.github.xiaofengzhou.qupath.mrxs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import static org.junit.jupiter.api.Assertions.*;

class IfBatchOutputTest {
    @TempDir Path root;
    final IfBatchQueue.Item item=new IfBatchQueue.Item("slide-roi","frozen-input");
    Path makeRun(String name)throws Exception {
        var path=root.resolve(name);
        try(var server=new IfAnalysisTest.TruthServer(4)) {
            var profile=new IfAnalysisProfile(1,0,List.of(new IfAnalysisProfile.Marker(0,"A",30,30,30)));
            IfAnalysisExport.analyzeAndWrite(path,server,ROIs.createRectangleROI(0,0,32,8,ImagePlane.getDefaultPlane()),profile,
                    List.of(),Map.of("method","test"),false,()->false,p->{});
        }
        return path;
    }
    @Test void realExportSealsAndRejectsChangedCsvOrTaskIdentity()throws Exception {
        var run=makeRun("run");IfBatchOutput.seal(run,item,()->false);IfBatchOutput.verify(run,item,()->false);
        assertThrows(IOException.class,()->IfBatchOutput.verify(run,new IfBatchQueue.Item("other-roi","frozen-input"),()->false));
        Files.writeString(run.resolve("cells.csv"),"tampered");
        assertThrows(IOException.class,()->IfBatchOutput.verify(run,item,()->false));
    }
    @Test void incompleteAddedAndLinkedFilesAreRejected()throws Exception {
        var run=makeRun("run");Files.writeString(run.resolve("INCOMPLETE"),"pending");
        assertThrows(IOException.class,()->IfBatchOutput.seal(run,item,()->false));
        Files.delete(run.resolve("INCOMPLETE"));IfBatchOutput.seal(run,item,()->false);
        Files.writeString(run.resolve("extra.csv"),"extra");
        assertThrows(IOException.class,()->IfBatchOutput.verify(run,item,()->false));
        Files.delete(run.resolve("extra.csv"));Files.createSymbolicLink(run.resolve("linked"),run.resolve("cells.csv"));
        assertThrows(IOException.class,()->IfBatchOutput.verify(run,item,()->false));
    }
    @Test void cancellationDoesNotWriteCompletionReceipt()throws Exception {
        var run=makeRun("run");
        assertThrows(java.util.concurrent.CancellationException.class,()->IfBatchOutput.seal(run,item,()->true));
        assertFalse(Files.exists(run.resolve("batch-output.json")));
    }
}
