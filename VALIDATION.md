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
