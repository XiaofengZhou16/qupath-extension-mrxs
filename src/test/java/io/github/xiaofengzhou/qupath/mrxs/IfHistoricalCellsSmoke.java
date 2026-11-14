package io.github.xiaofengzhou.qupath.mrxs;
import java.nio.file.*;
import java.util.*;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
/** Real reader with explicitly synthetic cell geometry: tests restoration, not segmentation accuracy. */
public class IfHistoricalCellsSmoke {
    public static void main(String[] args)throws Exception {
        try(var s=new MrxsImageServer(Path.of(args[0]).toUri())) {
            var plane=ImagePlane.getDefaultPlane();var cells=new ArrayList<IfAnalysis.Cell>();
            for(int i=0;i<3;i++)cells.add(new IfAnalysis.Cell("test-geometry-"+i,ROIs.createRectangleROI(32800+i*40,81960,30,30,plane),ROIs.createRectangleROI(32805+i*40,81965,10,10,plane)));
            boolean all=args.length>2&&args[2].equals("all");
            if(all||(args.length>2&&args[2].equals("excluded"))){
                cells.add(new IfAnalysis.Cell("test-nucleus-only",null,ROIs.createRectangleROI(32810,82010,10,10,plane)));
                cells.add(new IfAnalysis.Cell("test-cell-only",ROIs.createRectangleROI(32850,82010,20,20,plane),null));
                cells.add(new IfAnalysis.Cell("test-no-geometries",null,null));
            }
            var markers=java.util.stream.IntStream.range(1,s.nChannels()).mapToObj(c->new IfAnalysisProfile.Marker(c,s.getChannel(c).getName(),30,30,30)).toList();
            Path p=Path.of(args[1]);var result=IfAnalysisExport.analyzeAndWrite(p,s,ROIs.createRectangleROI(32768,81920,512,512,plane),new IfAnalysisProfile(1,0,markers),cells,Map.of("method","SYNTHETIC geometry for restoration test; not annotations or AI truth"),all,()->false,v->{});
            var entry=IfHistorySource.validate(p,IfHistorySource.capture(s),()->false);var loaded=IfHistoricalCells.load(entry,()->false);
            if(loaded.cells().size()!=3||result.cells().stream().anyMatch(c->loaded.pnMasks().get(c.id())!=c.pn()))throw new AssertionError("Restoration mismatch");
            System.out.println("IF_HISTORICAL_CELLS_OK cells=3 reader=real geometry=synthetic phenotypeMatchesExport=true");
        }
    }
}
