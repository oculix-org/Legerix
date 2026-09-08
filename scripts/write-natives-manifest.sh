#!/usr/bin/env bash
# Write <tier>/legerix-natives.txt for every native tier staged under
# src/main/resources: one file name per line, sorted, the manifest itself
# excluded. Legerix.loadNatives() extracts, in JAR mode, only the files this
# manifest names (Legerix#21): when Legerix is shaded into a consumer's fat
# jar, that jar may carry other natives under the very same tier directory
# (OculiX ships OpenCV under linux-x86-64/, darwin-aarch64/, ...), and
# without a manifest the loader copied them into its own cache as if it had
# shipped them.
#
# Run after the natives are staged and before `mvn package` / `mvn deploy`.
# Usage: bash ./scripts/write-natives-manifest.sh [resources-dir]
set -euo pipefail

RESOURCES="${1:-src/main/resources}"
MANIFEST="legerix-natives.txt"
# LEGERIX_TIERS narrows the run to the tiers actually staged (a local probe
# on one platform); the release builds stage all seven.
TIERS="${LEGERIX_TIERS:-linux-x86-64 linux-x86-64-legacy linux-aarch64 linux-aarch64-legacy darwin darwin-aarch64 win32-x86-64}"

for tier in $TIERS; do
  dir="$RESOURCES/$tier"
  if [ ! -d "$dir" ]; then
    echo "skip: $dir (absent)"
    continue
  fi
  rm -f "$dir/$MANIFEST"
  # Regular files, immediate children only, sorted for stable diffs. Plain
  # shell globbing rather than find: -printf is GNU-only and the macOS
  # runners have BSD find. Written through a temp file, since a redirection
  # would create the manifest before the loop runs and it would list itself.
  # Hidden files (.gitkeep) are repository plumbing, not natives.
  tmp="$(mktemp)"
  for f in "$dir"/*; do
    [ -f "$f" ] || continue
    base="${f##*/}"
    [ "$base" = "$MANIFEST" ] && continue
    printf '%s\n' "$base"
  done | LC_ALL=C sort > "$tmp"
  mv "$tmp" "$dir/$MANIFEST"
  count=$(wc -l < "$dir/$MANIFEST" | tr -d ' ')
  echo "$tier: $count file(s)"
  sed 's/^/    /' "$dir/$MANIFEST"
  if [ "$count" -eq 0 ]; then
    echo "ERROR: $dir holds no native file — staging failed before this step"
    exit 1
  fi
done
