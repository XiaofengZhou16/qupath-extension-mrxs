package io.github.xiaofengzhou.qupath.mrxs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.color.ColorModelFactory;
import qupath.lib.images.servers.*;
import qupath.lib.io.GsonTools;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RoiTools;
import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadataNode;
import java.awt.Point;
import java.awt.image.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class IfPublicationExportTest {
    @TempDir Path temporary;
    static WrappedBufferedImageServer fixture() {
        return fixture(null);
    }
    static WrappedBufferedImageServer fixture(Path localSource) {
        var channels=List.of(ImageChannel.getInstance("A",0xff0000),ImageChannel.getInstance("B",0x00ff00),ImageChannel.getInstance("C",0x0000ff));
        var raster=Raster.createBandedRaster(DataBuffer.TYPE_BYTE,512,256,3,new Point());
        for(int y=0;y<256;y++)for(int x=0;x<512;x++){raster.setSample(x,y,0,x%256);raster.setSample(x,y,1,y);raster.setSample(x,y,2,32);}
        var server=new WrappedBufferedImageServer("display truth",new BufferedImage(ColorModelFactory.createColorModel(PixelType.UINT8,channels),raster,false,null),channels){
            @Override public java.util.Collection<java.net.URI> getURIs(){return localSource==null?super.getURIs():List.of(localSource.toUri());}
        };
        server.setMetadata(new ImageServerMetadata.Builder(server.getMetadata()).rgb(false).pixelSizeMicrons(0.5,0.8).build());return server;
    }
    static IfDisplayProfile profile(double gamma){return new IfDisplayProfile(1,gamma,false,false,List.of(
            new IfDisplayProfile.Channel(0,"A",0xff0000,0,255,true),new IfDisplayProfile.Channel(1,"B",0x00ff00,0,255,true),new IfDisplayProfile.Channel(2,"C",0x0000ff,0,255,true)));}
    private static qupath.lib.roi.interfaces.ROI roi(){return ROIs.createRectangleROI(0,0,512,256,ImagePlane.getDefaultPlane());}
    @Test void pngEncodingHonorsCancellationDuringProgressAndCanRetry() throws Exception {
        var image=new BufferedImage(1024,1024,BufferedImage.TYPE_INT_RGB);
        var checks=new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(CancellationException.class,()->IfPublicationExport.writePng(image,temporary.resolve("aborted.png"),300,()->checks.incrementAndGet()>=4));
        assertTrue(checks.get()>=4);
        Path retry=temporary.resolve("retry.png");IfPublicationExport.writePng(image,retry,300,()->false);
        var read=ImageIO.read(retry.toFile());assertEquals(1024,read.getWidth());assertEquals(1024,read.getHeight());
        Path early=temporary.resolve("not-created.png");
        assertThrows(CancellationException.class,()->IfPublicationExport.writePng(image,early,300,()->true));
        assertFalse(Files.exists(early));
    }
    @Test void sourceFileChangeKeepsPublicationIncomplete() throws Exception {
        Path source=temporary.resolve("source.bin");Files.writeString(source,"original");
        try(var server=fixture(source)) {
            Path output=temporary.resolve("changed-source");
            assertThrows(java.io.IOException.class,()->IfPublicationExport.export(server,roi(),profile(1),
                    new IfPublicationExport.Options(0,300,8,false,false,0),output,()->false,p->{
                        try{Files.writeString(source,"changed source");}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
                    }));
            assertTrue(Files.exists(output.resolve("INCOMPLETE")));
            assertFalse(Files.exists(output.resolve("manifest.json")));
        }
    }
    @Test void exportPreservesFractionalRoiAndHashesItsSettings() throws Exception {
        try(var server=fixture()) {
            var selected=ROIs.createPolygonROI(new double[]{10.496,490.504,480.497,20.503},new double[]{10.499,10.501,240.499,240.501},ImagePlane.getDefaultPlane());
            Path output=temporary.resolve("precise-roi");
            IfPublicationExport.export(server,selected,profile(1.2),new IfPublicationExport.Options(0,300,8,false,false,0),output,()->false,p->{});
            var json=com.google.gson.JsonParser.parseString(Files.readString(output.resolve("roi.json"))).getAsJsonObject();
            var restored=IfGeometryExport.read(json,GsonTools.getInstance().fromJson(json,qupath.lib.roi.interfaces.ROI.class));
            for(int y=0;y<256;y++)for(int x=0;x<512;x++)assertEquals(selected.contains(x+0.5,y+0.5),restored.contains(x+0.5,y+0.5));
            var manifest=com.google.gson.JsonParser.parseString(Files.readString(output.resolve("manifest.json"))).getAsJsonObject();
            for(var entry:java.util.Map.of("roi.json","roiJsonSha256","display-profile.json","displayProfileJsonSha256").entrySet()) {
                var hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(output.resolve(entry.getKey()))));
                assertEquals(hash,manifest.get(entry.getValue()).getAsString());
            }
            assertTrue(manifest.get("sourceFileFingerprint").isJsonNull()); // in-memory fixture has no file identity
        }
    }
    @Test void displayProfileRoundTripsAndRejectsWrongMappingsAndInvalidRanges() throws Exception {
        var p=profile(1.3);assertEquals(p,GsonTools.getInstance().fromJson(GsonTools.getInstance().toJson(p),IfDisplayProfile.class));
        assertThrows(IllegalArgumentException.class,()->new IfDisplayProfile.Channel(0,"A",0,10,10,true));
        assertThrows(IllegalArgumentException.class,()->new IfDisplayProfile(1,Double.NaN,false,false,p.channels()));
        try(var server=fixture()) {
            p.validateFor(server);
            var wrong=new IfDisplayProfile(1,1,false,false,List.of(new IfDisplayProfile.Channel(0,"Different",0,0,255,true),p.channels().get(1),p.channels().get(2)));
            assertThrows(IllegalArgumentException.class,()->wrong.validateFor(server));
        }
    }
    @Test void renderingUsesExpectedChannelColorsAndDoesNotModifyRawRaster() throws Exception {
        try(var server=fixture()) {
            var raw=server.readRegion(RegionRequest.createInstance(server.getPath(),1,0,0,512,256));
            var before=raw.getRaster().getPixels(0,0,512,256,(int[])null);
            var merged=IfDisplaySettings.render(server,raw,profile(1),List.of(0,1,2));
            assertEquals(0xff804020,merged.getRGB(128,64));
            var onlyB=IfDisplaySettings.render(server,raw,profile(1),List.of(1));
            assertEquals(0xff004000,onlyB.getRGB(128,64));
            assertArrayEquals(before,raw.getRaster().getPixels(0,0,512,256,(int[])null));
        }
    }
    @Test void dpiChangesMetadataNotSourcePixelDimensionsOrValues() throws Exception {
        try(var server=fixture()) {
            Path a=temporary.resolve("dpi300"),b=temporary.resolve("dpi600");
            for(int dpi:List.of(300,600))IfPublicationExport.export(server,roi(),profile(1.4),
                    new IfPublicationExport.Options(0,dpi,8,false,true,25),dpi==300?a:b,()->false,p->{});
            var first=ImageIO.read(a.resolve("001-mask-7-clean.png").toFile());
            var second=ImageIO.read(b.resolve("001-mask-7-clean.png").toFile());
            assertEquals(512,first.getWidth());assertEquals(256,second.getHeight());
            assertArrayEquals(first.getRGB(0,0,512,256,null,0,512),second.getRGB(0,0,512,256,null,0,512));
            assertEquals(300,pngDpi(a.resolve("001-mask-7-clean.png")),0.02);
            assertEquals(600,pngDpi(b.resolve("001-mask-7-figure.png")),0.02);
            assertFalse(Files.exists(a.resolve("INCOMPLETE")));
        }
    }
    @Test void annotationMarginsPreserveEveryImagePixelAndScaleUsesHorizontalCalibration() throws Exception {
        try(var server=fixture()) {
            var raw=server.readRegion(RegionRequest.createInstance(server.getPath(),1,0,0,512,256));
            var image=IfDisplaySettings.render(server,raw,profile(1),List.of(0,1,2));
            var panel=IfPublicationExport.decorate(image,profile(1).channels(),profile(1),new IfPublicationExport.Options(0,300,8,true,true,25),0.5);
            assertEquals(50,panel.scaleBarPixels());assertEquals(25,panel.drawnScaleBarMicrons());
            assertArrayEquals(image.getRGB(0,0,512,256,null,0,512),panel.image().getRGB(0,panel.imageOffsetY(),512,256,null,0,512));
            assertTrue(panel.legendHeight()>0 && panel.footerHeight()>0);
        }
    }
    @Test void exportsAllSubsetsMasksRoiHolesAndNeverOverwritesPreviousRun() throws Exception {
        try(var server=fixture()) {
            var hole=ROIs.createRectangleROI(100,100,50,50,ImagePlane.getDefaultPlane());
            var selected=RoiTools.difference(roi(),hole);Path output=temporary.resolve("subsets");
            var options=new IfPublicationExport.Options(0,300,8,true,true,0);
            IfPublicationExport.export(server,selected,profile(1),options,output,()->false,p->{});
            try(var files=Files.list(output)){assertEquals(14,files.filter(p->p.toString().endsWith(".png")).count());}
            var png=ImageIO.read(output.resolve("001-mask-7-clean.png").toFile());
            assertEquals(0xff000000,png.getRGB(120,120));assertNotEquals(0xff000000,png.getRGB(200,200));
            var quality=GsonTools.getInstance().fromJson(Files.readString(output.resolve("display-qc.json")),IfDisplayQuality.Report.class);
            assertEquals(512L*256-50L*50,quality.sampledRoiPixels());
            assertEquals(quality.sampledRoiPixels(),quality.channels().getFirst().finitePixels());
            assertTrue(Files.readString(output.resolve("display-qc.txt")).contains("not sensor-saturation"));
            var manifest=com.google.gson.JsonParser.parseString(Files.readString(output.resolve("manifest.json"))).getAsJsonObject();
            assertEquals(quality.sampledRoiPixels(),manifest.getAsJsonObject("displayQuality").get("sampledRoiPixels").getAsLong());
            var expectedHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(output.resolve("display-qc.json"))));
            assertEquals(expectedHash,manifest.get("displayQualityJsonSha256").getAsString());
            assertThrows(java.nio.file.FileAlreadyExistsException.class,()->IfPublicationExport.export(server,selected,profile(1),options,output,()->false,p->{}));
        }
    }
    @Test void longMultilingualLegendsWrapWithoutChangingSourceOrPaintingAtCanvasEdges() {
        var channels=java.util.stream.IntStream.range(0,8).mapToObj(i->new IfDisplayProfile.Channel(i,
                "Marker "+i+" / collagen-associated fluorescence — 核与胞质 "+"long-channel-name-".repeat(3),
                0x40c0ff,0,255,true)).toList();
        var profile=new IfDisplayProfile(1,1,false,false,channels);
        var source=new BufferedImage(1024,64,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<64;y++)for(int x=0;x<1024;x++)source.setRGB(x,y,(x*31+y*17)&0xffffff);
        for(int dpi:List.of(300,600)) {
            var panel=IfPublicationExport.decorate(source,channels,profile,
                    new IfPublicationExport.Options(0,dpi,8,true,true,50),0.5);
            assertArrayEquals(source.getRGB(0,0,1024,64,null,0,1024),
                    panel.image().getRGB(0,panel.imageOffsetY(),1024,64,null,0,1024));
            assertTrue(panel.legendHeight()>100,"Long legend must create multiple rows");
            assertEquals(100,panel.scaleBarPixels());
            var canvas=panel.image();
            for(int x=0;x<canvas.getWidth();x++) {
                assertEquals(0xff000000,canvas.getRGB(x,0));
                assertEquals(0xff000000,canvas.getRGB(x,canvas.getHeight()-1));
            }
            for(int y=0;y<canvas.getHeight();y++) {
                if(y>=panel.imageOffsetY()&&y<panel.imageOffsetY()+64)continue;
                assertEquals(0xff000000,canvas.getRGB(0,y));
                assertEquals(0xff000000,canvas.getRGB(canvas.getWidth()-1,y));
            }
        }
    }

    @Test void rejectsUnusableFontAndScaleBarRatherThanSilentlyClipping() {
        var source=new BufferedImage(128,64,BufferedImage.TYPE_INT_RGB);
        assertThrows(IllegalArgumentException.class,()->IfPublicationExport.decorate(source,profile(1).channels(),profile(1),
                new IfPublicationExport.Options(0,600,8,true,true,0),0.5));
        var wide=new BufferedImage(1024,64,BufferedImage.TYPE_INT_RGB);
        assertThrows(IllegalArgumentException.class,()->IfPublicationExport.decorate(wide,profile(1).channels(),profile(1),
                new IfPublicationExport.Options(0,300,8,true,true,1000),0.5));
    }
    @Test void cancellationKeepsIncompleteMarkerAndNoSuccessfulManifest() throws Exception {
        try(var server=fixture()) {
            var cancelled=new AtomicBoolean();var output=temporary.resolve("cancelled");
            assertThrows(CancellationException.class,()->IfPublicationExport.export(server,roi(),profile(1),
                    new IfPublicationExport.Options(0,300,8,true,true,0),output,cancelled::get,p->cancelled.set(true)));
            assertTrue(Files.exists(output.resolve("INCOMPLETE")));assertFalse(Files.exists(output.resolve("manifest.json")));
        }
    }
    private static double pngDpi(Path path)throws Exception {
        try(var input=ImageIO.createImageInputStream(path.toFile())) {
            var reader=ImageIO.getImageReaders(input).next();try {reader.setInput(input);
                var root=(IIOMetadataNode)reader.getImageMetadata(0).getAsTree("javax_imageio_png_1.0");
                var phys=(IIOMetadataNode)root.getElementsByTagName("pHYs").item(0);assertEquals("meter",phys.getAttribute("unitSpecifier"));
                return Double.parseDouble(phys.getAttribute("pixelsPerUnitXAxis"))*0.0254;
            }finally{reader.dispose();}
        }
    }
}
