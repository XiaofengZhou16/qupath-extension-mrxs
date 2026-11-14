package io.github.xiaofengzhou.qupath.mrxs;

import java.util.Locale;

/** Measurement denominator accounting, never a segmentation accuracy score. */
record IfMeasurementCoverage(int proposedCells, int measuredCells, int rejectedCells,
                             Double measuredFraction, String status, String guidance) {
    static IfMeasurementCoverage from(IfAnalysis.Result result) {
        return of(result.inputCells(), result.cells().size(),
                result.excludedCells() + result.unmeasurableCells());
    }

    static IfMeasurementCoverage of(int proposed, int measured, int rejected) {
        if (proposed < 0 || measured < 0 || rejected < 0
                || (long)measured + rejected != proposed)
            throw new IllegalArgumentException("Cell measurement denominator does not reconcile");
        if (proposed == 0)
            return new IfMeasurementCoverage(0, 0, 0, null, "no_cell_candidates",
                    "No cell candidates: cell positivity is unavailable; PA is a separate pixel statistic.");
        return new IfMeasurementCoverage(proposed, measured, rejected, (double)measured / proposed,
                rejected > 0 ? "review_exclusions" : "all_candidates_measurable",
                rejected > 0
                        ? "Review rejected outlines and first-failure reasons before interpreting cell positivity. Rejected cells are not negative cells; selective exclusions can bias results."
                        : "All candidates were measurable; this does not establish segmentation accuracy or staining specificity.");
    }

    String displayText() {
        String coverage = measuredFraction == null ? "N/A" : String.format(Locale.ROOT, "%.1f%%", measuredFraction * 100);
        return String.format(Locale.ROOT,
                "Measurement coverage / 有效测量覆盖: %d / %d (%s); rejected / 排除: %d.\n%s\n"
                        + "NPN/CPN/PN overall denominator / 总体比例分母: eligible measured cells, not all DAPI nuclei / 有效测量细胞，不是全部 DAPI 细胞核。\n"
                        + "Both sampled nucleus and cytoplasm are required, including for NPN / NPN 也要求有效核与胞质。\n"
                        + "Not AI accuracy / 非 AI 准确率。",
                measuredCells, proposedCells, coverage, rejectedCells, guidance);
    }
}
