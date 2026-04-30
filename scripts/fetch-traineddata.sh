#!/usr/bin/env bash
# Download eng.traineddata_fast from the upstream tessdata_fast repo and stage
# it under src/main/resources/tessdata/eng.traineddata.
#
# Usage:  fetch-traineddata.sh [output-dir]
# Default output dir: src/main/resources/tessdata

set -euo pipefail

OUTDIR="${1:-src/main/resources/tessdata}"
URL="https://github.com/tesseract-ocr/tessdata_fast/raw/main/eng.traineddata"

mkdir -p "$OUTDIR"

echo "==> Fetching eng.traineddata (fast model) from $URL"
curl -fsSL "$URL" -o "$OUTDIR/eng.traineddata"

echo "==> Wrote $OUTDIR/eng.traineddata"
ls -lh "$OUTDIR/eng.traineddata"
