#!/usr/bin/env bash
# Build Tesseract from source, linked against the leptonica installed in $PREFIX.
#
# Usage:  build-tesseract.sh <version> <install-prefix>
# Example: build-tesseract.sh 5.5.0 /tmp/legerix-prefix

set -euo pipefail

VERSION="${1:?tesseract version required, e.g. 5.5.0}"
PREFIX="${2:?install prefix required, e.g. /tmp/legerix-prefix}"

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

cd "$WORKDIR"

echo "==> Fetching tesseract $VERSION"
curl -fsSL "https://github.com/tesseract-ocr/tesseract/archive/refs/tags/${VERSION}.tar.gz" \
    -o "tesseract-${VERSION}.tar.gz"
tar xzf "tesseract-${VERSION}.tar.gz"
cd "tesseract-${VERSION}"

echo "==> Bootstrapping autotools"
./autogen.sh

echo "==> Configuring tesseract"
# RPATH so libtesseract finds its transitive codec deps (libjpeg, libpng,
# libtiff, libz) and libleptonica in the same directory at runtime.
# Linux: $ORIGIN, macOS: @loader_path. Without this, minimal containers
# and brew-less machines crash on dlopen.
case "$(uname -s)" in
    Darwin) RPATH_FLAG="-Wl,-rpath,@loader_path" ;;
    *)      RPATH_FLAG="-Wl,-rpath,\$ORIGIN" ;;
esac
PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig" \
LDFLAGS="-L$PREFIX/lib $RPATH_FLAG" \
CPPFLAGS="-I$PREFIX/include" \
./configure \
    --prefix="$PREFIX" \
    --disable-static \
    --enable-shared \
    --disable-graphics \
    --disable-openmp \
    --disable-doc \
    CFLAGS="-O2 -fPIC" \
    CXXFLAGS="-O2 -fPIC -std=c++17"

echo "==> Building tesseract (-j$(nproc 2>/dev/null || sysctl -n hw.ncpu))"
make -j"$(nproc 2>/dev/null || sysctl -n hw.ncpu)"
make install

echo "==> Tesseract installed to $PREFIX"
"$PREFIX/bin/tesseract" --version || true
ls -lh "$PREFIX/lib/" | grep -E 'tesseract' || true

# On Linux, libtool corrupts $ORIGIN in RUNPATH — the leading $O gets consumed
# somewhere in the configure/libtool pipeline and the recorded RUNPATH becomes
# the literal string "RIGIN", plus the absolute CI build prefix. Both are
# unresolvable on the user's machine. Post-fix with patchelf: replace whatever
# libtool wrote with a pure "$ORIGIN" so libtesseract.so.5 resolves its sibling
# libleptonica.so.6 from the same directory at runtime, regardless of how the
# bundle is extracted.
if [ "$(uname -s)" != "Darwin" ] && command -v patchelf >/dev/null 2>&1; then
    for so in "$PREFIX/lib/libtesseract.so."*; do
        if [ -f "$so" ] && [ ! -L "$so" ]; then
            patchelf --set-rpath '$ORIGIN' "$so"
            echo "==> patchelf --set-rpath '\$ORIGIN' $so"
        fi
    done
fi
