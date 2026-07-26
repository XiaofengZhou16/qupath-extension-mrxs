package io.github.xiaofengzhou.qupath.mrxs;

import java.io.IOException;

final class MrxsCompatibilityException extends IOException {

    private final MrxsCompatibilityReport report;

    MrxsCompatibilityException(MrxsCompatibilityReport report) {
        super(firstError(report));
        this.report = report;
    }

    MrxsCompatibilityReport report() {
        return report;
    }

    private static String firstError(MrxsCompatibilityReport report) {
        return report.findings().stream()
                .filter(finding ->
                        finding.severity() == MrxsCompatibilityReport.Severity.ERROR)
                .findFirst()
                .map(finding -> finding.code() + ": " + finding.message())
                .orElse("Unsupported MRXS dataset");
    }
}
