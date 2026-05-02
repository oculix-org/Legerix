# Legerix

Tesseract + Leptonica natives bundled as a cross-platform Maven artifact, with
a thin Java loader. From Latin _legere_, "to read".

Modeled on [Apertix](https://github.com/julienmerconsulting/Apertix), which
does the same thing for OpenCV. Legerix solves the equivalent problem for
[tess4j](https://github.com/nguyenq/tess4j) consumers: ship the matching
Tesseract version with the application instead of relying on a system-wide
`apt install tesseract-ocr`, which on Ubuntu 24.04 ships `libtesseract.so.5.0.3`
(Tesseract 5.3.4) and is missing the `TessBaseAPIGetPAGEText` symbol introduced
in 5.5.x.

## Coordinates

```xml
<dependency>
    <groupId>io.github.oculix-org</groupId>
    <artifactId>legerix</artifactId>
    <version>5.5.0-1</version>
</dependency>
```

Versioning: `<tesseract-version>-<build-number>`. A bump of the build number
re-ships the same Tesseract version with a CI/packaging fix; a Tesseract
upstream release resets the build to `1`.

## What's in the jar

| Path                                  | Content                                |
| ------------------------------------- | -------------------------------------- |
| `linux-x86-64/`                       | glibc &ge; 2.38 build (Ubuntu 24.04)   |
| `linux-x86-64-legacy/`                | glibc &ge; 2.28 build (manylinux_2_28) |
| `linux-aarch64/`                      | glibc &ge; 2.38 build                  |
| `linux-aarch64-legacy/`               | glibc &ge; 2.28 build                  |
| `darwin/`                             | macOS x86\_64                          |
| `darwin-aarch64/`                     | macOS Apple Silicon                    |
| `win32-x86-64/`                       | Windows x86\_64 (vcpkg toolchain)      |
| `tessdata/*.traineddata`              | 5 bundled fast models, ~12 MB total (see below) |
| `io/github/julienmerconsulting/legerix/Legerix.class` | Java loader               |

Each platform directory contains both `libtesseract` and `libleptonica`.

## Public API

```java
import io.github.julienmerconsulting.legerix.Legerix;

// Extract natives + tessdata to a per-user cache, load both libs into the JVM.
Path nativesDir   = Legerix.loadNatives();

// Path to the extracted tessdata folder, ready to feed to tess4j.
Path tessdataDir  = Legerix.getTessdataPath();

// Detected runtime tier on Linux: "modern", "legacy" or "n/a" off-Linux.
String tier       = Legerix.getGlibcTier();

// Tesseract upstream version embedded in this jar (e.g. "5.5.0").
String tessVer    = Legerix.getTesseractVersion();
```

### Typical OculiX-side wiring (tess4j consumer)

```java
Legerix.loadNatives();                          // BEFORE tess4j touches JNA
ITesseract tess = new Tesseract();
tess.setDatapath(Legerix.getTessdataPath().toString());
tess.setLanguage("eng");
String text = tess.doOCR(image);
```

## Languages / tessdata

Five lightweight `tessdata_fast` language models are bundled in the jar
(~12 MB total), chosen to cover roughly 80% of the world's population by
primary spoken language:

| Code      | Language            | Approx. size |
| --------- | ------------------- | ------------ |
| `eng`     | English             | ~4 MB        |
| `fra`     | French              | ~1.1 MB      |
| `spa`     | Spanish             | ~1 MB        |
| `chi_sim` | Simplified Chinese  | ~3 MB        |
| `hin`     | Hindi               | ~3.2 MB      |

All five are `tessdata_fast` (LSTM-only, optimized for speed) — the same
trade-off Tesseract makes for its mobile / embedded targets. For higher
accuracy at the cost of size and latency, swap them out with
`tessdata_best` from upstream.

Available programmatically via `Legerix.BUNDLED_LANGUAGES`.

### Using a bundled language

```java
Legerix.loadNatives();
ITesseract tess = new Tesseract();
tess.setDatapath(Legerix.getTessdataPath().toString());
tess.setLanguage("fra");                 // or "eng+fra" for combined
String text = tess.doOCR(image);
```

### Adding more languages

If you need a language outside the bundled set (German, Japanese, Arabic,
multi-language combinations, etc.), drop the corresponding `*.traineddata`
file into the same cache directory that `getTessdataPath()` returns, then
keep using it as the datapath:

```java
Path tessdata = Legerix.getTessdataPath();
// On first run: download deu.traineddata into tessdata.resolve("deu.traineddata").
// (Get it from https://github.com/tesseract-ocr/tessdata_fast or tessdata_best.)
ITesseract tess = new Tesseract();
tess.setDatapath(tessdata.toString());
tess.setLanguage("deu");
```

The cache directory is writable and persistent, keyed on the full Legerix
version so a build-suffix bump invalidates stale extracted natives
(`~/.cache/legerix/5.5.0-4/tessdata/` on Linux,
`%LOCALAPPDATA%\legerix\5.5.0-4\tessdata\` on Windows).

Alternatively, point tess4j at a completely separate `tessdata` folder you
control:

```java
Legerix.loadNatives();
ITesseract tess = new Tesseract();
tess.setDatapath("/opt/myapp/tessdata");
tess.setLanguage("ara");
```

A future release may add a helper like `Legerix.installLanguage("deu")`
that downloads on-demand and caches.

## Glibc tier picker

On Linux, `loadNatives()` runs `ldd --version` at startup and picks
`linux-<arch>-legacy/` if the detected glibc is older than 2.38, else the
modern build. No env var required, no system tesseract required, no
`apt install` required.

For non-Linux platforms the tier is reported as `"n/a"`.

## Building locally

The natives are produced by GitHub Actions (`.github/workflows/build.yml`,
7-job matrix). Reproducing locally just for one platform:

```bash
PREFIX="$PWD/_prefix"
mkdir -p "$PREFIX"
./scripts/build-leptonica.sh 1.87.0 "$PREFIX"
./scripts/build-tesseract.sh 5.5.0  "$PREFIX"
./scripts/fetch-traineddata.sh src/main/resources/tessdata

# Stage and package
mkdir -p src/main/resources/linux-x86-64
cp -L "$PREFIX/lib/libleptonica.so.6" src/main/resources/linux-x86-64/
cp -L "$PREFIX/lib/libtesseract.so.5" src/main/resources/linux-x86-64/
mvn -B install
```

CI is the source of truth for cross-platform builds; local builds populate
only the current host's resource directory.

## License

[MIT](LICENSE) for Legerix itself. Bundled natives keep their upstream
licenses (Apache 2.0 for Tesseract and the eng traineddata, BSD-2-Clause for
Leptonica). See [NOTICE](NOTICE) and [THIRD-PARTY.txt](THIRD-PARTY.txt).
