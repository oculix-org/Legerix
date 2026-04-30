# Changelog

All notable changes to this project will be documented here. The format is
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
in the form `<tesseract-version>-<build>`.

## [Unreleased]

### Added
- Initial repo skeleton modeled on `julienmerconsulting/Apertix`.
- 7-job CI matrix: Linux x86\_64 + aarch64 (modern + legacy glibc tiers),
  macOS x86\_64 + aarch64, Windows x86\_64.
- `Legerix` Java loader: OS/arch detection, glibc tier picker (`ldd --version`),
  idempotent extraction to `~/.cache/legerix/<version>/<tier>/`.
- Smoke test: renders text via Java2D, runs OCR through tess4j, asserts
  round-trip.
- Build scripts: `build-leptonica.sh`, `build-tesseract.sh`,
  `fetch-traineddata.sh`.
- Maven Central release profile (`central-publishing-maven-plugin` + GPG).
- MIT license, NOTICE, THIRD-PARTY.txt covering Tesseract (Apache 2.0),
  Leptonica (BSD-2), eng.traineddata (Apache 2.0).

## [5.5.0-1] - TBD

First release. Tesseract 5.5.0 + Leptonica 1.85.0 bundled.
