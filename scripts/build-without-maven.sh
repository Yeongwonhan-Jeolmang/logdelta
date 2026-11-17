#!/usr/bin/env sh
# Builds the all-in-one jar with nothing but a JDK (25 or newer). Tests are not run:
# use Maven for that ("mvn verify").
#
#   ./scripts/build-without-maven.sh
#   java -jar dist/logdelta.jar
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
# Git Bash / MSYS on Windows: javac reads the @sources file itself, so it needs D:/... paths, not /d/...
if command -v cygpath >/dev/null 2>&1; then
  ROOT=$(cygpath -m "$ROOT")
fi
OUT="$ROOT/build/classes"
DIST="$ROOT/dist"

command -v javac >/dev/null 2>&1 || { echo "javac not found: install a JDK 25+" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT" "$DIST"

echo "Compiling..."
find "$ROOT"/logdelta-core/src/main/java "$ROOT"/logdelta-cli/src/main/java \
     "$ROOT"/logdelta-gui/src/main/java "$ROOT"/logdelta-app/src/main/java -name '*.java' > "$ROOT/build/sources.txt"
javac --release 25 -encoding UTF-8 -d "$OUT" @"$ROOT/build/sources.txt"
cp -R "$ROOT"/logdelta-gui/src/main/resources/. "$OUT"/
mkdir -p "$OUT/META-INF"
cp "$ROOT/LICENSE" "$OUT/META-INF/LICENSE"

echo "Packaging..."
printf 'Main-Class: io.github.logdelta.app.Launcher\nImplementation-Title: LogDelta\n' > "$ROOT/build/manifest.txt"
jar --create --file "$DIST/logdelta.jar" --manifest "$ROOT/build/manifest.txt" -C "$OUT" .

echo "Done: $DIST/logdelta.jar"
echo "  GUI: java -jar dist/logdelta.jar"
echo "  CLI: java -jar dist/logdelta.jar examples/baseline.log examples/candidate.log"
