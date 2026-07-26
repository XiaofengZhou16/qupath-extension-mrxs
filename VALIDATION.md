# Validation record

Date: 2026-07-24

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
- observed channel maxima: `[66, 22, 17, 0, 35]`

The CY5 band is exactly zero at the tested lowest-resolution layer, while the
other four bands contain non-zero values.

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
- Lowest-resolution channel statistics distinguish a successfully decoded,
  all-zero sampled CY5 layer in the five-channel fixture from a decoding
  failure.
- The diagnostic wording explicitly limits signal conclusions to the sampled
  pyramid level.
