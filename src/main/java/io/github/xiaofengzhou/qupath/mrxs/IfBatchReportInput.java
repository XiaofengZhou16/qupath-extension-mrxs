package io.github.xiaofengzhou.qupath.mrxs;

import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.*;
import java.math.BigDecimal;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CancellationException;
import java.security.MessageDigest;
import qupath.lib.io.GsonTools;

/** Fresh read-only report snapshots. Never pools counts from different ROIs. */
final class IfBatchReportInput {
    static final List<String> HEADER=List.of("combination","mask","mode","metric","numerator","denominator_type",
            "denominator","ratio","density_per_mm2","positive_area_mm2");
    record Run(IfAnalysisHistory.Entry entry,List<List<String>> rows,String manifestSha256,
               String combinationsSha256,String profileSha256,String nuclearCombinationsSha256,long measuredNuclei) {}
    record Batch(List<Run> runs,int profileGroups,int tableRows,List<String> warnings) {}

    static Batch load(List<Path> selected,BooleanSupplier cancelled) throws IOException {
        if(selected.isEmpty() || selected.size()>1000)throw new IOException("Select 1–1000 analysis runs");
        var paths=new HashSet<Path>();var profiles=new HashSet<String>();var runs=new ArrayList<Run>();
        int totalRows=0;long totalBytes=0;
        for(Path requested:selected) {
            check(cancelled);Path path=requested.toRealPath();
            if(!paths.add(path))throw new IOException("Duplicate analysis run: "+path);
            var catalog=IfAnalysisHistory.scan(path,cancelled);
            if(catalog.entries().size()!=1 || !catalog.rejected().isEmpty()
                    || !catalog.entries().getFirst().directory().equals(path))
                throw new IOException("Select a structurally complete run, not a parent folder: "+path);
            var entry=catalog.entries().getFirst();
            byte[] manifest=read(path.resolve("manifest.json"),2*1024*1024);
            var metadata=com.google.gson.JsonParser.parseString(new String(manifest,StandardCharsets.UTF_8)).getAsJsonObject();
            if(!entry.profile().equals(GsonTools.getInstance().fromJson(metadata.get("profile"),IfAnalysisProfile.class))
                    ||metadata.get("inputCells").getAsInt()!=entry.proposedCells()
                    ||metadata.get("eligibleCells").getAsInt()!=entry.measuredCells()
                    ||!metadata.get("imageName").getAsString().equals(entry.imageName())
                    ||!metadata.get("createdAt").getAsString().equals(entry.createdAt()))
                throw new IOException("Run metadata changed during selection: "+path);
            byte[] bytes=read(path.resolve("combinations.csv"),32*1024*1024);
            totalBytes+=bytes.length;if(totalBytes>128L*1024*1024)throw new IOException("Batch CSV memory limit exceeded");
            var rows=IfReportCsv.parse(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(),200001,10);
            if(rows.isEmpty() || !rows.getFirst().equals(HEADER))throw new IOException("Unsupported combination CSV header: "+path);
            rows=List.copyOf(rows.subList(1,rows.size()));
            validate(rows,entry,cancelled,-1);
            byte[] nuclearBytes=null;long measuredNuclei=-1;
            if(metadata.has("nuclearMeasurements")) {
                try {
                    var n=metadata.getAsJsonObject("nuclearMeasurements");
                    if(n.get("schemaVersion").getAsInt()!=1||!n.get("algorithm").getAsString().equals("raw-nucleus-only-v1")
                            ||!n.get("denominator").getAsString().equals("all_measured_nuclei"))throw new IOException("Unsupported nuclear supplement");
                    measuredNuclei=integer(n.get("measuredNuclei").getAsString());
                    long excluded=integer(n.get("excludedNuclei").getAsString());
                    if(measuredNuclei>entry.proposedCells()||excluded!=entry.proposedCells()-measuredNuclei)throw new IOException("Nuclear population mismatch");
                }catch(RuntimeException e){throw new IOException("Malformed nuclear supplement metadata",e);}
                nuclearBytes=read(path.resolve("nuclear-combinations.csv"),32*1024*1024);
                totalBytes+=nuclearBytes.length;if(totalBytes>128L*1024*1024)throw new IOException("Batch CSV memory limit exceeded");
                var nuclearRows=IfReportCsv.parse(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(nuclearBytes)).toString(),200001,10);
                if(nuclearRows.isEmpty()||!nuclearRows.getFirst().equals(HEADER))throw new IOException("Unsupported nuclear CSV header");
                nuclearRows=List.copyOf(nuclearRows.subList(1,nuclearRows.size()));
                validate(nuclearRows,entry,cancelled,measuredNuclei);
                var combined=new ArrayList<>(rows);combined.addAll(nuclearRows);rows=List.copyOf(combined);
            }
            totalRows+=rows.size();if(totalRows>200000)throw new IOException("Batch exceeds 200000 table rows");
            // Detect edits during preparation; a later report must use these immutable snapshots.
            if(!Arrays.equals(manifest,read(path.resolve("manifest.json"),2*1024*1024))
                    || !Arrays.equals(bytes,read(path.resolve("combinations.csv"),32*1024*1024))
                    || (nuclearBytes!=null&&!Arrays.equals(nuclearBytes,read(path.resolve("nuclear-combinations.csv"),32*1024*1024))))
                throw new IOException("Run changed during report preparation: "+path);
            String profileHash=hash(GsonTools.getInstance().toJson(entry.profile()).getBytes(StandardCharsets.UTF_8));
            profiles.add(profileHash);runs.add(new Run(entry,rows,hash(manifest),hash(bytes),profileHash,
                    nuclearBytes==null?"":hash(nuclearBytes),measuredNuclei));
        }
        return new Batch(List.copyOf(runs),profiles.size(),totalRows,List.of(
                "Rows remain separate by source run/ROI; no biological replicate or pooled estimate is inferred.",
                "Profile groups: "+profiles.size()+". Equal thresholds alone do not establish cross-slide comparability.",
                "Source paths/metadata and exported-file hashes do not verify original slide pixel identity.",
                "NPN/CPN/PN use eligible measured cells, not necessarily all DAPI nuclei; rejected cells are not negative."));
    }

    private static void validate(List<List<String>> rows,IfAnalysisHistory.Entry entry,BooleanSupplier cancelled,long nuclearPopulation) throws IOException {
        boolean nuclear=nuclearPopulation>=0;
        int channels=entry.profile().markers().size(),maxMask=(1<<channels)-1;
        var keys=new HashSet<String>();
        for(var row:rows) {
            check(cancelled);
            if(row.size()!=10)throw new IOException("Combination CSV row must have 10 fields");
            long mask=integer(row.get(1)),numerator=integer(row.get(4)),denominator=integer(row.get(6));
            if(mask<1 || mask>maxMask || numerator>denominator)throw new IOException("Invalid combination mask/counts");
            if(!Set.of("inclusive","exact").contains(row.get(2)) || !(nuclear?Set.of("NPN_NUCLEUS_ONLY"):Set.of("PA","NPN","CPN","PN")).contains(row.get(3)))
                throw new IOException("Unsupported combination mode/metric");
            var names=new ArrayList<String>();var allowed=new HashSet<String>();
            allowed.add(nuclear?"all_measured_nuclei":row.get(3).equals("PA")?"all_sampled_roi_pixels":"all_eligible_cells");
            for(int c=0;c<channels;c++)if((mask&(1<<c))!=0) {
                String name=entry.profile().markers().get(c).name();names.add(name);allowed.add("positive:"+name);
            }
            String label=String.join("+",names);
            if(!label.isEmpty() && "=+-@\t\r".indexOf(label.charAt(0))>=0)label="'"+label; // exporter CSV text protection
            if(!row.get(0).equals(label) || !allowed.contains(row.get(5)))throw new IOException("Channel/profile/denominator mismatch");
            String key=mask+"/"+row.get(2)+"/"+row.get(3)+"/"+row.get(5);
            if(!keys.add(key))throw new IOException("Duplicate combination row");
            if(denominator==0 && !row.get(7).isEmpty())throw new IOException("Undefined ratio must remain blank");
            if(denominator>0) {
                double ratio=number(row.get(7));
                if(Math.abs(ratio-(double)numerator/denominator)>1e-10)throw new IOException("Ratio does not match counts");
            }
            for(int i=8;i<10;i++)if(!row.get(i).isEmpty() && number(row.get(i))<0)throw new IOException("Negative physical measurement");
            if(row.get(5).equals("all_eligible_cells") && denominator!=entry.measuredCells())throw new IOException("Eligible-cell denominator mismatch");
            if(nuclear&&(denominator>nuclearPopulation||(!row.get(9).isEmpty())
                    ||(row.get(5).equals("all_measured_nuclei")&&denominator!=nuclearPopulation)))throw new IOException("Nuclear denominator/area mismatch");
        }
        int expected=0;for(int mask=1;mask<=maxMask;mask++)expected+=(nuclear?2:8)*(1+Integer.bitCount(mask));
        if(rows.size()!=expected)throw new IOException("Incomplete combination table: expected "+expected+", got "+rows.size());
        if(nuclear) {
            long[] exact=new long[maxMask+1];var inclusive=new HashMap<Long,Long>();
            for(var row:rows)if(row.get(5).equals("all_measured_nuclei")) {
                if(row.get(2).equals("exact"))exact[(int)integer(row.get(1))]=integer(row.get(4));
                else inclusive.put(integer(row.get(1)),integer(row.get(4)));
            }
            long used=0;for(long count:exact){if(count>nuclearPopulation-used)throw new IOException("Exact nuclear counts exceed population");used+=count;}
            var sums=IfAnalysis.inclusive(exact);
            for(var row:rows) {
                int mask=(int)integer(row.get(1));
                if(integer(row.get(4))!=(row.get(2).equals("exact")?exact[mask]:sums[mask]))throw new IOException("Inconsistent nuclear counts");
                for(int c=0;c<channels;c++)if(row.get(5).equals("positive:"+entry.profile().markers().get(c).name())
                        &&integer(row.get(6))!=inclusive.get((long)(1<<c)))throw new IOException("Conditional nuclear denominator mismatch");
            }
        }
    }
    private static long integer(String value) throws IOException {
        try {long result=new BigDecimal(value).longValueExact();if(result<0)throw new ArithmeticException();return result;}
        catch(RuntimeException e){throw new IOException("Invalid nonnegative integer",e);}
    }
    private static double number(String value) throws IOException {
        try {double result=new BigDecimal(value).doubleValue();if(!Double.isFinite(result))throw new ArithmeticException();return result;}
        catch(RuntimeException e){throw new IOException("Invalid finite numeric value",e);}
    }
    private static byte[] read(Path path,int limit) throws IOException {
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>limit)throw new IOException("Missing, linked or oversized report input: "+path);
        try(var input=Files.newInputStream(path)){byte[] bytes=input.readNBytes(limit+1);if(bytes.length>limit)throw new IOException("Report input grew beyond limit");return bytes;}
    }
    private static String hash(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean())throw new CancellationException();}
}
