package io.github.xiaofengzhou.qupath.mrxs;

import org.junit.jupiter.api.Test;
import qupath.lib.images.ImageData;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.ImageRegion;
import qupath.lib.roi.ROIs;
import java.awt.image.BufferedImage;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class IfCellOverlayTest {
    @Test void backgroundPreparationIsCancellableAndKeepsRenderedPixels()throws Exception {
        try(var server=new IfAnalysisTest.TruthServer(4)){
            var data=new ImageData<>(server,ImageData.ImageType.FLUORESCENCE);
            var roi=ROIs.createRectangleROI(1,1,8,5,ImagePlane.getDefaultPlane());
            var cells=java.util.stream.IntStream.range(0,1000).mapToObj(i->new IfAnalysis.Cell("c"+i,roi,null)).toList();
            var masks=cells.stream().collect(java.util.stream.Collectors.toMap(IfAnalysis.Cell::id,c->1));
            assertThrows(java.util.concurrent.CancellationException.class,()->new IfCellOverlay(data,cells,masks,()->true));
            var checks=new java.util.concurrent.atomic.AtomicInteger();
            assertThrows(java.util.concurrent.CancellationException.class,()->new IfCellOverlay(data,cells,masks,()->checks.incrementAndGet()>10));
            assertEquals(11,checks.get());
            var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
            try{
                var background=executor.submit(()->new IfCellOverlay(data,cells,masks,()->false)).get(10,java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(paint(new IfCellOverlay(data,cells,masks),data,0),paint(background,data,0));
                assertTrue(paint(background,data,0)>0);
            }finally{executor.shutdownNow();}
        }
    }
    @Test void differentFailureReasonsSelectDisjointOutlines() throws Exception {
        try(var server=new IfAnalysisTest.TruthServer(4)) {
            var plane=ImagePlane.getDefaultPlane();
            var roi=ROIs.createRectangleROI(0,0,32,8,plane);
            var left=ROIs.createRectangleROI(1,1,8,5,plane);
            var right=ROIs.createRectangleROI(21,1,8,5,plane);
            var cells=List.of(new IfAnalysis.Cell("missing",left,null),new IfAnalysis.Cell("no-cyto",right,right));
            var profile=new IfAnalysisProfile(1,0,List.of(new IfAnalysisProfile.Marker(0,"A",100,100,100)));
            var result=IfAnalysis.analyze(server,roi,profile,cells,()->false,p->{});
            var data=new ImageData<>(server,ImageData.ImageType.FLUORESCENCE);
            var overlay=new IfCellOverlay(data,cells,result);overlay.setRejectedOnly(true);
            long all=paint(overlay,data,0);
            overlay.setRejectedReason("missing_nucleus_roi");long missing=paint(overlay,data,0);
            overlay.setRejectedReason("no_valid_cytoplasm_pixels");long noCyto=paint(overlay,data,0);
            assertTrue(missing>0);assertTrue(noCyto>0);assertEquals(all,missing+noCyto);
            assertEquals(2,result.rejectedCells().size());assertTrue(result.cells().isEmpty());
            assertTrue(data.getHierarchy().getDetectionObjects().isEmpty());
        }
    }
    @Test void rejectedOutlinesAreOptInAndIgnorePhenotypeFilter() throws Exception {
        try(var server=new IfAnalysisTest.TruthServer(4)) {
            var roi=ROIs.createRectangleROI(1,1,10,6,ImagePlane.getDefaultPlane());
            var cells=List.of(new IfAnalysis.Cell("no-nucleus",roi,null));
            var profile=new IfAnalysisProfile(1,0,List.of(new IfAnalysisProfile.Marker(0,"A",100,100,100)));
            var result=IfAnalysis.analyze(server,roi,profile,cells,()->false,p->{});
            var data=new ImageData<>(server,ImageData.ImageType.FLUORESCENCE);
            var overlay=new IfCellOverlay(data,cells,result);
            assertEquals(0,paint(overlay,data,0));
            overlay.setRejectedOnly(true);overlay.setFilter(1,true);
            assertTrue(paint(overlay,data,0)>0);
            overlay.setRejectedReason("no_valid_cytoplasm_pixels");
            assertEquals(0,paint(overlay,data,0));
            overlay.setRejectedReason("missing_nucleus_roi");
            assertTrue(paint(overlay,data,0)>0);
            overlay.setRejectedReason(null);
            assertTrue(paint(overlay,data,0)>0);
            assertEquals(0,paint(overlay,data,1));
            overlay.setVisible(false);assertEquals(0,paint(overlay,data,0));
            assertTrue(data.getHierarchy().getDetectionObjects().isEmpty());
        }
    }
    private static long paint(IfCellOverlay overlay,ImageData<BufferedImage> data,int z) {
        var image=new BufferedImage(32,8,BufferedImage.TYPE_INT_ARGB);
        var g=image.createGraphics();
        try {overlay.paintOverlay(g,ImageRegion.createInstance(0,0,32,8,z,0),1,data,true);}
        finally {g.dispose();}
        long count=0;for(int y=0;y<8;y++)for(int x=0;x<32;x++)if((image.getRGB(x,y)>>>24)!=0)count++;
        return count;
    }
}
