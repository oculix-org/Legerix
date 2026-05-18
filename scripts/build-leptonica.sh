#!/usr/bin/env bash
# Build Leptonica from source.
#
# Usage:  build-leptonica.sh <version> <install-prefix>
# Example: build-leptonica.sh 1.87.0 /tmp/legerix-prefix

set -euo pipefail

VERSION="${1:?leptonica version required, e.g. 1.87.0}"
PREFIX="${2:?install prefix required, e.g. /tmp/legerix-prefix}"

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

cd "$WORKDIR"

echo "==> Fetching leptonica $VERSION"
curl -fsSL "https://github.com/DanBloomberg/leptonica/releases/download/${VERSION}/leptonica-${VERSION}.tar.gz" \
    -o "leptonica-${VERSION}.tar.gz"
tar xzf "leptonica-${VERSION}.tar.gz"
cd "leptonica-${VERSION}"

echo "==> Configuring leptonica"
# Belt-and-suspenders: pass every --without-X for formats we don't need,
# AND clear the matching pkg-config vars so a previously-installed webp /
# openjpeg from Homebrew or apt can't sneak back in via auto-detect.
#
# RPATH is set so the produced libleptonica.so / .dylib resolves its
# transitive codec dependencies (libjpeg, libpng, libtiff, libz) from
# the same directory where Legerix extracts it at runtime. Without this,
# minimal Linux containers (Ubuntu slim, Alpine, distroless) and macOS
# machines without homebrew installs crash on missing codec libs.
# Linux uses $ORIGIN, macOS uses @loader_path — same semantics, different
# syntax.
case "$(uname -s)" in
    Darwin) RPATH_FLAG="-Wl,-rpath,@loader_path" ;;
    *)      RPATH_FLAG="-Wl,-rpath,\$ORIGIN" ;;
esac
LIBWEBP_LIBS="" LIBWEBP_CFLAGS="" \
LIBWEBPMUX_LIBS="" LIBWEBPMUX_CFLAGS="" \
LIBOPENJPEG_LIBS="" LIBOPENJPEG_CFLAGS="" \
GIFLIB_LIBS="" GIFLIB_CFLAGS="" \
LDFLAGS="$RPATH_FLAG" \
./configure \
    --prefix="$PREFIX" \
    --disable-static \
    --enable-shared \
    --without-libwebp \
    --without-libwebpmux \
    --without-giflib \
    --without-libopenjpeg \
    CFLAGS="-O2 -fPIC"

echo "==> Building leptonica (-j$(nproc 2>/dev/null || sysctl -n hw.ncpu))"
make -j"$(nproc 2>/dev/null || sysctl -n hw.ncpu)"
make install

echo "==> Leptonica installed to $PREFIX"
ls -lh "$PREFIX/lib/" | grep -E 'leptonica|lept' || true
