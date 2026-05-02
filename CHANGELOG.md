# Changelog

All notable changes to this project will be documented here. The format is
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
in the form `<tesseract-version>-<build>`.

## [Unreleased]

## [5.5.0-4] - TBD

### Fixed
- Windows: `Legerix.loadNatives()` now calls `SetDllDirectoryW` on the
  extract directory before `System.load()`, so the vcpkg shim DLLs
  (`leptonica-1.87.0.dll`, `tesseract55.dll`) can resolve their sibling
  imports (`libleptonica1870.dll`, `libpng16.dll`, ...) from the same
  folder. Previous releases failed with `UnsatisfiedLinkError: Can't find
  dependent libraries` unless the user manually prepended the extract
  directory to `PATH`.
- Cache directory is now keyed on the full Legerix Maven version
  (e.g. `5.5.0-4`) instead of the upstream Tesseract version (`5.5.0`),
  so bumping only the build suffix invalidates stale extracted DLLs.
  Previously, upgrading from `5.5.0-2` to `5.5.0-3` left the broken
  Windows DLLs from the older cache in place.

## [5.5.0-3] - TBD

### Added
- Windows: bundle the full set of vcpkg-built transitive DLLs (libpng,
  libtiff, libjpeg-turbo, libwebp, openjp2, zlib, libcurl, libarchive,
  etc.) inside the jar at `win32-x86-64/`, alongside `tesseract55.dll`
  and `leptonica-1.87.0.dll`. Previous releases shipped only the two
  canonical DLLs and end users hit `UnsatisfiedLinkError` on Windows the
  first time tesseract.dll asked the loader to resolve a transitive
  codec/runtime import.
- `Legerix.loadNatives()` now extracts every regular file under the
  platform's resource directory, not just the two canonical names from
  `librariesFor()`. Linux/macOS no-op (resource dirs contain only the
  canonical pair); Windows picks up the codec/runtime DLLs automatically.
  Implemented via JarFile enumeration in JAR mode and `Files.list` in
  exploded-classpath mode.

### Changed
- Windows native packaging in CI: instead of two flat DLL release assets
  (`tesseract-win-x86-64.dll`, `leptonica-win-x86-64.dll`), we ship a
  single `legerix-win-x86-64.zip` asset containing every DLL from the
  vcpkg `installed/x64-windows/bin/` directory. Re-publication workflows
  (`publish-maven-central.yml`, `verify-release.yml`) `unzip` it back
  into the resource layout before `mvn deploy/verify`.

### Notes
- 5.5.0-2 was abandoned mid-build due to a CI permission issue: the
  multilang push via the GitHub API stripped the executable bit on
  `scripts/fetch-traineddata.sh`, breaking the `build_dist` job before
  any release was cut. Workflows now invoke shell scripts via
  `bash ./...` to be mode-agnostic. The multilang tessdata payload
  (fra, spa, chi_sim, hin alongside eng) ships in 5.5.0-3.

## [5.5.0-2] - never shipped (CI failure mid-build)

Intended payload: 4 additional bundled `tessdata_fast` languages (`fra`,
`spa`, `chi_sim`, `hin`) + `Legerix.BUNDLED_LANGUAGES` constant.
Superseded by 5.5.0-3 which carries the same payload plus the Windows
transitive DLL fix.

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
