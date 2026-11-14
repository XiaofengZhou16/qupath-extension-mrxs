package io.github.xiaofengzhou.qupath.mrxs;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.lib.display.*;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.regions.RegionRequest;
import static org.junit.jupiter.api.Assertions.*;

class IfPreparedRendererTest {
    @Test void stripedRenderingMatchesReferenceAndCancelsBetweenStripes()throws Exception {
        try(var server=IfPublicationExportTest.fixture()) {
            // Nonmultiple stripe height and subimage exercise offsets/scanline strides.
            var full=server.readRegion(RegionRequest.createInstance(server.getPath(),1,0,0,512,256));
            var raw=full.getSubimage(7,9,257,193);
            var before=full.getRaster().getPixels(0,0,512,256,(int[])null);
            var states=List.of(new IfDisplayProfile.Channel(0,"A",0x82e1ff,11,141,true),
                    new IfDisplayProfile.Channel(1,"B",0xe9a054,7,99,true),
                    new IfDisplayProfile.Channel(2,"C",0xf146d0,0,64,true));
            for(boolean gray:List.of(false,true))for(boolean inverted:List.of(false,true))for(double gamma:List.of(1.0,0.65,1.4)) {
                var profile=new IfDisplayProfile(1,gamma,gray,inverted,states);
                var renderer=IfDisplaySettings.prepare(server,profile);
                BufferedImage output=null;
                for(int mask=1;mask<8;mask++) {
                    var selected=new ArrayList<Integer>();
                    for(int c=0;c<3;c++)if((mask&(1<<c))!=0)selected.add(c);
                    var expected=freshReference(server,raw,profile,selected);
                    output=renderer.renderCancellable(raw,selected,output,()->false);
                    assertArrayEquals(expected.getRGB(0,0,257,193,null,0,257),output.getRGB(0,0,257,193,null,0,257));
                }
                var polls=new java.util.concurrent.atomic.AtomicInteger();
                assertThrows(java.util.concurrent.CancellationException.class,
                        ()->renderer.renderCancellable(raw,List.of(0,1),null,()->polls.incrementAndGet()>=4));
                assertEquals(4,polls.get());
                assertThrows(IllegalArgumentException.class,()->renderer.renderCancellable(raw,List.of(0),raw,()->false));
                var retried=renderer.renderCancellable(raw,List.of(0),output,()->false);
                assertSame(output,retried);
                assertArrayEquals(freshReference(server,raw,profile,List.of(0)).getRGB(0,0,257,193,null,0,257),
                        retried.getRGB(0,0,257,193,null,0,257));
            }
            assertArrayEquals(before,full.getRaster().getPixels(0,0,512,256,(int[])null));
        }
    }
    @Test void repeatedCombinationsMatchFreshQuPathTransformsInEveryDisplayMode()throws Exception {
        try(var server=IfPublicationExportTest.fixture()) {
            var raw=server.readRegion(RegionRequest.createInstance(server.getPath(),1,0,0,128,64));
            var before=raw.getRaster().getPixels(0,0,raw.getWidth(),raw.getHeight(),(int[])null);
            var states=List.of(new IfDisplayProfile.Channel(0,"A",0x82e1ff,11,141,true),
                    new IfDisplayProfile.Channel(1,"B",0xe9a054,7,99,true),
                    new IfDisplayProfile.Channel(2,"C",0xf146d0,0,64,true));
            for(boolean gray:List.of(false,true))for(boolean inverted:List.of(false,true))for(double gamma:List.of(1.0,0.65,1.4)) {
                var profile=new IfDisplayProfile(1,gamma,gray,inverted,states);
                var renderer=IfDisplaySettings.prepare(server,profile);
                BufferedImage reusable=null;
                // Descending then ascending masks exercise switching, not just one render.
                for(int pass=0;pass<2;pass++)for(int m=1;m<8;m++) {
                    int mask=pass==0?8-m:m;
                    var selected=new ArrayList<Integer>();
                    for(int c=0;c<3;c++)if((mask&(1<<c))!=0)selected.add(c);
                    var expected=freshReference(server,raw,profile,selected);
                    var actual=renderer.render(raw,selected);
                    reusable=renderer.renderInto(raw,selected,reusable);
                    assertEquals(expected.getWidth(),actual.getWidth());
                    assertEquals(expected.getHeight(),actual.getHeight());
                    assertArrayEquals(expected.getRGB(0,0,expected.getWidth(),expected.getHeight(),null,0,expected.getWidth()),
                            actual.getRGB(0,0,actual.getWidth(),actual.getHeight(),null,0,actual.getWidth()),
                            "mask="+mask+" grayscale="+gray+" inverted="+inverted+" gamma="+gamma);
                    assertArrayEquals(expected.getRGB(0,0,expected.getWidth(),expected.getHeight(),null,0,expected.getWidth()),
                            reusable.getRGB(0,0,reusable.getWidth(),reusable.getHeight(),null,0,reusable.getWidth()));
                }
                assertThrows(IllegalArgumentException.class,()->renderer.renderInto(raw,List.of(0),raw));
                var alias=new BufferedImage(raw.getColorModel(),raw.getRaster(),raw.isAlphaPremultiplied(),null);
                assertThrows(IllegalArgumentException.class,()->renderer.renderInto(raw,List.of(0),alias));
            }
            assertArrayEquals(before,raw.getRaster().getPixels(0,0,raw.getWidth(),raw.getHeight(),(int[])null));
        }
    }

    // Independent fresh-object reference preserves the pre-optimization algorithm.
    static BufferedImage freshReference(ImageServer<BufferedImage> server,BufferedImage raw,
                                                IfDisplayProfile profile,List<Integer> selected) {
        profile.validateFor(server);
        var data=new ImageData<>(server,ImageData.ImageType.FLUORESCENCE);
        var channels=new ArrayList<DirectServerChannelInfo>();
        for(var state:profile.channels())if(selected.contains(state.index())) {
            var c=new DirectServerChannelInfo(data,state.index());int rgb=state.color();
            c.setLUTColor((rgb>>16)&255,(rgb>>8)&255,rgb&255);
            c.setMinDisplay((float)state.minimum());c.setMaxDisplay((float)state.maximum());channels.add(c);
        }
        var mode=profile.grayscale()?(profile.inverted()?ChannelDisplayMode.INVERTED_GRAYSCALE:ChannelDisplayMode.GRAYSCALE)
                :(profile.inverted()?ChannelDisplayMode.INVERTED_COLOR:ChannelDisplayMode.COLOR);
        var image=ImageDisplay.applyTransforms(raw,null,channels,mode);
        if(profile.gamma()!=1)image=IfDisplaySettings.gamma(profile.gamma()).filter(image,null);
        return image;
    }
}
