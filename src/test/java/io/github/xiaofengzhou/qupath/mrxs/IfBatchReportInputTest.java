package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import static org.junit.jupiter.api.Assertions.*;

class IfBatchReportInputTest {
    @TempDir Path temp;
    private Path make(String folder,String marker,int threshold) throws Exception {
        try(var server=new IfAnalysisTest.TruthServer(4)) {
            var channels=new ArrayList<>(server.getMetadata().getChannels());
            channels.set(0,qupath.lib.images.servers.ImageChannel.getInstance(marker,0xff0000));
            server.setMetadata(new qupath.lib.images.servers.ImageServerMetadata.Builder(server.getMetadata()).channels(channels).build());
            Path path=temp.resolve(folder);
            var profile=new IfAnalysisProfile(1,0,List.of(new IfAnalysisProfile.Marker(0,marker,threshold,threshold,threshold)));
            IfAnalysisExport.analyzeAndWrite(path,server,ROIs.createRectangleROI(0,0,32,8,ImagePlane.getDefaultPlane()),
                    profile,List.of(),Map.of("method","synthetic"),false,()->false,v->{});
            return path;
        }
    }
    @Test void preservesQuotedLabelsBlanksAndSeparateProfiles() throws Exception {
        Path first=make("first","=标记,\n\"A\"",30),second=make("second","B",40);
        byte[] original=Files.readAllBytes(first.resolve("combinations.csv"));
        var batch=IfBatchReportInput.load(List.of(first,second),()->false);
        assertEquals(2,batch.runs().size());assertEquals(2,batch.profileGroups());assertEquals(40,batch.tableRows());
        assertEquals("'=标记,\n\"A\"",batch.runs().getFirst().rows().getFirst().getFirst());
        assertTrue(batch.runs().getFirst().rows().stream().anyMatch(r->r.get(3).equals("PN") && r.get(7).isEmpty()));
        assertEquals(64,batch.runs().getFirst().combinationsSha256().length());
        assertArrayEquals(original,Files.readAllBytes(first.resolve("combinations.csv")));
    }
    @Test void rejectsDuplicatesIncompleteRowsAndCountTampering() throws Exception {
        Path path=make("one","A",30);
        assertThrows(java.io.IOException.class,()->IfBatchReportInput.load(List.of(path,path.resolve(".")),()->false));
        Path csv=path.resolve("combinations.csv");String original=Files.readString(csv);
        Files.writeString(csv,original.substring(0,original.lastIndexOf('\n',original.length()-2)+1));
        assertThrows(java.io.IOException.class,()->IfBatchReportInput.load(List.of(path),()->false));
        String tampered=original.replaceFirst(",inclusive,PA,[0-9]+,",",inclusive,PA,999999,");
        assertNotEquals(original,tampered);Files.writeString(csv,tampered);
        assertThrows(java.io.IOException.class,()->IfBatchReportInput.load(List.of(path),()->false));
        Files.writeString(csv,original);Files.writeString(path.resolve("INCOMPLETE"),"cancelled");
        assertThrows(java.io.IOException.class,()->IfBatchReportInput.load(List.of(path),()->false));
    }
    @Test void strictCsvRejectsBrokenQuotesAndPreservesTrailingEmpty() throws Exception {
        assertEquals(List.of(List.of("a","b,b","c\nd","")),IfReportCsv.parse("a,\"b,b\",\"c\nd\",\r\n",2,4));
        assertThrows(java.io.IOException.class,()->IfReportCsv.parse("\"open",10,10));
        assertThrows(java.io.IOException.class,()->IfReportCsv.parse("\"a\"x,b",10,10));
        assertThrows(java.io.IOException.class,()->IfReportCsv.parse("a,b,c",10,2));
        assertThrows(java.io.IOException.class,()->IfReportCsv.parse("a\nb\n",1,2));
    }
    @Test void cancellationDoesNotReadOrWriteOutput() {
        assertThrows(java.util.concurrent.CancellationException.class,
                ()->IfBatchReportInput.load(List.of(temp.resolve("absent")),()->true));
    }
}
