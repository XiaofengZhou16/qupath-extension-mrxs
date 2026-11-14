package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.*;
import qupath.lib.io.GsonTools;

/** Original MRXS + exported real-model contours; quantitative replay, not accuracy. */
public class IfRealHistoryRemeasureSmoke {
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("source.mrxs history-run new-output-directory");
        Path historical=Path.of(args[1]),output=Path.of(args[2]);
        var originals=new LinkedHashMap<Path,byte[]>();
        var comparisonTables=new ArrayList<>(List.of("cells.csv","excluded-cells.csv","combinations.csv"));
        for(String name:List.of("nuclei.csv","excluded-nuclei.csv","nuclear-combinations.csv"))
            if(Files.exists(historical.resolve(name)))comparisonTables.add(name);
        for(String name:List.of("manifest.json","roi.json","profile.json","cells.csv","cells.geojson","excluded-cells.csv","excluded-cells.geojson","combinations.csv")) {
            Path p=historical.resolve(name);originals.put(p,Files.readAllBytes(p));
        }
        for(String name:comparisonTables){Path p=historical.resolve(name);originals.putIfAbsent(p,Files.readAllBytes(p));}
        try(var server=new MrxsImageServer(Path.of(args[0]).toUri())) {
            var entry=IfHistorySource.validate(historical,IfHistorySource.capture(server),()->false);
            var cells=new ArrayList<>(IfHistoricalCells.load(entry,()->false).cells());
            for(var rejected:IfHistoricalExclusions.load(entry,()->false))
                cells.add(new IfAnalysis.Cell(rejected.id(),rejected.cell(),rejected.nucleus()));
            if(cells.size()!=entry.proposedCells())throw new AssertionError("Candidate accounting mismatch");
            long started=System.nanoTime();
            var result=IfAnalysisExport.analyzeAndWrite(output,server,entry.roi(),entry.profile(),cells,
                    Map.of("method","Remeasured exported real-model contours; no AI rerun or manual truth","sourceRun",historical.toAbsolutePath().toString()),false,()->false,p->{});
            var checks=new LinkedHashMap<String,Object>();
            for(String name:comparisonTables) {
                var before=IfReportCsv.parse(Files.readString(historical.resolve(name)),200001,64);
                var after=IfReportCsv.parse(Files.readString(output.resolve(name)),200001,64);
                if(!before.getFirst().equals(after.getFirst())||before.size()!=after.size())throw new AssertionError("Table shape changed: "+name);
                var a=new ArrayList<>(before.subList(1,before.size()));var b=new ArrayList<>(after.subList(1,after.size()));
                Comparator<List<String>> order=Comparator.comparing(row->GsonTools.getInstance().toJson(row));
                a.sort(order);b.sort(order);
                if(!a.equals(b))throw new AssertionError("Quantitative table changed after replay: "+name);
                checks.put(name,Map.of("rows",a.size(),"allFieldsExactlyMatchIgnoringRowOrder",true));
            }
            for(var original:originals.entrySet())if(!Arrays.equals(original.getValue(),Files.readAllBytes(original.getKey())))throw new AssertionError("Historical file changed");
            var report=new LinkedHashMap<String,Object>();
            report.put("software",IfSoftwareProvenance.snapshot());report.put("sourceRun",historical.toAbsolutePath().toString());
            report.put("proposedCells",result.inputCells());report.put("measuredCells",result.cells().size());
            var nuclear=result.nuclearMeasurements();
            if(nuclear.nuclei().size()+nuclear.rejected().size()!=result.inputCells())throw new AssertionError("Nuclear accounting mismatch");
            var nucleiById=nuclear.nuclei().stream().collect(java.util.stream.Collectors.toMap(IfNuclearMeasurements.Measurement::id,n->n));
            for(var cell:result.cells()) {
                var n=nucleiById.get(cell.id());
                if(n==null||n.pixels()!=cell.nucleusPixels()||!n.means().equals(cell.nucleusMeans())||n.mask()!=cell.npn())
                    throw new AssertionError("Shared nuclear measurements differ from legacy values");
            }
            report.put("nuclearMeasurements",Map.of("measured",nuclear.nuclei().size(),"excluded",nuclear.rejected().size(),
                    "allLegacyNuclearMeansAndMasksExactlyMatch",true,"accountingMatchesInput",true));
            report.put("tables",checks);report.put("secondsIncludingExportAndComparison",(System.nanoTime()-started)/1e9);
            report.put("originalHistoryUnchanged",true);report.put("screenshots",false);
            report.put("limitations","One real-source ROI and existing model contours. Same raw thresholds; row order normalized, numerical CSV strings compared exactly. No model inference rerun, accuracy, manual editing or whole-slide performance claim.");
            Files.writeString(output.resolve("remeasure-verification.json"),GsonTools.getInstance(true).toJson(report));
            System.out.println("IF_REAL_HISTORY_REMEASURE_OK "+GsonTools.getInstance().toJson(report));
        }
    }
}
