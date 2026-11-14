# QuPath MRXS extension

An experimental [QuPath](https://qupath.github.io/) 0.7 extension for native,
tile-based access to multichannel fluorescence MRXS whole-slide images.

The extension is intended primarily for macOS and Linux systems where the
vendor viewer is unavailable. It reads supported MRXS files directly and
exposes their fluorescence channels as a non-RGB `UINT8` QuPath
`ImageServer`.

> [!WARNING]
> This is alpha software. Keep the original scanner data and validate image
> appearance and channel identity before using measurements in research.

## Requirements

- QuPath 0.7.x
- An MRXS anchor file together with its same-named companion directory
- Currently supported: 8-bit, JPEG-compressed fluorescence MRXS layouts
  represented by the validation fixtures

Brightfield slides and other MRXS encodings may already work through QuPath's
standard readers. This extension deliberately declines unsupported layouts
instead of silently decoding them incorrectly.

Runtime and release packaging details: [dependencies and distribution](DEPENDENCIES.md).

## Install

1. Download the plugin JAR attached to the release you intend to use (not the
   `-sources.jar`). Check that release's supported QuPath version. The IF features
   described below are currently in `0.5.0-SNAPSHOT`; an older reader-only release
   does not include them. For an unreleased build, follow the build instructions
   below and use the JAR from `build/libs`.
2. In QuPath, open **Extensions → Manage extensions** and drag the JAR into
   the extensions window. Alternatively, copy it into the QuPath extensions
   directory shown by QuPath.
3. Restart QuPath.

Remove older versions of this extension before installing an update.

## Use

1. Keep the `.mrxs` file beside its same-named data directory.
2. Open the `.mrxs` file in QuPath as usual.
3. If QuPath asks for an image type, choose **Fluorescence**.
4. Use **Brightness/Contrast** to enable, recolor, or rescale individual
   channels.

The extension is discovered automatically; it does not add a separate menu
command for opening images.

For a slide currently opened by this extension, choose
**Extensions → MRXS → Show compatibility report**. The report lists format
support findings, channel/storage mappings, pyramid geometry and statistics
from the lowest-resolution layer. An all-zero result applies only to that
sampled layer; it does not prove that the full-resolution channel or biological
stain is absent.

### Validate against another viewer

Choose **Extensions → MRXS → Validation → Export active viewer ROI** to export
the visible source region at the nearest native pyramid level. The output
folder contains:

- one lossless 8-bit grayscale PNG for every raw channel;
- `manifest.json` with source coordinates, native level, downsample, physical
  pixel size, channel statistics and SHA-256 digests;
- `display-composite.png`, which applies the currently selected QuPath channel
  LUTs and ranges for a visual comparison.

The per-channel PNGs are the quantitative reference: no display LUT, gamma,
contrast transform or sharpening is applied. The manifest records the source
filename but deliberately omits the absolute local path.

For pyramid-level comparisons, choose **Extensions → MRXS → Validation →
Export active viewer ROI - all pyramid levels**. This exports the same
source-coordinate region at every native MRXS level into separate folders.
Each level has its own raw-channel PNGs and manifest, while
`pyramid-manifest.json` records the matched geometry and links all outputs.
The command performs size checks before writing any image.

### Export all channel combinations

Select an area annotation and choose **Extensions → MRXS → Export → Export ROI
- all channel combinations**. If no area ROI is selected, the command exports
the visible viewer region. Select the participating channels and either the
current viewer resolution or the highest native resolution.

For channels A, B and C, the command exports `ABC`, `AB`, `AC`, `BC`, `A`, `B`
and `C` as lossless pseudocolor PNGs. It also exports one untransformed
grayscale PNG per channel and a JSON manifest. Combination images use the
current QuPath channel colors, min/max ranges and viewer gamma. Pixels outside
a non-rectangular ROI are black. To avoid accidental multi-gigabyte exports,
the command limits all-combination export to eight selected channels, 25 million
pixels per image and 500 million pixels across all combinations.

### Multiplex IF analysis (development snapshot)

Start at **Extensions → MRXS → IF workflow / IF 工作台** for the color/export,
analysis/batch and history/report windows. The workbench shows the current image
and selected area ROI; repeated clicks reuse an existing window. Analysis, cancel
and batch controls remain visible while settings scroll.

中文日常操作入口：[IF 工作台](docs/IF-WORKFLOW-UI-ZH.md)。
详细参数与结果说明：[IF 工作流指南](docs/IF-QUICKSTART-ZH.md)。

The `0.5.0-SNAPSHOT` build adds **Extensions → MRXS → IF analysis → Multiplex IF
analysis**. This is an experimental analysis workflow, not a validated replacement
for SaiViewer or an AI diagnostic system. The installed/released reader is not
automatically updated by building the snapshot.

1. Select an area annotation in an image with separate fluorescence channels.
2. Choose phenotype markers and raw PA/nuclear/cytoplasmic thresholds. DAPI is
   normally excluded from phenotyping. Defaults are starting values, not validated
   assay cutoffs; determine thresholds with appropriate controls.
3. Use existing QuPath cell objects, **classical nuclear watershed**, or optional
   **InstanSeg AI** (setup below).
   Classical watershed needs calibrated pixel sizes, a nuclear channel, detection threshold,
   nuclear size limits and expansion distance. It is **not AI**; nuclear expansion
   approximates cell boundaries and requires review. Its detection threshold acts
   after background correction, unlike the raw analysis thresholds.
4. Run into a new output folder. Work runs in the background, supports cancellation
   and does not replace existing detections. Use result outlines and phenotype
   filters to inspect inclusive versus exact combinations.
5. Leave **Export PA pixel masks** enabled to retain a lossless, tiled audit of
   pixel positivity. After completion, enable **Show PA pixel masks**, choose a
   marker combination, toggle exact/inclusive and adjust mask opacity. This is
   separate from the PN cell outlines. The "all" option shows any PA-positive
   pixel; pixels negative for every selected marker remain transparent.

PA mask overlays load only visible tiles asynchronously from the completed
export, with SHA-256 verification and a bounded cache (at most 64 MiB or 1/32 of
the heap, whichever is smaller). Zoom in when the visible region exceeds 128
tiles or the cache budget. No original scan reread occurs during this preview.
Overlay colors identify positivity combinations, **not fluorophore display LUTs**.
Source pixels, classifications and stored detections are unchanged.

Outputs include `combinations.csv`, `cells.csv`, `cells.geojson`, `profile.json`,
`segmentation.json`, `roi.json` and `manifest.json`. A remaining `INCOMPLETE` file
means the output must not be used. The profile buttons currently save/load analysis
thresholds only; each completed run additionally records all segmentation parameters.

`area-masks/` contains one shared set of unsigned 16-bit PNG label tiles,
`tiles.jsonl` with level-coordinate origins/dimensions/checksums, and a manifest.
Code 0 is outside the sampled ROI (including holes); code 1 is sampled ROI with
no positive selected marker; all other codes are `1 + positivityBits`. Bit j
refers to `profile.markers[j]`, not source-channel index. Eight positive markers
require code 256, so these files must **not** be converted to 8-bit images.
Coordinates, threshold equality and inclusive/exact formulas are documented in
the mask manifest. Masks are streamed during the analysis pass, without another
source read or one full-slide mask per combination. Export verifies that their
counts agree with PA statistics. Numeric masks are not publication figures.

PA counts threshold-positive pixels; NPN and CPN threshold mean raw nuclear and
cytoplasmic intensities; PN is their per-marker OR, counting each cell once.
Inclusive AB contains ABC; exact AB excludes other **selected** markers. Empty
denominators are blank, not zero. ROI area is not automatically tissue area.
Only cells wholly inside the ROI with sampled nucleus and cytoplasm are eligible.
Neither pixel overlap nor same-cell positivity establishes molecular interaction.

`cells.geojson` contains eligible cell and nuclear geometries with stable IDs and
phenotype masks. Avoid importing duplicate detections for reanalysis. The panel
also supports an **optional** isolated editable review workflow. Enable
**显示人工修正工具（可选）** in the analysis panel to reveal its tools;
automatic analysis does not require this step. Selecting review mode also reveals
the tools.

1. Finish analysis, then click **Create editable review copy…** and confirm.
2. QuPath receives a new review annotation group, with paired **Cell** and
   **Nucleus** annotations. Edit these copies with QuPath's annotation tools;
   retain their pairing metadata. Original detections are unchanged.
3. Select the parent review group and choose **Selected editable IF review group**
   before running analysis again. Only that group's paired objects are measured;
   other groups and original detections are not combined with them.
4. Save the QuPath project/image data to retain edits. Review groups and pairing
   survive `.qpdata` serialization. A review copy is not automatically validated
   ground truth.
5. To add a missed cell, draw separate outer cell and inner nucleus area
   annotations. Multi-select these two annotations and the target review group
   in the hierarchy, then click **Add hand-drawn cell/nucleus pair…**. The dialog
   confirms containment-based pairing before copying them into the group. Both
   must share the group's image plane; the cell must fit inside its ROI. Original
   hand-drawn annotations are retained, and reusing the same source cell annotation
   in the same group is rejected. Reanalyze and save the project afterward.
6. To repair a missing nucleus without duplicating its cell, multi-select the
   review group, its existing paired Cell annotation, and a new hand-drawn nuclear
   annotation. Use **Add missing nucleus to review cell…**. Existing nuclei cannot
   be overwritten; the new nucleus must fit inside that cell on the same plane
   and leave cytoplasm. The original pair ID is retained. Reanalyze afterward;
   adding a nucleus alone does not establish eligibility or marker positivity.

`excluded-cells.csv` lists the first failing eligibility/measurement reason per
excluded object; `excluded-cells.geojson` contains separate review annotations,
not detections. The **Review excluded cells only** toggle shows drawable excluded
outlines as orange dashes. Missing geometry cannot be displayed. Existing-cell
input alone does not establish the upstream segmentation method.

#### Optional AI segmentation

Install the official [InstanSeg extension](https://github.com/qupath/qupath-extension-instanseg)
and DJL extension. In the IF panel select **InstanSeg AI**, choose a trusted local
`fluorescence_nuclei_and_cells` model folder, select AI input channels and CPU or
MPS, then run. Nuclear and cell boundaries are supplied by the model rather than
fixed nuclear expansion. AI input channels are independent of phenotype markers:
include DAPI and useful cell-boundary channels; avoid adding empty/noisy channels
without validation. Only load trusted TorchScript models. DJL can download its
own PyTorch runtime on first use. Images are processed locally.

The model/runtime is **not bundled** in the MRXS JAR. Missing extensions do not
prevent MRXS reading or classical analysis. Current bridge validation uses
InstanSeg 0.1.7, fluorescence model 0.1.1 and QuPath 0.7.0 on Apple Silicon.
AI results record weight/RDF hashes, runtime and extension versions, input channels,
device requested, tile settings, timing and failed tiles. Interrupted, zero-tile
and partially failed inference cannot produce a successful measurement export.
Cells without nuclei or measurable cytoplasm are excluded and reported.

The default build tests synthetic nuclei, compartments, ROI holes, native levels,
inclusive/exact counts, cancellation and output round-trips. Private-slide tests
are opt-in and may be skipped when fixtures are absent. Separate local GUI and
AI checks exercise display controls, analysis, history, export and remeasurement;
a successful default build does not mean those optional checks ran.

These are engineering checks, not independent segmentation or phenotype accuracy
validation. Real research-batch performance, other operating systems and new
scanner layouts need their own validation. Neither vendor algorithm equivalence
nor clinical suitability is established. Historical development notes are not
proof that a particular release passed a check. Consult the test evidence
attached to the specific release.

### Live color workbench and publication export (development snapshot)

Open **Extensions → MRXS → Display → IF color and publication export**.
The workbench is bound to the source image shown in its heading.

- Enable channels, pick colors and adjust min/max sliders or numeric fields;
  gamma, grayscale and inversion are display-only. Changes are debounced to
  avoid unnecessary redraws. Original intensity pixels and IF thresholds stay
  unchanged. Local gamma is restored when that viewer switches to another image.
- Save/load display profiles as JSON. Exact source channel names and indices
  must match; this prevents silently applying a profile to different markers.
- Refresh synchronized merged/single-channel previews for the selected ROI or
  visible region. They share a cached source raster, rendered in a background
  worker. Their caption records the source bounding box and native downsample.
  Refresh after changing ROI. Previews show the bounding box; publication export
  additionally masks outside the annotation.
- In **Publication export**, select an area annotation, native level, 300/600
  DPI, font size and optional calibrated scale bar. Check the image-area pixel
  dimensions, print dimensions, file count and memory estimate before export.
- Export either the selected merge or every nonempty channel combination. Each
  combination produces a `clean.png` and a labeled `figure.png`, plus a shared
  display profile, ROI and manifest with checksums. Output is always a new folder.
- Review `display-qc.txt` (and machine-readable `display-qc.json`) after export.
  It counts raw samples strictly below/above each selected display range inside
  the ROI, with endpoint equality and nonfinite samples reported separately.
  Fractions use finite samples, excluding ROI holes. This is display clipping,
  **not acquisition saturation or staining positivity**; composite RGB clipping
  is not covered. The audit never changes your ranges or exported pixels.

The export reads the native source region once and reuses it across combinations.
LUTs use QuPath's display transforms; gamma is applied consistently. Clean PNGs
are **display composites, not raw intensity data**. Figure labels and scale bars
are placed outside the image in additional margins; the source-derived image
area is copied without resizing or sharpening. DPI is written as PNG `pHYs`
metadata and controls print size only. It cannot add resolution or recover lost
bit depth. Biological scale derives from µm/source pixel and native downsample,
not DPI. Monitor ICC transforms and viewer annotation overlays are not exported.

Use a consistent profile across comparison groups, review clipping/background,
and confirm antibody-to-channel identities before publication. Fluorescence
channel names are not automatically interpreted as antibody identities. If an
export fails/cancels, retain the `INCOMPLETE` marker and do not use that output.

### Display presets

**Show display sampling (Retina diagnosis)** reports logical viewport dimensions,
window render/output scale, the logical-view pyramid level and a finer native
sampling target for a device-resolution renderer. It is read-only and takes no
screenshots. It does **not** enable a new renderer or increase source resolution.
QuPath 0.7.0's main image buffer was verified at logical dimensions even with a
2× window render scale; simply disabling smoothing or changing export DPI does
not remove that limit. The development build now includes an opt-in main-view
integration: **Toggle native HiDPI view (experimental)**.

This mode renders native source tiles at the window's device render scale and
inserts a mouse-transparent image layer below QuPath's navigator/scalebar
controls. It reuses QuPath overlay and ROI-handle drawing APIs. During mouse
press/scroll interactions the stock viewer is shown immediately; the high-detail
layer returns after the view settles. A cancelled/stale frame never replaces a
newer view. The mode closes when the source image changes or the viewer closes.
Toggle again to disable; **Show native HiDPI status** reports dimensions, level,
view geometry and cache/read counts. This mode does not change global smoothing,
gamma, source pixels, detections or publication-export settings.

RGB tile caching is viewer-local and capped at min(64 MiB, heap/32); complete
display profiles and source tile requests are part of the cache key. Only one
render worker and one queued job are retained. Frame/tile limits retain the
stock view if a request is too large or fails. Source frames are reused for
overlay-only repaints. Initial native-level pixel checks, main-view interaction,
stale-read rejection, rotation and cleanup tests pass, but full manual annotation
editing, third-party overlay compatibility and multi-monitor behavior remain
release gates. Treat this as experimental, not a claim of scanner-level or
commercial-viewer equivalence.

Native-zoom commands preserve the image center, including rotated views. PA and
cell-result overlays query the actual source-coordinate graphics clip rather
than assuming QuPath always supplies viewport bounds in its region argument.

The **Extensions → MRXS → Display** menu provides:

- **Raw linear**: channel ranges 0–255 and viewer gamma 1.0;
- **CaseViewer-like percentile preset**: viewer-local percentile contrast for
  a darker, higher-contrast composite;
- **Reset display**: restores the active viewer state captured before the first
  MRXS display preset was applied;
- **Show current channel ranges**: reports the exact viewer gamma and per-channel
  display ranges.
- **High-clarity view**: snaps to the nearest native MRXS pyramid level and
  disables bilinear smoothing so that the viewer does not soften pixel edges;
- **Snap zoom to nearest native pyramid level**: removes fractional zoom
  resampling without changing QuPath's interpolation preference;
- **Restore interpolation preference**: restores the interpolation setting that
  was active before high-clarity mode.

The range, gamma and native-zoom commands change only the active QuPath viewer.
None of the display commands modifies MRXS pixels, exported raw values or
measurements. At startup the extension only warns if the global viewer gamma is
unusually far from 1.0; it never changes that preference.

The interpolation option in the older **High-clarity view** command is a QuPath-wide
viewer preference. The extension records its previous value and restores it
automatically as soon as a non-MRXS image becomes active, preventing the mode
from silently carrying over to SVS, TIFF or other formats. "DPI" settings apply
to saved or printed images; on-screen detail is determined by source pixels,
native pyramid level, zoom and interpolation.

## Implemented capabilities

- Parse `Slidedat.ini` without assuming fixed hierarchy positions.
- Discover zoom, filter, focus and auxiliary hierarchy dimensions.
- Discover fluorescence channel names, colors, filter levels and packed
  component indices.
- Read recursive MIRAX `Index.dat` pages and JPEG payloads from `Data*.dat`.
- Separate packed JPEG components using `STORING_CHANNEL_NUMBER` and
  `DATA_IN_THIS_FILTER_LEVEL`.
- Read the compressed stitching-position buffer.
- Expose the native pyramid as a non-RGB `UINT8` QuPath `ImageServer`.
- Limit tile lookup with a spatial index.
- Bound decoded-image memory with a 128 MB cache.
- Deduplicate concurrent decoding and reuse positional file channels.
- Aggregate compatibility errors and warnings with stable diagnostic codes.
- Report channel minima, maxima, means and non-zero fractions on demand.
- Export reproducible validation ROIs and full pyramid metadata.
- Export selected ROIs as every non-empty channel combination.
- Apply transparent, viewer-local raw and high-contrast display presets.

## Known limitations

- Compatibility has only been validated on a small number of fluorescence
  slides from one workflow.
- Pixel-perfect equivalence with the vendor viewer at every pyramid level has
  not yet been established; use the validation export for matched-region tests.
- Corrupted-record fuzzing and broader scanner/software-version testing remain
  future work.
- Channel metadata and display colors must be checked against the staining
  panel; successful decoding does not establish biological signal.

Please open an issue with non-sensitive metadata and logs for unsupported
files. Do not upload patient data or whole-slide images to a public issue.

## Build and test

QuPath 0.7 requires Java 25.

```bash
JAVA_HOME=/path/to/jdk-25 \
GRADLE_USER_HOME="$PWD/.gradle" \
./gradlew clean test build --no-watch-fs
```

Private-slide integration tests are skipped unless these environment variables
point to readable MRXS anchor files:

```bash
export MRXS_TEST_SAMPLE_4C=/path/to/four-channel-slide.mrxs
export MRXS_TEST_SAMPLE_5C=/path/to/five-channel-slide.mrxs
export MRXS_TEST_SAMPLE_5C_POSITIVE_CY5=/path/to/confirmed-cy5-positive-slide.mrxs
./gradlew test
```

The public test suite creates a small synthetic four-channel MRXS dataset at
runtime. It exercises `Slidedat.ini`, `Index.dat`, packed JPEG payloads, BGR/RGB
component mapping, the QuPath `ImageServer`, validation export and combination
export without distributing real whole-slide images. Private fixtures remain
necessary to validate real scanner layouts and pyramid geometry.

The installable artifact is written to
`build/libs/qupath-extension-mrxs-0.5.0-SNAPSHOT.jar` for the current development
version. The filename follows the version in `build.gradle.kts`.

## Validation

Historical reader validation covers parser, metadata, index, JPEG, multichannel,
builder-selection, spatial-index and concurrent-read checks against private
four- and five-channel fixtures. See [VALIDATION.md](VALIDATION.md) for that
anonymized record; it does not establish current IF accuracy or a rerun on the
current artifact.

## Contributing

Bug reports and focused pull requests are welcome. See
[CONTRIBUTING.md](CONTRIBUTING.md).

This community project is not affiliated with or endorsed by QuPath,
3DHISTECH, CaseViewer, or OpenSlide. MRXS and product names may be trademarks
of their respective owners.

## License

Copyright (C) 2026 contributors.

Licensed under the [GNU General Public License v3.0](LICENSE).
