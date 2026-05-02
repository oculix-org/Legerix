# Changelog

All notable changes to this project will be documented here. The format is
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
in the form `<tesseract-version>-<build>`.

## [Unreleased]

## [5.5.0-3] - TBD

### Fixed
- Windows: bundle the full vcpkg DLL closure (libpng, tiff, jpeg62, libwebp,
  openjp2, zlib, gif, libcurl, libarchive, …) alongside `tesseract55.dll`
  and `leptonica-*.dll`. Previously only the two top-level DLLs were copied
  by the build and extracted at runtime, causing `UnsatisfiedLinkError` on
  end-user machines that lack the transitive image-codec DLLs.
- `Legerix.loadNatives()` on Windows now enumerates every file under the
  `win32-x86-64/` resource directory and extracts them all, instead of only
  the two hard-coded names returned by `librariesFor(WINDOWS)`.

### Notes
- Same Tesseract upstream (5.5.0). Build-only fix; no API change.

## [5.5.0-2] - TBD

### Added
- Four additional bundled `tessdata_fast` language models alongside English:
  French (`fra`), Spanish (`spa`), Simplified Chinese (`chi_sim`) and Hindi
  (`hin`). Together with `eng` they cover roughly 80% of the world's
  population by primary spoken language.
- `Legerix.BUNDLED_LANGUAGES` public constant exposing the bundled language
  codes for programmatic discovery.

### Changed
- Tesseract upstream version unchanged (still 5.5.0). This is a payload-only
  bump: same natives, expanded `tessdata` folder.
- `scripts/fetch-traineddata.sh` now fetches the five bundled languages
  rather than English alone.
- README "Languages / tessdata" section rewritten: documents the bundled
  set, points to `tessdata_best` for accuracy-sensitive consumers, retains
  the pattern for adding extra languages on top of the bundle.

### Notes
- Jar size grows from ~21 MB to ~30 MB (still well under typical native
  bundle thresholds).

## [5.5.0-1] - 2026-05-01

First release. Tesseract 5.5.0 + Leptonica 1.87.0 bundled, English-only
`tessdata_fast` model.

Why leptonica 1.87.0 and not 1.85.0: tesseract 5.5.0 calls
`pixFindBaselinesGen` which was added to leptonica between 1.85 and
1.86. Building against 1.85.0 produces a runtime
`undefined symbol: pixFindBaselinesGen` error — the same class of
mismatch that originally motivated Legerix.

### Initial scope
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
