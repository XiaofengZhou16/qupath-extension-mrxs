package io.github.xiaofengzhou.qupath.mrxs;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class IfAiSettingsProfileTest {
    @TempDir Path temp;
    @Test void rejectsUnrepresentableChannelOrder() {
        assertThrows(IllegalArgumentException.class,()->new IfAiSettingsProfile(1,IfAiSettingsProfile.KIND,
                "cpu",2,512,32,List.of("DAPI","A","B"),List.of(2,0)));
    }
    @Test void deserializationEnforcesValidation()throws Exception {
        var valid=new IfAiSettingsProfile(1,IfAiSettingsProfile.KIND,"cpu",2,512,32,List.of("DAPI","A","B"),List.of(0,2));
        var json=qupath.lib.io.GsonTools.getInstance().toJsonTree(valid).getAsJsonObject();
        Path file=temp.resolve("invalid.json");
        for(String key:List.of("schemaVersion","threads","tileSize","padding")) {
            var invalid=json.deepCopy();invalid.addProperty(key,-1);
            Files.writeString(file,invalid.toString());
            assertThrows(RuntimeException.class,()->IfAiSettingsProfile.read(file),key);
        }
        var reversed=json.deepCopy();var channels=new com.google.gson.JsonArray();channels.add(2);channels.add(0);
        reversed.add("inputChannels",channels);Files.writeString(file,reversed.toString());
        assertThrows(RuntimeException.class,()->IfAiSettingsProfile.read(file));
        Files.write(file,new byte[]{(byte)0xC3,(byte)0x28});
        assertThrows(java.io.IOException.class,()->IfAiSettingsProfile.read(file));
    }
    @Test void roundtripRejectsMismatchAndDoesNotStoreTrust()throws Exception {
        var p=new IfAiSettingsProfile(1,IfAiSettingsProfile.KIND,"mps",1,512,80,List.of("DAPI","A","B"),List.of(0,2));
        String json=qupath.lib.io.GsonTools.getInstance().toJson(p);Path file=temp.resolve("ai.json");Files.writeString(file,json);
        assertEquals(p,IfAiSettingsProfile.read(file));assertFalse(json.contains("trust"));assertFalse(json.contains("modelPath"));
        p.validateChannels(List.of("DAPI","A","B"));
        assertThrows(IllegalArgumentException.class,()->p.validateChannels(List.of("DAPI","B","A")));
        assertThrows(IllegalArgumentException.class,()->new IfAiSettingsProfile(1,"wrong","mps",1,512,80,List.of("DAPI"),List.of(0)));
        assertThrows(IllegalArgumentException.class,()->new IfAiSettingsProfile(1,IfAiSettingsProfile.KIND,"mps",1,512,80,List.of("DAPI"),List.of(1)));
        Files.writeString(file," ".repeat(65537));assertThrows(java.io.IOException.class,()->IfAiSettingsProfile.read(file));
        Files.writeString(file,"null");assertThrows(java.io.IOException.class,()->IfAiSettingsProfile.read(file));
    }
}
