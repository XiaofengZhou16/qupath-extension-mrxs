# Contributing

Thank you for helping improve MRXS compatibility.

## Before reporting a problem

- Confirm that the `.mrxs` anchor and its same-named companion directory are
  both present.
- Confirm that only one version of this extension is installed.
- Record the QuPath version, operating system, scanner model, acquisition
  software version, channel count, and compression type when known.

Never attach patient data or whole-slide images to a public issue. Prefer a
minimal, de-identified fixture that you have permission to share. If no fixture
can be shared, include only non-sensitive metadata and the relevant QuPath log
excerpt.

## Development

Use JDK 25 and run:

```bash
./gradlew clean test build --no-watch-fs
```

Private integration fixtures can be enabled with `MRXS_TEST_SAMPLE_4C` and
`MRXS_TEST_SAMPLE_5C`; see the README. New format support should include a
focused regression test and should fail explicitly for malformed input.

By contributing, you agree that your contribution is licensed under GPL-3.0.
