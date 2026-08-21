# Changelog

## 0.4.0-alpha — 2026-08-13

- Add an active-viewer validation export with untransformed per-channel PNGs,
  SHA-256 digests and a JSON manifest containing the exact source region,
  native pyramid level, downsample and pixel calibration.
- Add an optional display-composite PNG for visual comparison while preserving
  raw channel exports as the quantitative reference.
- Expand pyramid diagnostics with stored tile dimensions, image counts and
  level-specific physical pixel sizes.
- Add viewer-local raw-linear and CaseViewer-like percentile display presets;
  neither preset changes MRXS pixels or QuPath global preferences.
- Add explicit regression tests that the extension never claims SVS, TIFF,
  OME-TIFF, NDPI or CZI files.
- Add one-click selected-ROI export for every non-empty channel combination,
  with current QuPath colors/ranges/gamma, raw grayscale single channels,
  non-rectangular ROI masking and a JSON manifest.
- Decline parseable MRXS layouts that fail the compatibility assessment instead
  of advertising them to QuPath with a high builder support level.
- Limit combination export to 25 million pixels per image and 500 million
  pixels across all combinations, with overflow-safe size calculation.
- Add a public synthetic four-channel MRXS fixture covering the parser, binary
  index, packed JPEG channel mapping, ImageServer and both export paths.

## 0.3.1-alpha — 2026-07-25

- Correct packed-channel decoding from MIRAX BGR component numbering to
  ImageIO RGB raster-band numbering.
- Add a confirmed CY5-positive five-channel fixture as an optional integration
  test and verify CY5 across every stored pyramid level.
- Correct the earlier validation conclusion that a CY5 layer was all-zero.

## 0.3.0-alpha — 2026-07-25

- Add a structured compatibility report with stable diagnostic codes.
- Check the MRXS anchor, index, data files, bit depth, compression, geometry,
  channel mappings and pyramid metadata before decoding.
- Add an on-demand QuPath compatibility-report command.
- Generate channel statistics off the JavaFX application thread.
- Report per-channel minima, maxima, means and non-zero fractions from the
  lowest-resolution layer.
- Distinguish a decoded all-zero sampled layer from a read failure while
  explicitly avoiding biological signal claims.

## 0.2.0-alpha — 2026-07-25

- Add a spatial index for source-tile lookup.
- Add a bounded decoded-image cache with concurrent decode de-duplication.
- Reuse positional data-file channels.
- Tighten format validation and builder selection.
- Validate deterministic concurrent reads and four-/five-channel fixtures.

This is the first public alpha release.
