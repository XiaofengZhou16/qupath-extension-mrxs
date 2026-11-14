package io.github.xiaofengzhou.qupath.mrxs;
import java.nio.file.Path;
public class IfOfficeReportSmoke {
    public static void main(String[] args) throws Exception {
        var catalog=IfAnalysisHistory.scan(Path.of(args[0]),()->false);
        if(catalog.entries().size()!=Integer.parseInt(args[2]))throw new IllegalStateException("Run count mismatch");
        IfOfficeReport.write(catalog.entries().stream().map(IfAnalysisHistory.Entry::directory).toList(),Path.of(args[1]),()->false);
        System.out.println("IF_OFFICE_REPORT_OK runs="+catalog.entries().size());
    }
}
