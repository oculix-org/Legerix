#!/usr/bin/env bash
# Build a self-contained Legerix jar locally — no CI needed.
#
# Why this exists: the Maven-Central-published legerix-5.5.0-5.jar ships
# libtesseract.so.5 dynamically linked against libjpeg.so.62, libpng16.so.16,
# etc. On hosts without those system libs (Ubuntu 22+ minimal, Alpine,
# distroless), Legerix fails with UnsatisfiedLinkError on dlopen.
#
# This script statically links every codec dep into libtesseract.so.5 and
# libleptonica.so.6 so the resulting .so has no DT_NEEDED on libjpeg/libpng/
# libtiff/libwebp/libz/libzstd/liblzma. Apertix (OpenCV wrapper) does the same.
#
# Usage:
#   ./scripts/build-self-contained-local.sh
#   mvn install
#
# Output:
#   src/main/resources/<your-tier>/libleptonica.so.6 + libtesseract.so.5
#     -- self-contained, statically linked against all codec deps
#   src/main/resources/<other 6 tiers>/  -- 1-byte placeholders so
#     maven-bundle-plugin Bundle-NativeCode validation passes
#
# `mvn install` then installs the jar as 5.5.0-5 in your local ~/.m2,
# overriding the broken Maven Central copy. Rebuild OculiX (which
# resolves Legerix via the property <legerix.version>5.5.0-5</legerix.version>)
# and the loop is closed without polluting CI or republishing.
#
# Requirements (Ubuntu/Debian):
#   sudo apt install build-essential cmake autoconf automake libtool \
#                    pkg-config curl tar nasm
# (nasm only needed for libjpeg-turbo SIMD on x86_64)

set -euo pipefail

# ---- Versions (aligned with pom.xml leptonica.version and historical tesseract build) ----
LEPTONICA_VERSION="${LEPTONICA_VERSION:-1.87.0}"
TESSERACT_VERSION="${TESSERACT_VERSION:-5.5.0}"
LIBJPEG_TURBO_VERSION="${LIBJPEG_TURBO_VERSION:-3.0.4}"
LIBPNG_VERSION="${LIBPNG_VERSION:-1.6.43}"
LIBTIFF_VERSION="${LIBTIFF_VERSION:-4.6.0}"
LIBWEBP_VERSION="${LIBWEBP_VERSION:-1.4.0}"
ZLIB_VERSION="${ZLIB_VERSION:-1.3.1}"
ZSTD_VERSION="${ZSTD_VERSION:-1.5.6}"
XZ_VERSION="${XZ_VERSION:-5.6.2}"
LIBDEFLATE_VERSION="${LIBDEFLATE_VERSION:-1.20}"

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STATIC_PREFIX="${LEGERIX_STATIC_PREFIX:-/tmp/legerix-static-build}"
FINAL_PREFIX="${STATIC_PREFIX}-final"
JOBS="${JOBS:-$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)}"

mkdir -p "$STATIC_PREFIX"/{lib,include,bin,src} "$FINAL_PREFIX"/{lib,include,bin}

# ---- Tier detection (mirrors Legerix.java resourceDirFor + detectGlibcTier) ----
detect_tier() {
    local os arch
    os="$(uname -s)"
    arch="$(uname -m)"
    case "$os" in
        Linux)
            local glibc_ver glibc_major glibc_minor is_modern=0
            glibc_ver="$(ldd --version 2>&1 | head -1 | grep -oE '[0-9]+\.[0-9]+' | head -1)"
            glibc_major="${glibc_ver%.*}"
            glibc_minor="${glibc_ver#*.}"
            if [ "$glibc_major" -gt 2 ] || { [ "$glibc_major" -eq 2 ] && [ "$glibc_minor" -ge 38 ]; }; then
                is_modern=1
            fi
            local arch_suffix
            case "$arch" in
                x86_64)        arch_suffix="x86-64" ;;
                aarch64|arm64) arch_suffix="aarch64" ;;
                *) echo "Unsupported arch: $arch" >&2; exit 1 ;;
            esac
            if [ "$is_modern" -eq 1 ]; then
                echo "linux-${arch_suffix}"
            else
                echo "linux-${arch_suffix}-legacy"
            fi
            ;;
        Darwin)
            case "$arch" in
                x86_64) echo "darwin" ;;
                arm64)  echo "darwin-aarch64" ;;
                *) echo "Unsupported arch: $arch" >&2; exit 1 ;;
            esac
            ;;
        *)
            echo "Unsupported OS: $os (this script is for Linux/macOS only — Windows uses vcpkg via CI)" >&2
            exit 1
            ;;
    esac
}

TIER="$(detect_tier)"
echo "==> Building self-contained Legerix for tier: $TIER"

# ---- RPATH so libtesseract.so.5 finds libleptonica.so.6 next to it at runtime ----
case "$(uname -s)" in
    Darwin) RPATH_FLAG="-Wl,-rpath,@loader_path" ;;
    *)      RPATH_FLAG="-Wl,-rpath,\$ORIGIN" ;;
esac

fetch_and_cd() {
    local url="$1" name="$2"
    cd "$STATIC_PREFIX/src"
    if [ ! -d "$name" ]; then
        echo "==> Fetching $name"
        curl -fsSL "$url" -o "${name}.tar.gz"
        tar xzf "${name}.tar.gz"
    fi
    cd "$name"
}

# ---- Static codec deps ----
build_zlib() {
    [ -f "$STATIC_PREFIX/lib/libz.a" ] && return
    echo "==> zlib"
    fetch_and_cd "https://github.com/madler/zlib/releases/download/v${ZLIB_VERSION}/zlib-${ZLIB_VERSION}.tar.gz" "zlib-${ZLIB_VERSION}"
    CFLAGS="-O2 -fPIC" ./configure --prefix="$STATIC_PREFIX" --static
    make -j"$JOBS"; make install
}

build_xz() {
    [ -f "$STATIC_PREFIX/lib/liblzma.a" ] && return
    echo "==> xz/liblzma"
    fetch_and_cd "https://github.com/tukaani-project/xz/releases/download/v${XZ_VERSION}/xz-${XZ_VERSION}.tar.gz" "xz-${XZ_VERSION}"
    CFLAGS="-O2 -fPIC" ./configure --prefix="$STATIC_PREFIX" --enable-static --disable-shared --disable-doc --disable-nls --disable-scripts
    make -j"$JOBS"; make install
}

build_zstd() {
    [ -f "$STATIC_PREFIX/lib/libzstd.a" ] && return
    echo "==> zstd"
    fetch_and_cd "https://github.com/facebook/zstd/releases/download/v${ZSTD_VERSION}/zstd-${ZSTD_VERSION}.tar.gz" "zstd-${ZSTD_VERSION}"
    PREFIX="$STATIC_PREFIX" CFLAGS="-O2 -fPIC" make -j"$JOBS" -C lib install-static install-includes
}

build_libjpeg_turbo() {
    [ -f "$STATIC_PREFIX/lib/libjpeg.a" ] && return
    echo "==> libjpeg-turbo"
    fetch_and_cd "https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/${LIBJPEG_TURBO_VERSION}/libjpeg-turbo-${LIBJPEG_TURBO_VERSION}.tar.gz" "libjpeg-turbo-${LIBJPEG_TURBO_VERSION}"
    rm -rf build && mkdir build && cd build
    cmake .. -DCMAKE_INSTALL_PREFIX="$STATIC_PREFIX" \
             -DENABLE_STATIC=ON -DENABLE_SHARED=OFF \
             -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
             -DCMAKE_BUILD_TYPE=Release
    make -j"$JOBS"; make install
}

build_libpng() {
    [ -f "$STATIC_PREFIX/lib/libpng16.a" ] && return
    echo "==> libpng"
    fetch_and_cd "https://download.sourceforge.net/libpng/libpng-${LIBPNG_VERSION}.tar.gz" "libpng-${LIBPNG_VERSION}"
    CPPFLAGS="-I$STATIC_PREFIX/include" LDFLAGS="-L$STATIC_PREFIX/lib" CFLAGS="-O2 -fPIC" \
        ./configure --prefix="$STATIC_PREFIX" --enable-static --disable-shared
    make -j"$JOBS"; make install
}

build_libwebp() {
    [ -f "$STATIC_PREFIX/lib/libwebp.a" ] && return
    echo "==> libwebp"
    fetch_and_cd "https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-${LIBWEBP_VERSION}.tar.gz" "libwebp-${LIBWEBP_VERSION}"
    CFLAGS="-O2 -fPIC" ./configure --prefix="$STATIC_PREFIX" --enable-static --disable-shared \
        --disable-gl --disable-sdl --disable-png --disable-jpeg --disable-tiff --disable-gif
    make -j"$JOBS"; make install
}

build_libdeflate() {
    [ -f "$STATIC_PREFIX/lib/libdeflate.a" ] && return
    echo "==> libdeflate"
    fetch_and_cd "https://github.com/ebiggers/libdeflate/releases/download/v${LIBDEFLATE_VERSION}/libdeflate-${LIBDEFLATE_VERSION}.tar.gz" "libdeflate-${LIBDEFLATE_VERSION}"
    rm -rf build && mkdir build && cd build
    cmake .. -DCMAKE_INSTALL_PREFIX="$STATIC_PREFIX" \
             -DLIBDEFLATE_BUILD_STATIC_LIB=ON -DLIBDEFLATE_BUILD_SHARED_LIB=OFF \
             -DLIBDEFLATE_BUILD_GZIP=OFF \
             -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
             -DCMAKE_BUILD_TYPE=Release
    make -j"$JOBS"; make install
}

build_libtiff() {
    [ -f "$STATIC_PREFIX/lib/libtiff.a" ] && return
    echo "==> libtiff"
    fetch_and_cd "https://download.osgeo.org/libtiff/tiff-${LIBTIFF_VERSION}.tar.gz" "tiff-${LIBTIFF_VERSION}"
    CPPFLAGS="-I$STATIC_PREFIX/include" LDFLAGS="-L$STATIC_PREFIX/lib" CFLAGS="-O2 -fPIC" \
        ./configure --prefix="$STATIC_PREFIX" --enable-static --disable-shared \
                    --disable-jbig --disable-lerc --disable-tools --disable-tests --disable-docs
    make -j"$JOBS"; make install
}

# ---- leptonica (shared, statically linked against codec deps) ----
build_leptonica() {
    echo "==> leptonica $LEPTONICA_VERSION (shared, static codec deps)"
    fetch_and_cd "https://github.com/DanBloomberg/leptonica/releases/download/${LEPTONICA_VERSION}/leptonica-${LEPTONICA_VERSION}.tar.gz" "leptonica-${LEPTONICA_VERSION}"
    # Force pkg-config of codec libs to point at our static prefix, and pass
    # explicit -Bstatic / -Bdynamic groups so codec .a files are pulled in
    # and only the system libs (libm, libpthread, libc) remain dynamic.
    LIBWEBP_LIBS="" LIBWEBP_CFLAGS="" \
    LIBWEBPMUX_LIBS="" LIBWEBPMUX_CFLAGS="" \
    LIBOPENJPEG_LIBS="" LIBOPENJPEG_CFLAGS="" \
    GIFLIB_LIBS="" GIFLIB_CFLAGS="" \
    PKG_CONFIG_PATH="$STATIC_PREFIX/lib/pkgconfig" \
    CPPFLAGS="-I$STATIC_PREFIX/include" \
    LDFLAGS="-L$STATIC_PREFIX/lib $RPATH_FLAG" \
    LIBS="-Wl,-Bstatic -ljpeg -lpng16 -ltiff -lz -ldeflate -llzma -lzstd -Wl,-Bdynamic -lm -lpthread" \
        ./configure --prefix="$FINAL_PREFIX" \
            --disable-static --enable-shared \
            --without-libwebp --without-libwebpmux \
            --without-giflib --without-libopenjpeg \
            CFLAGS="-O2 -fPIC"
    make -j"$JOBS"; make install
}

# ---- tesseract (shared, linked against libleptonica.so.6 + static codec deps) ----
build_tesseract() {
    echo "==> tesseract $TESSERACT_VERSION (shared, static codec deps, links libleptonica.so.6 dynamic)"
    fetch_and_cd "https://github.com/tesseract-ocr/tesseract/archive/refs/tags/${TESSERACT_VERSION}.tar.gz" "tesseract-${TESSERACT_VERSION}"
    ./autogen.sh
    PKG_CONFIG_PATH="$FINAL_PREFIX/lib/pkgconfig:$STATIC_PREFIX/lib/pkgconfig" \
    CPPFLAGS="-I$FINAL_PREFIX/include -I$STATIC_PREFIX/include" \
    LDFLAGS="-L$FINAL_PREFIX/lib -L$STATIC_PREFIX/lib $RPATH_FLAG" \
    LIBS="-Wl,-Bstatic -ljpeg -lpng16 -ltiff -lz -ldeflate -llzma -lzstd -Wl,-Bdynamic -lm -lpthread" \
        ./configure --prefix="$FINAL_PREFIX" \
            --disable-static --enable-shared \
            --disable-graphics --disable-openmp --disable-doc \
            CFLAGS="-O2 -fPIC" CXXFLAGS="-O2 -fPIC -std=c++17"
    make -j"$JOBS"; make install
}

# ---- Run the build chain ----
build_zlib
build_xz
build_zstd
build_libjpeg_turbo
build_libpng
build_libwebp
build_libdeflate
build_libtiff
build_leptonica
build_tesseract

# ---- Stage into src/main/resources ----
echo ""
echo "==> Staging self-contained natives into src/main/resources/$TIER/"
mkdir -p "$REPO_ROOT/src/main/resources/$TIER"

case "$(uname -s)" in
    Darwin)
        cp -L "$FINAL_PREFIX/lib/libtesseract.5.dylib" "$REPO_ROOT/src/main/resources/$TIER/"
        cp -L "$FINAL_PREFIX/lib/libleptonica.6.dylib" "$REPO_ROOT/src/main/resources/$TIER/"
        # Re-point @rpath references so loader_path resolution works.
        install_name_tool -id "@rpath/libtesseract.5.dylib" "$REPO_ROOT/src/main/resources/$TIER/libtesseract.5.dylib" 2>/dev/null || true
        install_name_tool -id "@rpath/libleptonica.6.dylib" "$REPO_ROOT/src/main/resources/$TIER/libleptonica.6.dylib" 2>/dev/null || true
        ;;
    *)
        cp -L "$FINAL_PREFIX/lib/libtesseract.so.5" "$REPO_ROOT/src/main/resources/$TIER/"
        cp -L "$FINAL_PREFIX/lib/libleptonica.so.6" "$REPO_ROOT/src/main/resources/$TIER/"
        ;;
esac

# Strip to shrink the jar.
strip "$REPO_ROOT/src/main/resources/$TIER/libtesseract."* 2>/dev/null || true
strip "$REPO_ROOT/src/main/resources/$TIER/libleptonica."* 2>/dev/null || true

# Verify: on Linux, confirm libtesseract.so.5 has no DT_NEEDED on codec libs.
if [ "$(uname -s)" = "Linux" ]; then
    echo ""
    echo "==> Verifying libtesseract.so.5 has no DT_NEEDED on libjpeg/libpng/libtiff/libwebp/libzstd/liblzma"
    NEEDED="$(readelf -d "$REPO_ROOT/src/main/resources/$TIER/libtesseract.so.5" | grep NEEDED || true)"
    echo "$NEEDED"
    if echo "$NEEDED" | grep -qE 'libjpeg|libpng|libtiff|libwebp|libzstd|liblzma|libdeflate'; then
        echo ""
        echo "ERROR: libtesseract.so.5 still has dynamic deps on codec libs. Static link failed."
        echo "       Inspect LDFLAGS/LIBS in build_tesseract() and rerun."
        exit 1
    fi
    echo "OK: libtesseract.so.5 is self-contained (only libleptonica + libstdc++/libgcc_s/libpthread/libc/libm)."
fi

# ---- Stage 1-byte dummies in the 6 other tiers so Bundle-NativeCode validation passes ----
ALL_TIERS=(linux-x86-64 linux-x86-64-legacy linux-aarch64 linux-aarch64-legacy darwin darwin-aarch64 win32-x86-64)
for t in "${ALL_TIERS[@]}"; do
    [ "$t" = "$TIER" ] && continue
    mkdir -p "$REPO_ROOT/src/main/resources/$t"
    case "$t" in
        linux*)       printf 'x' > "$REPO_ROOT/src/main/resources/$t/libtesseract.so.5"
                       printf 'x' > "$REPO_ROOT/src/main/resources/$t/libleptonica.so.6" ;;
        darwin*)      printf 'x' > "$REPO_ROOT/src/main/resources/$t/libtesseract.5.dylib"
                       printf 'x' > "$REPO_ROOT/src/main/resources/$t/libleptonica.6.dylib" ;;
        win32-x86-64) printf 'x' > "$REPO_ROOT/src/main/resources/$t/tesseract55.dll" ;;
    esac
done

# ---- tessdata (lightweight language models) ----
# Defuse Windows CRLF on the existing script (the repo was likely cloned with
# autocrlf=true and WSL bash chokes on \r\n line endings).
sed -i 's/\r$//' "$REPO_ROOT/scripts/fetch-traineddata.sh"
bash "$REPO_ROOT/scripts/fetch-traineddata.sh" "$REPO_ROOT/src/main/resources/tessdata"

echo ""
echo "================================================================"
echo "  $TIER staged self-contained. Other tiers stubbed."
echo "================================================================"
echo ""
echo "Next:"
echo "  cd $REPO_ROOT"
echo "  mvn -B install -DskipTests"
echo ""
echo "This overrides ~/.m2/repository/io/github/oculix-org/legerix/5.5.0-5/"
echo "with your locally-built self-contained jar."
echo ""
echo "Then rebuild OculiX from the test branch:"
echo "  cd /path/to/OculiX"
echo "  git checkout test/legerix-5.5.0-5-for-adrian"
echo "  mvn -B install -DskipTests"
echo ""
echo "Run OculiX and Tesseract OCR should work without libjpeg.so.62 or any"
echo "other codec libs being installed on the host."
