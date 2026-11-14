package io.github.xiaofengzhou.qupath.mrxs;

import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import qupath.lib.io.GsonTools;

/** Decode embedded payloads before placement/LUT; bounded sampling, never screen capture. */
public class MrxsStoredPyramidAudit {
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("slide new-output-directory");
        Path out=Path.of(args[1]);Files.createDirectory(out);
        var metadata=SlidedatParser.parse(Path.of(args[0]));
        var reports=new ArrayList<Map<String,Object>>();
        try(var index=MiraxIndex.open(metadata)) {
            for(int level=0;level<metadata.zoomLevels().size();level++)for(var channel:metadata.channels()) {
                var entries=index.readTiles(level,channel.filterLevelIndex());
                int n=Math.min(entries.size(),level>=6?256:8);
                long[] histogram=new long[256];
                var hashes=new TreeSet<String>();
                for(int p=0;p<n;p++) {
                    var entry=entries.get(n==1?0:(int)((long)p*(entries.size()-1)/(n-1)));
                    byte[] payload=index.readPayload(entry);
                    hashes.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)));
                    var image=ImageIO.read(new ByteArrayInputStream(payload));
                    if(image==null)throw new IllegalStateException("Undecodable payload");
                    int band=MrxsImageServer.sourceBandForStoredComponent(channel.storedComponent(),image.getRaster().getNumBands());
                    for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)
                        histogram[image.getRaster().getSample(x,y,band)]++;
                    if(p==0 && level>=6)Files.write(out.resolve("level-"+level+"-channel-"+channel.index()+".payload"),payload);
                }
                var report=new LinkedHashMap<String,Object>();
                report.put("level",level);report.put("channel",channel.name());report.put("filter",channel.filterLevelIndex());
                report.put("storedComponent",channel.storedComponent());report.put("storedEntries",entries.size());
                report.put("sampledEntries",n);report.put("sampledPayloadHashes",hashes);report.put("histogram",histogram);
                reports.add(report);
                System.out.printf(Locale.ROOT,"RAW_PYRAMID level=%d channel=%s entries=%d sampled=%d zero=%d gray128=%d other=%d%n",
                        level,channel.name(),entries.size(),n,histogram[0],histogram[128],Arrays.stream(histogram).sum()-histogram[0]-histogram[128]);
            }
        }
        Files.writeString(out.resolve("stored-pyramid.json"),GsonTools.getInstance(true).toJson(reports));
    }
}
