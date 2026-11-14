package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class IfHistoricalNucleiTest {
    @TempDir Path temp;
    private IfAnalysisHistory.Entry make()throws Exception {
        try(var server=new IfAnalysisTest.TruthServer(4)) {
            var cells=List.of(new IfAnalysis.Cell("normal",IfNuclearMeasurementsTest.rect(4,0,4,4),IfNuclearMeasurementsTest.rect(5,1,2,2)),
                    new IfAnalysis.Cell("nucleus-only",null,IfNuclearMeasurementsTest.rect(12,1,2,2)),
                    new IfAnalysis.Cell("=protected",null,IfNuclearMeasurementsTest.rect(28,1,2,2)),
                    new IfAnalysis.Cell("missing",null,null));
            var out=temp.resolve("run");IfAnalysisExport.analyzeAndWrite(out,server,IfNuclearMeasurementsTest.rect(0,0,32,8),
                    IfNuclearMeasurementsTest.profile(),cells,Map.of(),false,()->false,p->{});
            return IfAnalysisHistory.scan(out,()->false).entries().getFirst();
        }
    }
    @Test void restoresIndependentNucleiWithProtectedIdentityAndNoCellDetection()throws Exception {
        var entry=make();var loaded=IfHistoricalNuclei.load(entry,()->false);
        assertEquals(1,entry.measuredCells());assertEquals(3,loaded.outlines().size());
        assertEquals(Map.of("normal",1,"nucleus-only",3,"=protected",7),loaded.masks());
        assertTrue(loaded.outlines().stream().allMatch(c->c.cell()!=null&&c.nucleus()==null));
        // Drawing adapter uses only the saved nuclear shape, never the larger original cell.
        assertEquals(4,loaded.outlines().getFirst().cell().getArea());
        assertThrows(java.util.concurrent.CancellationException.class,()->IfHistoricalNuclei.load(entry,()->true));
    }
    @Test void rejectsCentroidMaskOrDuplicateIdentityMismatch()throws Exception {
        var entry=make();Path csv=entry.directory().resolve("nuclei.csv");String original=Files.readString(csv);
        for(int column:List.of(0,1,4)) {
            var rows=IfReportCsv.parse(original,100,24);var altered=new StringBuilder();
            for(int i=0;i<rows.size();i++) {
                var row=new ArrayList<>(rows.get(i));
                if(i==1)row.set(column,column==0?rows.get(2).getFirst():column==1?"999":"0");
                altered.append(String.join(",",row)).append('\n');
            }
            Files.writeString(csv,altered.toString());
            assertThrows(java.io.IOException.class,()->IfHistoricalNuclei.load(entry,()->false));
        }
    }
    @Test void nuclearOverlayFiltersInclusiveVersusExactWithoutHierarchyObjects()throws Exception {
        var entry=make();var loaded=IfHistoricalNuclei.load(entry,()->false);
        try(var server=new IfAnalysisTest.TruthServer(4)) {
            var data=new qupath.lib.images.ImageData<java.awt.image.BufferedImage>(server);
            var overlay=new IfCellOverlay(data,loaded.outlines(),loaded.masks());
            int[] counts=new int[2];
            for(int mode=0;mode<2;mode++) {
                overlay.setFilter(3,mode==1);
                // Synthetic offscreen paint buffer, not a screen/viewer capture.
                var buffer=new java.awt.image.BufferedImage(32,8,java.awt.image.BufferedImage.TYPE_INT_ARGB);
                var g=buffer.createGraphics();g.setClip(0,0,32,8);
                try{overlay.paintOverlay(g,qupath.lib.regions.ImageRegion.createInstance(0,0,32,8,0,0),1,data,true);}finally{g.dispose();}
                for(int y=0;y<8;y++)for(int x=0;x<32;x++)if((buffer.getRGB(x,y)>>>24)!=0)counts[mode]++;
            }
            assertTrue(counts[0]>counts[1]);assertTrue(counts[1]>0);
            assertTrue(data.getHierarchy().getAnnotationObjects().isEmpty());assertTrue(data.getHierarchy().getDetectionObjects().isEmpty());
        }
    }
    @Test void rejectsMissingSupplementAndLinkedGeometry()throws Exception {
        var entry=make();Path geo=entry.directory().resolve("nuclei.geojson"),saved=temp.resolve("geometry.json");
        Files.move(geo,saved);Files.createSymbolicLink(geo,saved);
        assertThrows(java.io.IOException.class,()->IfHistoricalNuclei.load(entry,()->false));
        Files.delete(geo);Files.move(saved,geo);
        Path manifest=entry.directory().resolve("manifest.json");var m=com.google.gson.JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        m.remove("nuclearMeasurements");Files.writeString(manifest,m.toString());
        assertThrows(java.io.IOException.class,()->IfHistoricalNuclei.load(entry,()->false));
    }
}
