package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.JsonParser;
import qupath.lib.roi.ROIs;
import qupath.lib.regions.ImagePlane;
import static org.junit.jupiter.api.Assertions.*;

class IfNuclearReportTest {
    @TempDir Path temp;
    private Path make(String name)throws Exception {
        Path path=temp.resolve(name);var plane=ImagePlane.getDefaultPlane();
        try(var server=new IfAnalysisTest.TruthServer(4)) {
            var cells=List.of(new IfAnalysis.Cell("positive",null,ROIs.createRectangleROI(4,0,2,2,plane)),
                    new IfAnalysis.Cell("negative",null,ROIs.createRectangleROI(0,0,2,2,plane)),
                    new IfAnalysis.Cell("missing",null,null));
            IfAnalysisExport.analyzeAndWrite(path,server,ROIs.createRectangleROI(0,0,32,8,plane),
                    new IfAnalysisProfile(1,0,List.of(new IfAnalysisProfile.Marker(0,"A",100,100,100))),
                    cells,Map.of("method","synthetic"),false,()->false,p->{});
        }
        return path;
    }
    @Test void mixedLegacyReportsDeclareMissingRatherThanZeroAndPreserveNuclearPopulation()throws Exception {
        Path current=make("current"),legacy=make("legacy");
        var manifest=JsonParser.parseString(Files.readString(legacy.resolve("manifest.json"))).getAsJsonObject();
        manifest.remove("nuclearMeasurements");Files.writeString(legacy.resolve("manifest.json"),manifest.toString());
        var b=IfBatchReportInput.load(List.of(current,legacy),()->false);
        assertEquals(36,b.tableRows());assertEquals(2,b.runs().getFirst().measuredNuclei());
        assertEquals(-1,b.runs().getLast().measuredNuclei());assertEquals("",b.runs().getLast().nuclearCombinationsSha256());
        assertEquals(64,b.runs().getFirst().nuclearCombinationsSha256().length());
        var nuclear=b.runs().getFirst().rows().stream().filter(r->r.get(3).equals("NPN_NUCLEUS_ONLY")).toList();
        assertEquals(4,nuclear.size());
        for(var r:nuclear){assertEquals("1",r.get(4));assertEquals(r.get(5).equals("all_measured_nuclei")?"2":"1",r.get(6));}
        Path out=temp.resolve("report");IfOfficeReport.write(List.of(current,legacy),out,()->false);
        try(var zip=new ZipFile(out.resolve("summary.docx").toFile());var in=zip.getInputStream(zip.getEntry("word/document.xml"))) {
            String xml=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(xml.contains("Independent measured nuclei: 2"));assertTrue(xml.contains("Independent nuclear measurements unavailable"));
            assertTrue(xml.contains("NPN_NUCLEUS_ONLY"));assertTrue(xml.contains("all_measured_nuclei"));
        }
    }
    @Test void missingOrMisdeclaredSupplementIsRejectedBeforeReportCreation()throws Exception {
        Path source=make("source"),csv=source.resolve("nuclear-combinations.csv"),out=temp.resolve("report");
        Files.move(csv,source.resolve("saved.csv"));
        assertThrows(java.io.IOException.class,()->IfOfficeReport.write(List.of(source),out,()->false));assertFalse(Files.exists(out));
        Files.move(source.resolve("saved.csv"),csv);
        var m=JsonParser.parseString(Files.readString(source.resolve("manifest.json"))).getAsJsonObject();
        m.getAsJsonObject("nuclearMeasurements").addProperty("measuredNuclei",3);Files.writeString(source.resolve("manifest.json"),m.toString());
        assertThrows(java.io.IOException.class,()->IfBatchReportInput.load(List.of(source),()->false));
    }
    @Test void selfConsistentRatiosCannotHideContradictoryExactAndInclusiveCounts()throws Exception {
        Path source=make("source"),csv=source.resolve("nuclear-combinations.csv");
        var rows=IfReportCsv.parse(Files.readString(csv),100,10);var altered=new StringBuilder();
        for(var original:rows) {
            var row=new ArrayList<>(original);
            if(row.get(2).equals("exact")){row.set(4,"0");row.set(7,"0");row.set(8,"0");}
            altered.append(String.join(",",row)).append('\n');
        }
        Files.writeString(csv,altered.toString());
        var error=assertThrows(java.io.IOException.class,()->IfBatchReportInput.load(List.of(source),()->false));
        assertTrue(error.getMessage().contains("Inconsistent nuclear counts"));
    }
}
