package io.github.xiaofengzhou.qupath.mrxs;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import static org.junit.jupiter.api.Assertions.*;
class IfReportFigureTest {
    @TempDir Path temp;
    @Test void absentOrTinyPreviewDoesNotReadPixelsOrInventImages() throws Exception {
        try(var server=new IfAnalysisTest.TruthServer(4)) {
            var roi=ROIs.createRectangleROI(0,0,32,8,ImagePlane.getDefaultPlane());
            assertEquals("not_requested",IfReportFigure.write(temp,server,roi,null,()->false).get("status"));
            var channels=java.util.stream.IntStream.range(0,server.nChannels()).mapToObj(c->new IfDisplayProfile.Channel(c,server.getChannel(c).getName(),0xffffff,0,255,true)).toList();
            assertEquals("unavailable",IfReportFigure.write(temp,server,roi,new IfDisplayProfile(1,1,false,false,channels),()->false).get("status"));
            assertEquals(0,server.reads);assertFalse(Files.exists(temp.resolve("report-figure")));
        }
    }
}
