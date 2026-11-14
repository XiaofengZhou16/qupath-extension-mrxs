package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.*;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import static org.junit.jupiter.api.Assertions.*;

class IfReportFigureInputTest {
    @TempDir Path temp;
    private Path make() throws Exception {return make("run");}
    private Path make(String name) throws Exception {
        Path run=temp.resolve(name);
        try(var s=new IfAnalysisTest.TruthServer(4)) {
            IfAnalysisExport.analyzeAndWrite(run,s,ROIs.createRectangleROI(0,0,32,8,ImagePlane.getDefaultPlane()),
                    new IfAnalysisProfile(1,0,List.of(new IfAnalysisProfile.Marker(0,"A",30,30,30))),List.of(),Map.of(),false,()->false,v->{});
        }
        Path fig=Files.createDirectory(run.resolve("report-figure"));Files.copy(run.resolve("roi.json"),fig.resolve("roi.json"));
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"PNG",fig.resolve("001-mask-1-figure.png").toFile());
        var source=JsonParser.parseString(Files.readString(run.resolve("manifest.json"))).getAsJsonObject();
        var manifest=new JsonObject();manifest.addProperty("schemaVersion",1);manifest.addProperty("status","complete");manifest.add("imageName",source.get("imageName"));manifest.addProperty("sourceZ",0);manifest.addProperty("sourceT",0);
        var options=new JsonObject();options.addProperty("level",0);manifest.add("options",options);
        var item=new JsonObject();item.addProperty("figureFile","001-mask-1-figure.png");item.addProperty("figureSha256",hash(fig.resolve("001-mask-1-figure.png")));item.addProperty("figureWidth",2);item.addProperty("figureHeight",2);var names=new JsonArray();names.add("A");item.add("channels",names);var outputs=new JsonArray();outputs.add(item);manifest.add("outputs",outputs);
        Files.writeString(fig.resolve("manifest.json"),manifest.toString());
        var ref=new JsonObject();ref.addProperty("status","exported");ref.addProperty("manifest","report-figure/manifest.json");ref.addProperty("manifestSha256",hash(fig.resolve("manifest.json")));ref.addProperty("roiSha256",hash(fig.resolve("roi.json")));source.getAsJsonObject("source").add("reportFigure",ref);Files.writeString(run.resolve("manifest.json"),source.toString());return run;
    }
    private IfBatchReportInput.Run snapshot(Path run)throws Exception{return IfBatchReportInput.load(List.of(run),()->false).runs().getFirst();}
    private static String hash(Path p)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
    @Test void embedsExactPngBytesAndDeclaresImageRelationship() throws Exception {
        Path run=make(),out=temp.resolve("report");var figure=IfReportFigureInput.load(snapshot(run));assertEquals(2,figure.width());
        IfOfficeReport.write(List.of(run),out,()->false);
        try(var zip=new ZipFile(out.resolve("summary.docx").toFile())) {
            try(var in=zip.getInputStream(zip.getEntry("word/media/figure1.png"))){assertArrayEquals(figure.png(),in.readAllBytes());}
            var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            try(var in=zip.getInputStream(zip.getEntry("word/document.xml"))) {
                var doc=factory.newDocumentBuilder().parse(in);assertEquals(1,doc.getElementsByTagNameNS("http://schemas.openxmlformats.org/drawingml/2006/main","blip").getLength());
                String word="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
                assertEquals(2,doc.getElementsByTagNameNS(word,"pageBreakBefore").getLength()); // run summary and quantitative section
                assertTrue(doc.getElementsByTagNameNS(word,"keepNext").getLength()>=3);
                assertTrue(doc.getElementsByTagNameNS(word,"rFonts").getLength()>0);
            }
            try(var in=zip.getInputStream(zip.getEntry("word/_rels/document.xml.rels"))){assertTrue(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).contains("media/figure1.png"));}
        }
    }
    @Test void excelOnlySkipsUnneededDamagedFigureWithoutClaimingItWasVerified()throws Exception {
        Path run=make(),out=temp.resolve("excel-only");
        Files.write(run.resolve("report-figure/001-mask-1-figure.png"),new byte[]{1,2,3});
        IfOfficeReport.write(List.of(run),out,new IfOfficeReport.Formats(true,false),()->false);
        assertTrue(Files.exists(out.resolve("results.xlsx")));assertFalse(Files.exists(out.resolve("summary.docx")));
        var audit=JsonParser.parseString(Files.readString(out.resolve("manifest.json"))).getAsJsonObject();
        assertEquals(0,audit.get("wordQuantitativeRows").getAsInt());assertEquals(1,audit.getAsJsonObject("files").size());
        assertEquals("not_requested",audit.getAsJsonArray("reportFigures").get(0).getAsJsonObject().get("status").getAsString());
        Path rejected=temp.resolve("word-bad-image");
        assertThrows(java.io.IOException.class,()->IfOfficeReport.write(List.of(run),rejected,new IfOfficeReport.Formats(false,true),()->false));
        assertFalse(Files.exists(rejected));
    }
    @Test void rejectsTamperingAndLinksWithoutCreatingReport() throws Exception {
        Path run=make(),png=run.resolve("report-figure/001-mask-1-figure.png"),out=temp.resolve("report");byte[] original=Files.readAllBytes(png);
        Files.write(png,new byte[]{1,2});assertThrows(java.io.IOException.class,()->IfOfficeReport.write(List.of(run),out,()->false));assertFalse(Files.exists(out));
        Files.write(png,original);Files.writeString(run.resolve("report-figure/roi.json"),"{}");assertThrows(java.io.IOException.class,()->IfReportFigureInput.load(snapshot(run)));
        Files.copy(run.resolve("roi.json"),run.resolve("report-figure/roi.json"),StandardCopyOption.REPLACE_EXISTING);
        Files.delete(png);Files.createSymbolicLink(png,run.resolve("roi.json"));assertThrows(java.io.IOException.class,()->IfReportFigureInput.load(snapshot(run)));
    }
    @Test void rejectsReferenceTraversalAndStaleAnalysisSnapshot() throws Exception {
        Path run=make();var prior=snapshot(run);var m=JsonParser.parseString(Files.readString(run.resolve("manifest.json"))).getAsJsonObject();
        m.getAsJsonObject("source").getAsJsonObject("reportFigure").addProperty("manifest","../outside/manifest.json");Files.writeString(run.resolve("manifest.json"),m.toString());
        assertThrows(java.io.IOException.class,()->IfReportFigureInput.load(prior));
        assertThrows(java.io.IOException.class,()->IfReportFigureInput.load(snapshot(run)));
    }
    @Test void mixedBatchDoesNotAttachUnreferencedFigureToLegacyRun() throws Exception {
        Path first=make("first"),legacy=make("legacy"),last=make("last"),out=temp.resolve("mixed");
        var m=JsonParser.parseString(Files.readString(legacy.resolve("manifest.json"))).getAsJsonObject();
        m.getAsJsonObject("source").remove("reportFigure");Files.writeString(legacy.resolve("manifest.json"),m.toString());
        IfOfficeReport.write(List.of(first,legacy,last),out,()->false);
        var audit=JsonParser.parseString(Files.readString(out.resolve("manifest.json"))).getAsJsonObject().getAsJsonArray("reportFigures");
        assertEquals("embedded",audit.get(0).getAsJsonObject().get("status").getAsString());
        assertEquals("unavailable",audit.get(1).getAsJsonObject().get("status").getAsString());
        assertEquals("embedded",audit.get(2).getAsJsonObject().get("status").getAsString());
        try(var zip=new ZipFile(out.resolve("summary.docx").toFile())){
            assertNotNull(zip.getEntry("word/media/figure2.png"));assertNull(zip.getEntry("word/media/figure3.png"));
            try(var in=zip.getInputStream(zip.getEntry("word/document.xml"))){String xml=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertTrue(xml.contains("ROI figure unavailable"));assertTrue(xml.contains("r:embed=\"rId2\""));}
        }
    }
}
