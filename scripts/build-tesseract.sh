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
PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig" \
LDFLAGS="-L$PREFIX/lib -Wl,-rpath,\$ORIGIN" \
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
