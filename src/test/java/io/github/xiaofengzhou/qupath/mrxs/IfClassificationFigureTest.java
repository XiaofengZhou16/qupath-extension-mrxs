package io.github.xiaofengzhou.qupath.mrxs;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.xml.parsers.DocumentBuilderFactory;
import static org.junit.jupiter.api.Assertions.*;
class IfClassificationFigureTest {
    @TempDir Path temp;
    @Test void streamingDigestMatchesKnownVectorAndHonorsCancellation()throws Exception {
        Path p=temp.resolve("hash.txt");Files.writeString(p,"abc");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",IfClassificationFigure.sha256(p,()->false));
        assertThrows(java.util.concurrent.CancellationException.class,()->IfClassificationFigure.sha256(p,()->true));
    }
    @Test void emptyFileDigestStillHonorsCancellation()throws Exception {
        Path p=temp.resolve("empty.txt");Files.writeString(p,"");
        assertThrows(java.util.concurrent.CancellationException.class,()->IfClassificationFigure.sha256(p,()->true));
    }
    @Test void exportedClipPreservesHoleAndOuterTissue()throws Exception {
        var shell=IfNuclearMeasurementsTest.rect(0,0,32,8).getGeometry();
        var hole=IfNuclearMeasurementsTest.rect(12,2,8,4).getGeometry();
        var roi=qupath.lib.roi.GeometryTools.geometryToROI(shell.difference(hole),qupath.lib.regions.ImagePlane.getDefaultPlane());
        try(var server=new IfAnalysisTest.TruthServer(3)) {
            var result=IfAnalysis.analyze(server,roi,IfNuclearMeasurementsTest.profile(),List.of(),()->false,p->{});
            Path file=temp.resolve("hole.svg");IfClassificationFigure.write(file,roi,List.of(),result,()->false);
            var xml=DocumentBuilderFactory.newInstance();xml.setNamespaceAware(true);
            xml.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            var doc=xml.newDocumentBuilder().parse(file.toFile());
            var clip=(org.w3c.dom.Element)doc.getElementsByTagNameNS("*","clipPath").item(0);
            var element=(org.w3c.dom.Element)clip.getElementsByTagNameNS("*","path").item(0);
            var path=new java.awt.geom.Path2D.Double(element.getAttribute("clip-rule").equals("evenodd")
                ?java.awt.geom.Path2D.WIND_EVEN_ODD:java.awt.geom.Path2D.WIND_NON_ZERO);
            var tokens=new java.util.StringTokenizer(element.getAttribute("d").replaceAll("([MLZ])"," $1 "));
            while(tokens.hasMoreTokens()) {
                String command=tokens.nextToken();
                switch(command) {
                    case "M" -> path.moveTo(Double.parseDouble(tokens.nextToken()),Double.parseDouble(tokens.nextToken()));
                    case "L" -> path.lineTo(Double.parseDouble(tokens.nextToken()),Double.parseDouble(tokens.nextToken()));
                    case "Z" -> path.closePath();
                    default -> fail("Unexpected polygon command: "+command);
                }
            }
            assertTrue(path.contains(2,2),"Outer tissue must remain visible");
            assertFalse(path.contains(16,4),"Interior hole must be clipped out");
            assertFalse(path.contains(40,4),"Outside ROI must be clipped out");
        }
    }
    @Test void longUnicodeLegendRetainsAllText() {
        String label="标记😀<&>".repeat(80);
        var lines=IfClassificationFigure.wrap(label);
        assertEquals(label,String.join("",lines));
        assertTrue(lines.stream().allMatch(v->v.codePointCount(0,v.length())<=60));
    }
    @Test void mapUsesMeasuredMasksAndEscapesIdsWithoutMutatingResults()throws Exception {
        var roi=IfNuclearMeasurementsTest.rect(0,0,32,8);
        var cells=List.of(new IfAnalysis.Cell("a<&\"",IfNuclearMeasurementsTest.rect(4,0,4,4),IfNuclearMeasurementsTest.rect(5,1,2,2)),
                new IfAnalysis.Cell("excluded",IfNuclearMeasurementsTest.rect(12,0,2,2),null));
        try(var server=new IfAnalysisTest.TruthServer(3)) {
            var result=IfAnalysis.analyze(server,roi,IfNuclearMeasurementsTest.profile(),cells,()->false,p->{});
            Path file=temp.resolve("map.svg");IfClassificationFigure.write(file,roi,cells,result,()->false);
            var f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            var doc=f.newDocumentBuilder().parse(file.toFile());var gs=doc.getElementsByTagNameNS("*","g");int measured=0;
            for(int i=0;i<gs.getLength();i++){var g=(org.w3c.dom.Element)gs.item(i);if(g.hasAttribute("data-cell-id")){measured++;assertEquals("a<&\"",g.getAttribute("data-cell-id"));assertEquals("1",g.getAttribute("data-pn-mask"));}}
            assertEquals(1,measured);assertEquals(1,doc.getElementsByTagNameNS("*","clipPath").getLength());
            assertTrue(doc.getDocumentElement().getTextContent().contains("horizontal"));assertEquals(1,result.cells().size());
            Path cancelled=temp.resolve("cancel.svg");assertThrows(java.util.concurrent.CancellationException.class,()->IfClassificationFigure.write(cancelled,roi,cells,result,()->true));assertFalse(Files.exists(cancelled));
            var checks=new java.util.concurrent.atomic.AtomicInteger();
            assertThrows(java.util.concurrent.CancellationException.class,()->IfClassificationFigure.write(cancelled,roi,cells,result,()->checks.incrementAndGet()>1));
            assertFalse(Files.exists(cancelled));
            try(var paths=Files.list(temp)){assertFalse(paths.anyMatch(p->p.getFileName().toString().endsWith(".tmp")));}
            var before=Files.readAllBytes(file);
            assertThrows(FileAlreadyExistsException.class,()->IfClassificationFigure.write(file,roi,cells,result,()->false));
            assertArrayEquals(before,Files.readAllBytes(file));
        }
    }
}
