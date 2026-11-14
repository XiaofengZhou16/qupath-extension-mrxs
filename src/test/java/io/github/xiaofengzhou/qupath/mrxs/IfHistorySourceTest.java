package io.github.xiaofengzhou.qupath.mrxs;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.roi.ROIs;
import qupath.lib.regions.ImagePlane;
import static org.junit.jupiter.api.Assertions.*;
class IfHistorySourceTest {
    @TempDir Path temp;
    @Test void acceptsExactLocatorMetadataAndRejectsMismatchesWithoutPixelReads()throws Exception {
        try(var s=new IfAnalysisTest.TruthServer(4)){
            Path run=temp.resolve("run");
            IfAnalysisExport.analyzeAndWrite(run,s,ROIs.createRectangleROI(0,0,32,8,ImagePlane.getDefaultPlane()),
                    new IfAnalysisProfile(1,0,List.of(new IfAnalysisProfile.Marker(0,"A",30,30,30))),List.of(),Map.of(),false,()->false,v->{});
            var c=IfHistorySource.capture(s);int reads=s.reads;
            assertThrows(java.io.IOException.class,()->IfHistorySource.validate(run,c,()->false)); // empty URI is not identity
            var current=new IfHistorySource.Current(List.of("file:///synthetic-fixture.mrxs"),c.serverClass(),c.width(),c.height(),c.zSlices(),c.timepoints(),c.channels(),c.pixelWidth(),c.pixelHeight(),c.downsamples());
            Path path=run.resolve("manifest.json");var m=JsonParser.parseString(Files.readString(path)).getAsJsonObject();var uris=new JsonArray();uris.add("file:///synthetic-fixture.mrxs");m.getAsJsonObject("source").add("uris",uris);String original=m.toString();Files.writeString(path,original);
            assertEquals(32,IfHistorySource.validate(run,current,()->false).roi().getBoundsWidth());
            for(String field:List.of("width","serverClass","uris","channels")){
                var changed=JsonParser.parseString(original).getAsJsonObject();var source=changed.getAsJsonObject("source");
                switch(field){case "width"->source.addProperty(field,999);case "serverClass"->source.addProperty(field,"other.Reader");case "uris"->{var a=new JsonArray();a.add("file:///different.mrxs");source.add(field,a);}default->{var a=new JsonArray();a.add("wrong");source.add(field,a);}}
                Files.writeString(path,changed.toString());assertThrows(java.io.IOException.class,()->IfHistorySource.validate(run,current,()->false),field);
            }
            Files.writeString(path,original);var bad=JsonParser.parseString(original).getAsJsonObject();bad.addProperty("pixelWidthMicrons",1.5);Files.writeString(path,bad.toString());
            assertThrows(java.io.IOException.class,()->IfHistorySource.validate(run,current,()->false));
            assertEquals(reads,s.reads);
            Path local=temp.resolve("source.bin");Files.writeString(local,"original pixels");
            var localUris=List.of(local.toUri().toString());
            var localCurrent=new IfHistorySource.Current(localUris,c.serverClass(),c.width(),c.height(),c.zSlices(),c.timepoints(),c.channels(),c.pixelWidth(),c.pixelHeight(),c.downsamples());
            var fingerprintManifest=JsonParser.parseString(original).getAsJsonObject();
            var fingerprintSource=fingerprintManifest.getAsJsonObject("source");
            var gson=qupath.lib.io.GsonTools.getInstance();
            fingerprintSource.add("uris",gson.toJsonTree(localUris));
            fingerprintSource.add("fileFingerprint",gson.toJsonTree(IfSourceFingerprint.capture(localUris,()->false)));
            Files.writeString(path,fingerprintManifest.toString());
            assertEquals(32,IfHistorySource.validate(run,localCurrent,()->false).roi().getBoundsWidth());
            Files.writeString(local,"replaced pixels");
            assertThrows(java.io.IOException.class,()->IfHistorySource.validate(run,localCurrent,()->false));
            assertEquals(reads,s.reads);
        }
    }
}
