package io.github.xiaofengzhou.qupath.mrxs;

/** Opt-in read-only scan of existing private analysis outputs. */
public class IfHistorySmoke {
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("parent-folder expected-complete-runs");
        var catalog=IfAnalysisHistory.scan(java.nio.file.Path.of(args[0]),()->false);
        if(catalog.entries().size()!=Integer.parseInt(args[1]))throw new IllegalStateException(IfAnalysisHistory.format(catalog));
        System.out.println(IfAnalysisHistory.format(catalog));
        System.out.println("IF_HISTORY_OK complete="+catalog.entries().size()+" skipped="+catalog.rejected().size());
    }
}
