# Validation record

Date: 2026-08-13

## Automated tests

The clean test/build completed successfully with Temurin JDK 25 and Gradle
9.2.1.

### Four-channel private fixture

- 9 pyramid resolutions
- channels: DAPI, SPorange, SpGreen, CY5
- storage levels 0 and 1 each contain 21 JPEG records at zoom level 7
- Java ImageIO decodes the probed JPEG payloads
- four-band lowest-resolution QuPath region read succeeds
- observed channel maxima: `[128, 128, 128, 128]`

The CY5 maximum proves only that the stored band can be decoded. It does not
prove specific biological staining.

### Five-channel private fixture

- 10 pyramid resolutions
- channels: DAPI, SpGreen, SpOrange, CY5, SpAqua
- five-band lowest-resolution QuPath region read succeeds
- corrected observed channel maxima: `[17, 22, 66, 121, 35]`

An earlier build reported `[66, 22, 17, 0, 35]` because it applied MIRAX BGR
component numbers directly to ImageIO RGB raster bands. That result was a
decoder mapping error and must not be interpreted as evidence that CY5 staining
was absent.

The fixtures are not distributed because whole-slide images may contain
sensitive information and are too large for source control.

## QuPath 0.7 integration

QuPath discovered the builder, constructed the custom `ImageServer`, prompted
for image type and loaded the four-channel fixture. The viewer exposed a
10 mm scale bar and no residual modal sheet.

## Interpretation boundary

This validates discovery, metadata/channel exposure, indexed payload reading,
low-resolution stitching and QuPath integration. It does not yet establish
pixel-perfect equivalence to the vendor viewer at every pyramid level.

## Version 0.2 performance and concurrency

- Spatial-index test: 9 candidates from 6,592 level-0 records for one
  512 × 512 request.
- Twelve concurrent reads of the same region produced identical four-band
  checksums.
- The concurrent test retained 40 decoded images (9,400,320 bytes) within the
  128 MB cache limit.
- Three `DataNNNN.dat` file channels were reused for the test.
- Unrelated files receive support level 0, so the builder does not claim them.

## Version 0.3 diagnostics

- Compatibility assessment aggregates errors and warnings rather than stopping
  at the first unsupported property.
- Public unit tests cover supported metadata and simultaneous bit-depth,
  compression and missing-data-file errors without private fixtures.
- Both private fixtures pass the compatibility assessment.
- Lowest-resolution channel statistics distinguish sampled pixel values from
  decoding failures without making biological staining claims.
- The diagnostic wording explicitly limits signal conclusions to the sampled
  pyramid level.

## Version 0.3.1 channel-order correction

- MIRAX packed components are numbered in BGR order, while Java ImageIO exposes
  decoded JPEG raster bands in RGB order.
- The corrected mapping is `0 → 2`, `1 → 1`, `2 → 0`.
- A confirmed CY5-positive five-channel fixture contains CY5 data at all ten
  stored pyramid levels.
- In that positive fixture, the decoded CY5 maximum is 255 in the
  full-resolution stored tiles and 106 at the rendered lowest-resolution
  layer.

## Version 0.4 validation and display controls

- Three private fixtures pass the complete integration suite, including a
  confirmed CY5-positive five-channel fixture.
- Validation export writes lossless per-channel PNGs and verifies sampled PNG
  values against the corresponding non-RGB QuPath image bands.
- The JSON manifest records the exact region, nearest native pyramid level,
  downsample, physical calibration, display snapshot and SHA-256 digests while
  omitting absolute local paths.
- Pyramid diagnostics report geometry downsample and physical pixel-size
  ratios separately. A private four-channel fixture contains a small declared
  calibration/geometry difference; it is surfaced as
  `PYRAMID_PIXEL_SIZE_MISMATCH` rather than silently normalized.
- Explicit builder tests confirm that SVS, TIFF, OME-TIFF, NDPI and CZI receive
  support level zero and cannot be opened by this extension.
- Raw-linear, CaseViewer-like and reset commands are viewer-local. The
  extension neither changes raw pixels nor writes QuPath's global gamma.
- CaseViewer pixel equivalence remains unproven until the same slide, source
  region, physical scale and unenhanced vendor export are available.
- Three-channel combination enumeration is deterministic: `ABC`, `AB`, `AC`,
  `BC`, `A`, `B`, `C`. Integration tests confirm seven composite PNGs, three
  raw single-channel PNGs, manifest generation and black masking outside an
  elliptical ROI.

## Version 0.4 release-blocker regression — 2026-08-20

- The public JDK 25 clean build completed with 22 tests discovered: 15 executed
  successfully and seven private-fixture tests skipped as designed.
- A generated, non-biological four-channel MRXS fixture now exercises the
  parser, recursive index lookup, two packed JPEG payloads, MIRAX BGR component
  mapping, non-RGB QuPath region reads and both export paths in public CI.
- Parseable 16-bit and non-JPEG MRXS fixtures receive builder support level
  zero and are not claimed by this extension.
- Combination export rejects a single output above 25 million pixels, rejects
  more than 500 million cumulative output pixels and handles size arithmetic
  overflow without allocating an image.
- A private regression run executed all 22 tests with zero skips and zero
  failures using the accessible four-channel fixture and the confirmed
  CY5-positive five-channel fixture. The earlier second five-channel fixture
  was not present at its previous path and was not revalidated in this run.
