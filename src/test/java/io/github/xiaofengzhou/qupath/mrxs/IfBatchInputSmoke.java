package io.github.xiaofengzhou.qupath.mrxs;

/** Read-only preparation of real historical outputs; no report files written. */
public class IfBatchInputSmoke {
    public static void main(String[] args)throws Exception {
        var entries=IfAnalysisHistory.scan(java.nio.file.Path.of(args[0]),()->false).entries();
        var batch=IfBatchReportInput.load(entries.stream().map(IfAnalysisHistory.Entry::directory).toList(),()->false);
        if(batch.runs().size()!=Integer.parseInt(args[1]))throw new IllegalStateException("Unexpected run count");
        System.out.println("IF_BATCH_INPUT_OK runs="+batch.runs().size()+" rows="+batch.tableRows()+" profileGroups="+batch.profileGroups());
        batch.warnings().forEach(System.out::println);
    }
}
