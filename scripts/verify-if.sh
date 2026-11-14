#!/usr/bin/env bash
# Explicit, local validation only. Never installs, captures screens, or publishes.
set -euo pipefail

if [[ $# -ne 3 ]]; then
    echo 'Usage: JAVA_HOME=/path/to/jdk bash scripts/verify-if.sh core|gui|history|ai|ai-gui|ai-multi /path/to/demo.mrxs /absolute/new-output-directory' >&2
    echo 'Optional: MRXS_QUPATH_APP; ai requires MRXS_AI_MODEL, MRXS_INSTANSEG_JARS, DJL_CACHE_DIR (already populated).' >&2
    exit 2
fi
suite=$1
slide=$2
output=$3
case "$suite" in core|gui|history|ai|ai-gui|ai-multi) ;; *) echo 'Unknown suite' >&2; exit 2;; esac
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
qupath_app=${MRXS_QUPATH_APP:-/Applications/QuPath-0.7.0-arm64.app}
java_bin=${JAVA_HOME:?Set JAVA_HOME to the configured JDK}/bin/java
[[ -x "$java_bin" && -f "$slide" && -d "$qupath_app/Contents/app" ]] || { echo 'Missing Java, slide or QuPath runtime' >&2; exit 2; }
[[ "$slide" = /* && "$output" = /* && ! -e "$output" ]] || { echo 'Use absolute paths and a new, nonexistent output directory' >&2; exit 2; }
archive=$(awk -F/ '/^distributionUrl=/ {print $NF}' "$repo/gradle/wrapper/gradle-wrapper.properties")
distribution=${archive%.zip}
gradle_directory=${distribution%-bin}
gradle_directory=${gradle_directory%-all}
cached=false
for marker in "$repo/.gradle/wrapper/dists/$distribution"/*/"$archive.ok"; do
    if [[ -f "$marker" && -x "$(dirname "$marker")/$gradle_directory/bin/gradle" ]]; then cached=true; break; fi
done
[[ "$cached" == true ]] || { echo 'Gradle wrapper distribution is not cached in the project. Populate it separately before this offline suite.' >&2; exit 2; }
if [[ "$suite" == ai || "$suite" == ai-gui || "$suite" == ai-multi ]]; then
    [[ -f "${MRXS_AI_MODEL:?Set trusted model directory}/instanseg.pt" && -f "$MRXS_AI_MODEL/rdf.yaml" ]] || exit 2
    [[ -d "${MRXS_INSTANSEG_JARS:?Set installed InstanSeg main-jar directory}" && -d "${DJL_CACHE_DIR:?Set populated DJL cache}" ]] || exit 2
fi
mkdir "$output"
cd "$repo"
# Pin the fixture explicitly; do not silently run only synthetic tests.
# Wrapper bootstrap reads the environment before Gradle can parse CLI options.
MRXS_TEST_SAMPLE_4C="$slide" GRADLE_USER_HOME="$repo/.gradle" ./gradlew test build --offline --no-watch-fs 2>&1 | tee "$output/build.log"
version=$(awk -F '"' '$1 ~ /^[[:space:]]*version = / {print $2}' build.gradle.kts)
jar="$repo/build/libs/qupath-extension-mrxs-$version.jar"
[[ -f "$jar" ]] || { echo 'Expected current-version JAR missing' >&2; exit 2; }
cp_path="$jar:$repo/build/classes/java/test:$qupath_app/Contents/app/*"
java_flags=(--enable-native-access=ALL-UNNAMED -Xmx3g)
if [[ "$suite" != gui && "$suite" != history && "$suite" != ai-gui && "$suite" != ai-multi ]]; then java_flags+=(-Djava.awt.headless=true); fi
if [[ "$suite" == ai || "$suite" == ai-gui || "$suite" == ai-multi ]]; then
    cp_path="$cp_path:$MRXS_INSTANSEG_JARS/*"
    java_flags+=(-Dai.djl.pytorch.graph_optimizer=false)
fi
run_smoke() {
    local label=$1 class=$2
    shift 2
    "$java_bin" "${java_flags[@]}" -cp "$cp_path" "io.github.xiaofengzhou.qupath.mrxs.$class" "$@" 2>&1 | tee "$output/$label.log"
}
case "$suite" in
    core)
        run_smoke allocation IfAnalysisAllocationSmoke "$slide" "$output/allocation"
        run_smoke whole-extent IfWholeSlideSmoke "$slide" "$output/whole-extent"
        run_smoke concurrent IfConcurrentReadSmoke "$slide" "$output/concurrent"
        ;;
    gui)
        run_smoke history-worker IfHistoryWorkerSmoke
        run_smoke display IfDisplayPanelSmoke "$slide" "$output/prefs-display"
        for mode in workload repair cancel responsiveness; do
            run_smoke "$mode" IfAnalysisRunSmoke "$slide" "$output/prefs-$mode" "$output/$mode" "$mode"
        done
        run_smoke history IfHistoryGuiSmoke "$output/responsiveness" "$output/prefs-history" 5
        run_smoke history-report IfHistoryReportGuiSmoke "$output/responsiveness" "$output/history-report"
        run_smoke publication IfPublicationGuiSmoke "$slide" "$output/prefs-publication" "$output/publication"
        run_smoke hidpi MrxsHiDpiGuiSmoke "$slide" "$output/prefs-hidpi" "$output/hidpi"
        run_smoke review-scale IfReviewScaleGuiSmoke 20000 "$output/review-scale"
        ;;
    history)
        run_smoke fixture IfHistoricalCellsSmoke "$slide" "$output/fixture" all
        run_smoke all-overlays-switch IfHistoryLocateGuiSmoke "$slide" "$output/fixture" "$output/prefs-switch" all
        run_smoke all-overlays-close IfHistoryLocateGuiSmoke "$slide" "$output/fixture" "$output/prefs-close" all close
        run_smoke review-copy IfHistoryReviewGuiSmoke "$slide" "$output/fixture" "$output/prefs-review"
        run_smoke review-roundtrip IfHistoricalReviewRoundtripSmoke "$slide" "$output/fixture" "$output/roundtrip"
        run_smoke project-review-gui IfProjectReviewGuiSmoke "$output/roundtrip/project/project.qpproj" "$output/roundtrip/after-reload" "$output/project-review-gui"
        ;;
    ai)
        run_smoke ai-concurrent IfConcurrentReadSmoke "$slide" "$output/ai-concurrent" "$MRXS_AI_MODEL"
        ;;
    ai-multi)
        # Nine sequential analyses in one reader/GUI session, then independent replay.
        java_flags+=(-Dif.smoke.multiRoi=true -Dif.smoke.roiCycles=3 -Dif.smoke.roi=32768,81920,2048)
        run_smoke ai-multi IfAnalysisRunSmoke "$slide" "$output/prefs-ai" "$output/ai-multi" ai-responsiveness "$MRXS_AI_MODEL"
        shopt -s nullglob
        ai_runs=("$output/ai-multi"/if-analysis-*)
        [[ ${#ai_runs[@]} -eq 9 ]] || { echo 'Expected nine completed AI runs' >&2; exit 2; }
        for index in "${!ai_runs[@]}"; do
            run_smoke "replay-$index" IfRealHistoryRemeasureSmoke "$slide" "${ai_runs[$index]}" "$output/replay-$index"
        done
        ;;
    ai-gui)
        run_smoke ai-responsiveness IfAnalysisRunSmoke "$slide" "$output/prefs-ai" "$output/ai-responsiveness" ai-responsiveness "$MRXS_AI_MODEL"
        shopt -s nullglob
        ai_runs=("$output/ai-responsiveness"/if-analysis-*)
        [[ ${#ai_runs[@]} -eq 1 && -d "${ai_runs[0]}" ]] || { echo 'Expected exactly one AI analysis run for quantitative replay' >&2; exit 2; }
        run_smoke ai-remeasure IfRealHistoryRemeasureSmoke "$slide" "${ai_runs[0]}" "$output/ai-remeasure"
        ;;
esac
shasum -a 256 "$jar" | tee "$output/jar-sha256.txt"
echo "Suite completed: $suite. Evidence: $output. Review test limitations; success is not biological validation."
