# Changelog

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
