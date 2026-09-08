#!/usr/bin/env bash
# Legerix#21 on the real path: the payload contract, exercised in JAR mode on
# the jar that was just packaged. Nothing here runs from target/classes, where
# the manifest is not even read.
#
# Five things are proven, in the order of the report:
#   1. a foreign native under the generic top-level directories a consumer
#      uses never reaches Legerix's cache;
#   2. a decoy jar placed FIRST on the class path, exposing the very same
#      META-INF/legerix resources, supplies nothing: every extracted byte
#      comes from the jar that holds Legerix.class;
#   3. a packaging without its tier manifest, without a declared file, or
#      without Legerix's own identity is refused before anything loads —
#      with a valid cache from step 1 still on disk, so the cache is proven
#      not to be a fallback;
#   4. the extraction directory is private to the run and claimed;
#   5. a second call reuses it and extracts nothing again.
#
# Usage: bash ./scripts/run-payload-probes.sh [tier]
# The tier defaults to linux-x86-64, the one CI packages for this check.
set -euo pipefail

TIER="${1:-linux-x86-64}"
WORK="${RUNNER_TEMP:-/tmp}/legerix-payload-probes"

# CI runs this on Linux; it also runs under Git Bash on Windows, where java is
# the Windows one and wants ';' and native paths. Keeping it runnable there is
# what let these probes be verified by hand before they were trusted in CI.
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) SEP=';' ; native() { cygpath -w "$1"; } ;;
    *)                    SEP=':' ; native() { printf '%s' "$1"; } ;;
esac
classpath() { local out=""; for p in "$@"; do out="${out:+$out$SEP}$(native "$p")"; done; printf '%s' "$out"; }
rm -rf "$WORK"
mkdir -p "$WORK/inject/$TIER" "$WORK/decoy/META-INF/legerix/natives/$TIER"

JAR=$(ls target/legerix-*.jar | grep -v -- '-sources\|-javadoc' | head -1)
# Through a file, not /dev/stdout: Maven writes it with the JVM's own file
# API, and /dev/stdout is not a path a Windows JVM can open.
mvn -B -q dependency:build-classpath -DincludeArtifactIds=jna \
    -Dmdep.outputFile="$(native "$WORK/jna-classpath.txt")" > /dev/null
JNA=$(tr -d '\r\n' < "$WORK/jna-classpath.txt")
[ -n "$JNA" ] || { echo "ERROR: could not resolve the jna jar" >&2; exit 1; }
VERSION=$(mvn -B -q help:evaluate -Dexpression=project.version -DforceStdout)
echo "==> jar: $JAR   version: $VERSION   tier: $TIER"

# 1. A co-bundling consumer's native, under the generic directory name it
#    would use, inside the very same jar.
cp "$JAR" "$WORK/legerix.jar"
echo "not ours" > "$WORK/inject/$TIER/libopencv_fake.so"
(cd "$WORK/inject" && jar uf "$WORK/legerix.jar" "$TIER/libopencv_fake.so")

# 2. A decoy jar carrying Legerix's own resource paths with other content,
#    and a different identity, placed before Legerix on the class path.
MANIFEST="META-INF/legerix/natives/$TIER/legerix-natives.txt"
unzip -p "$WORK/legerix.jar" "$MANIFEST" > "$WORK/decoy/$MANIFEST"
while read -r name; do
  [ -n "$name" ] && echo "DECOY" > "$WORK/decoy/META-INF/legerix/natives/$TIER/$name"
done < "$WORK/decoy/$MANIFEST"
printf 'legerix.version=9.9.9-decoy\ntesseract.version=9.9\nleptonica.version=9.9\n' \
    > "$WORK/decoy/META-INF/legerix/legerix.properties"
(cd "$WORK/decoy" && jar cf "$WORK/decoy.jar" META-INF)

rm -rf "$HOME/.cache/legerix/$VERSION" "${LOCALAPPDATA:-/nonexistent}/legerix/$VERSION"
echo "==> probe 1+2+4+5: decoy first on the class path"
java -cp "$(classpath "$WORK/decoy.jar" "$WORK/legerix.jar" "$JNA")" scripts/JarModeProbe.java

# 3. Damaged packagings, each refused before loading, with the cache the
#    successful run above just left in place.
echo "==> probe 3a: no tier manifest"
cp "$WORK/legerix.jar" "$WORK/no-manifest.jar"
zip -q -d "$WORK/no-manifest.jar" "$MANIFEST"
java -cp "$(classpath "$WORK/no-manifest.jar" "$JNA")" scripts/StrictPackagingProbe.java "does not declare what it ships"

echo "==> probe 3b: a declared file removed from the payload"
DECLARED=$(head -1 "$WORK/decoy/$MANIFEST")
cp "$WORK/legerix.jar" "$WORK/missing-file.jar"
zip -q -d "$WORK/missing-file.jar" "META-INF/legerix/natives/$TIER/$DECLARED"
java -cp "$(classpath "$WORK/missing-file.jar" "$JNA")" scripts/StrictPackagingProbe.java "does not contain it"

echo "==> probe 3c: no Legerix identity"
cp "$WORK/legerix.jar" "$WORK/no-identity.jar"
zip -q -d "$WORK/no-identity.jar" 'META-INF/legerix/legerix.properties'
java -cp "$(classpath "$WORK/no-identity.jar" "$JNA")" scripts/StrictPackagingProbe.java "legerix.properties is missing"

echo "==> all payload probes passed"
