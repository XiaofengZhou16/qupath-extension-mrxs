package io.github.xiaofengzhou.qupath.mrxs;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** White-box spatial-index check: lookup buckets must not determine compositing order. */
class MrxsPlacementOrderTest {
    @Test void overlappingTilesHaveSameOrderForWholeAndPartialRequests() throws Exception {
        Class<?> tile=Class.forName(MrxsImageServer.class.getName()+"$PlacedImage");
        var constructor=tile.getDeclaredConstructor(int.class,int.class,int.class,int.class,
                double.class,double.class,MiraxIndex.TileEntry.class);
        constructor.setAccessible(true);
        Object earlier=constructor.newInstance(600,0,100,100,0.5,0.5,new MiraxIndex.TileEntry(0,1,1,0));
        Object later=constructor.newInstance(0,0,1100,100,0.5,0.5,new MiraxIndex.TileEntry(1,2,1,0));
        Class<?> index=Class.forName(MrxsImageServer.class.getName()+"$PlacedImageIndex");
        var indexConstructor=index.getDeclaredConstructor(List.class,int.class);indexConstructor.setAccessible(true);
        Object spatial=indexConstructor.newInstance(List.of(earlier,later),512);
        var query=index.getDeclaredMethod("query",int.class,int.class,int.class,int.class);query.setAccessible(true);
        List<?> whole=(List<?>)query.invoke(spatial,0,0,1024,100);
        List<?> partial=(List<?>)query.invoke(spatial,512,0,512,100);
        assertEquals(List.of(earlier,later),partial);
        assertEquals(partial,whole,"Bucket traversal must not reverse the overlap winner");
    }
}
