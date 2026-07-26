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

## Install

1. Download `qupath-extension-mrxs-0.3.1-alpha.jar` from the latest release.
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

## Known limitations

- Compatibility has only been validated on a small number of fluorescence
  slides from one workflow.
- Pixel-perfect equivalence with the vendor viewer at every pyramid level has
  not been established.
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

The installable artifact is written to
`build/libs/qupath-extension-mrxs-0.3.1-alpha.jar`.

## Validation

The current release passes parser, metadata, index, JPEG, multichannel,
builder-selection, spatial-index and concurrent-read checks against private
four- and five-channel fixtures. See [VALIDATION.md](VALIDATION.md) for the
anonymized validation record.

## Contributing

Bug reports and focused pull requests are welcome. See
[CONTRIBUTING.md](CONTRIBUTING.md).

This community project is not affiliated with or endorsed by QuPath,
3DHISTECH, CaseViewer, or OpenSlide. MRXS and product names may be trademarks
of their respective owners.

## License

Copyright (C) 2026 contributors.

Licensed under the [GNU General Public License v3.0](LICENSE).
