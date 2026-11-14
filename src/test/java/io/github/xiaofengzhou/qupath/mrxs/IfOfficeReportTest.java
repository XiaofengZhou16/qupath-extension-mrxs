package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import static org.junit.jupiter.api.Assertions.*;

class IfOfficeReportTest {
    @TempDir Path temp;
    private Path source() throws Exception {
        try(var s=new IfAnalysisTest.TruthServer(4)) {
            String name="=标记<&_x0041_\nA";
            var channels=new ArrayList<>(s.getMetadata().getChannels());
            channels.set(0,qupath.lib.images.servers.ImageChannel.getInstance(name,0xff0000));
            s.setMetadata(new qupath.lib.images.servers.ImageServerMetadata.Builder(s.getMetadata()).channels(channels).build());
            Path p=temp.resolve("source");
            IfAnalysisExport.analyzeAndWrite(p,s,ROIs.createRectangleROI(0,0,32,8,ImagePlane.getDefaultPlane()),
                    new IfAnalysisProfile(1,0,List.of(new IfAnalysisProfile.Marker(0,name,30,30,30))),
                    List.of(),Map.of("method","synthetic"),false,()->false,v->{});
            return p;
        }
    }
    @Test void packagesParseAndKeepRowsSeparateWithoutFormulas() throws Exception {
        Path source=source(),out=temp.resolve("report");byte[] original=Files.readAllBytes(source.resolve("manifest.json"));
        IfOfficeReport.write(List.of(source),out,()->false);
        assertFalse(Files.exists(out.resolve("INCOMPLETE")));assertTrue(Files.exists(out.resolve("manifest.json")));
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        for(String file:List.of("results.xlsx","summary.docx"))try(var zip=new ZipFile(out.resolve(file).toFile())) {
            var entries=zip.entries();while(entries.hasMoreElements()) {
                var entry=entries.nextElement();try(var in=zip.getInputStream(entry)){assertNotNull(factory.newDocumentBuilder().parse(in));}
            }
            if(file.endsWith("xlsx"))try(var in=zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml"))) {
                var doc=factory.newDocumentBuilder().parse(in);
                assertEquals(21,doc.getElementsByTagNameNS("*","row").getLength());
                assertEquals(0,doc.getElementsByTagNameNS("*","f").getLength());
                assertTrue(doc.getDocumentElement().getTextContent().contains("_x005F_x0041_"));
                assertTrue(doc.getDocumentElement().getTextContent().contains("标记<&"));
                var rows=doc.getElementsByTagNameNS("*","row");
                var cells=((org.w3c.dom.Element)rows.item(1)).getElementsByTagNameNS("*","c");
                assertEquals("n",((org.w3c.dom.Element)cells.item(4)).getAttribute("t"));
                assertEquals("n",((org.w3c.dom.Element)cells.item(7)).getAttribute("t"));
                assertEquals("inlineStr",((org.w3c.dom.Element)cells.item(3)).getAttribute("t"));
                assertEquals("A1:M21",((org.w3c.dom.Element)doc.getElementsByTagNameNS("*","autoFilter").item(0)).getAttribute("ref"));
                assertEquals("frozen",((org.w3c.dom.Element)doc.getElementsByTagNameNS("*","pane").item(0)).getAttribute("state"));
            }
        }
        assertArrayEquals(original,Files.readAllBytes(source.resolve("manifest.json")));
        assertThrows(FileAlreadyExistsException.class,()->IfOfficeReport.write(List.of(source),out,()->false));
    }
    @Test void cancellationCreatesNoOutput() throws Exception {
        Path out=temp.resolve("cancelled");assertThrows(java.util.concurrent.CancellationException.class,
                ()->IfOfficeReport.write(List.of(temp.resolve("missing")),out,()->true));
        assertFalse(Files.exists(out));
    }
    @Test void wordOnlyWritesOnlyRequestedArtifactAndRejectsNoFormat()throws Exception {
        assertThrows(IllegalArgumentException.class,()->new IfOfficeReport.Formats(false,false));
        Path source=source(),out=temp.resolve("word-only");
        IfOfficeReport.write(List.of(source),out,new IfOfficeReport.Formats(false,true),()->false);
        assertTrue(Files.exists(out.resolve("summary.docx")));assertFalse(Files.exists(out.resolve("results.xlsx")));
        var m=com.google.gson.JsonParser.parseString(Files.readString(out.resolve("manifest.json"))).getAsJsonObject();
        assertEquals(Set.of("summary.docx"),m.getAsJsonObject("files").keySet());
        assertFalse(m.getAsJsonObject("requestedFormats").get("excel").getAsBoolean());
        assertTrue(m.getAsJsonObject("requestedFormats").get("word").getAsBoolean());
        assertEquals(m.get("rows").getAsInt(),m.get("wordQuantitativeRows").getAsInt());
    }
    @Test void wordNumbersStayReadableWithoutLosingSmallNonzeroValues() {
        assertEquals("", IfOfficeReport.wordNumber(""));
        assertEquals("0", IfOfficeReport.wordNumber("0.0000"));
        assertEquals("0.3333", IfOfficeReport.wordNumber("0.333333333333"));
        assertEquals("2.98e-8", IfOfficeReport.wordNumber("0.0000000298023223876953125"));
        assertEquals("1e-400", IfOfficeReport.wordNumber("1e-400"));
        assertEquals("1.235e8", IfOfficeReport.wordNumber("123456789"));
        assertEquals("-0.1235", IfOfficeReport.wordNumber("-0.123456"));
    }
    @Test void wordTablesRetainEveryValidatedRowAndUndefinedValues()throws Exception {
        Path source=source(),out=temp.resolve("word-tables");
        var input=IfBatchReportInput.load(List.of(source),()->false).runs().getFirst();
        var expected=new LinkedHashMap<List<String>,List<List<String>>>();
        for(var r:input.rows())expected.computeIfAbsent(List.of(r.get(2),r.get(3),r.get(5)),k->new ArrayList<>()).add(r);
        IfOfficeReport.write(List.of(source),out,()->false);
        try(var zip=new ZipFile(out.resolve("summary.docx").toFile());var in=zip.getInputStream(zip.getEntry("word/document.xml"))){
            var f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);var doc=f.newDocumentBuilder().parse(in);
            var tables=doc.getElementsByTagNameNS("*","tbl");assertEquals(expected.size(),tables.getLength());int t=0,seen=0,blankRatios=0;
            for(var group:expected.entrySet()){
                var table=(org.w3c.dom.Element)tables.item(t++);var rows=table.getElementsByTagNameNS("*","tr");
                assertEquals(2,table.getElementsByTagNameNS("*","tblHeader").getLength());assertEquals(group.getValue().size()+2,rows.getLength());
                for(int r=0;r<rows.getLength();r++) {
                    var row=(org.w3c.dom.Element)rows.item(r);
                    assertEquals(1,row.getElementsByTagNameNS("*","cantSplit").getLength(),"Every quantitative row requests intact pagination");
                }
                String context=rows.item(0).getTextContent();assertTrue(context.contains("Mode: "+group.getKey().get(0)));assertTrue(context.contains("Metric: "+group.getKey().get(1)));assertTrue(context.contains("Denominator: "+group.getKey().get(2).replaceAll("\\R","")));
                for(int r=0;r<group.getValue().size();r++){
                    var original=group.getValue().get(r);var cells=((org.w3c.dom.Element)rows.item(r+2)).getElementsByTagNameNS("*","tc");assertEquals(7,cells.getLength());
                    int[] columns={0,1,4,6,7,8,9};for(int c=0;c<columns.length;c++)assertEquals((c>=4?IfOfficeReport.wordNumber(original.get(columns[c])):original.get(columns[c])).replaceAll("\\R",""),cells.item(c).getTextContent());
                    if(original.get(7).isEmpty()){blankRatios++;assertEquals("",cells.item(4).getTextContent());}seen++;
                }
            }
            assertEquals(input.rows().size(),seen);assertTrue(blankRatios>0);assertTrue(doc.getElementsByTagNameNS("*","br").getLength()>0);
            assertEquals(0,doc.getElementsByTagNameNS("*","fldSimple").getLength());
        }
    }
    @Test void cancellationDuringWritingRetainsIncompleteMarker() throws Exception {
        Path source=source(),out=temp.resolve("partial");
        assertThrows(java.util.concurrent.CancellationException.class,
                ()->IfOfficeReport.write(List.of(source),out,()->Files.exists(out.resolve("results.xlsx"))));
        assertTrue(Files.exists(out.resolve("INCOMPLETE")));
        assertFalse(Files.exists(out.resolve("manifest.json")));
        assertFalse(Files.exists(out.resolve("summary.docx")));
    }
    @Test void numericGuardProtectsPrecisionAndRejectsExpressions() {
        for(String value:List.of("0","12","16223240800","0.333333333333","1e-12","999999999999999","-0.5"))
            assertTrue(IfOfficeReport.safeExcelNumber(value),value);
        for(String value:List.of("","=1+1","NaN","Infinity","1234567890123456","1000000000000000","0.1234567890123456","1e-308","1e309"," 12","@SUM(A1)"))
            assertFalse(IfOfficeReport.safeExcelNumber(value),value);
    }
    @Test void oversizedCountsAreWrittenAsTextWithoutRounding() throws Exception {
        Path source=source(),csv=source.resolve("combinations.csv"),out=temp.resolve("large-counts");
        String original=Files.readString(csv);
        String changed=original.replaceFirst(",inclusive,PA,[0-9]+,\"all_sampled_roi_pixels\",[0-9]+,[^,]*,",
                ",inclusive,PA,9007199254740993,\"all_sampled_roi_pixels\",9007199254740993,1,");
        assertNotEquals(original,changed);Files.writeString(csv,changed);
        IfOfficeReport.write(List.of(source),out,()->false);
        try(var zip=new ZipFile(out.resolve("results.xlsx").toFile());var in=zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml"))) {
            var f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            var doc=f.newDocumentBuilder().parse(in);var rows=doc.getElementsByTagNameNS("*","row");
            var cells=((org.w3c.dom.Element)rows.item(1)).getElementsByTagNameNS("*","c");
            for(int i:List.of(7,9)) {var cell=(org.w3c.dom.Element)cells.item(i);assertEquals("inlineStr",cell.getAttribute("t"));assertEquals("9007199254740993",cell.getTextContent());}
        }
    }
}
