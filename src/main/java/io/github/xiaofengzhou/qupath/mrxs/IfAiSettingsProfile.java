package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.List;
import qupath.lib.io.GsonTools;

/** Portable settings only: never stores a model path, trust decision or executable content. */
record IfAiSettingsProfile(int schemaVersion,String kind,String device,int threads,int tileSize,int padding,
                           List<String> sourceChannels,List<Integer> inputChannels) {
    static final String KIND="mrxs-if-ai-settings";
    IfAiSettingsProfile {
        if(schemaVersion!=1||!KIND.equals(kind)||sourceChannels==null||sourceChannels.isEmpty()||sourceChannels.size()>64
                ||sourceChannels.stream().anyMatch(s->s==null||s.isBlank()||s.length()>256))throw new IllegalArgumentException("Invalid AI settings profile");
        sourceChannels=List.copyOf(sourceChannels);
        var validated=new IfInstanSeg.Config(Path.of("."),device,threads,tileSize,padding,inputChannels);
        inputChannels=validated.inputChannels();
        // The checkbox UI always supplies channels in source order. Reject profiles it
        // cannot represent rather than silently changing the model's input ordering.
        for(int i=1;i<inputChannels.size();i++)
            if(inputChannels.get(i)<=inputChannels.get(i-1))
                throw new IllegalArgumentException("AI input channels must follow source channel order; settings not applied");
        int channelCount=sourceChannels.size();
        if(inputChannels.stream().anyMatch(i->i>=channelCount))throw new IllegalArgumentException("AI channel index outside saved mapping");
        if(!List.of(16,32,48,64,80,96).contains(padding))throw new IllegalArgumentException("Unsupported UI padding choice");
    }
    void validateChannels(List<String> channels){
        if(!sourceChannels.equals(channels))throw new IllegalArgumentException("AI profile channel order/names differ from this image; settings not applied");
    }
    static IfAiSettingsProfile read(Path path)throws java.io.IOException {
        byte[] bytes;try(var in=Files.newInputStream(path)){bytes=in.readNBytes(65_537);}
        if(bytes.length>65_536)throw new java.io.IOException("AI profile exceeds 64 KiB");
        var profile=GsonTools.getInstance().fromJson(java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString(),IfAiSettingsProfile.class);
        if(profile==null)throw new java.io.IOException("Empty AI profile");return profile;
    }
}
