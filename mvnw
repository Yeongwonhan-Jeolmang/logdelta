#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Maven wrapper: runs the Maven version named in .mvn/wrapper/maven-wrapper.properties, downloading
# it once into $MAVEN_USER_HOME/wrapper/dists (default ~/.m2/wrapper/dists). No Maven installation
# is needed, only Java and curl or wget. Usage is the same as mvn:
#
#   ./mvnw verify
#
# The properties file uses the same keys as the Apache Maven Wrapper (distributionUrl and the
# optional distributionSha256Sum), so the official wrapper can replace this script at any time.
set -eu

die() {
  printf 'mvnw: %s\n' "$*" >&2
  exit 1
}

BASEDIR=$(cd "$(dirname "$0")" && pwd)
PROPS="$BASEDIR/.mvn/wrapper/maven-wrapper.properties"
[ -f "$PROPS" ] || die "missing $PROPS"

prop() {
  sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$PROPS" | tr -d '\r' | tail -n 1
}

URL=$(prop distributionUrl)
SHA256=$(prop distributionSha256Sum)
[ -n "$URL" ] || die "no distributionUrl in $PROPS"

ARCHIVE_NAME=${URL##*/}
case "$ARCHIVE_NAME" in
  *-bin.tar.gz) DIST_NAME=${ARCHIVE_NAME%-bin.tar.gz} ;;
  *-bin.zip) DIST_NAME=${ARCHIVE_NAME%-bin.zip} ;;
  *) die "unsupported distribution archive: $ARCHIVE_NAME" ;;
esac

# One folder per URL, so changing the mirror or version never reuses a stale download.
URL_HASH=$(printf '%s' "$URL" | cksum | cut -d ' ' -f 1)
DISTS="${MAVEN_USER_HOME:-$HOME/.m2}/wrapper/dists/$DIST_NAME/$URL_HASH"
MAVEN_HOME="$DISTS/$DIST_NAME"

if [ ! -x "$MAVEN_HOME/bin/mvn" ]; then
  mkdir -p "$DISTS"
  TMP=$(mktemp -d "$DISTS/download.XXXXXX")
  trap 'rm -rf "$TMP"' EXIT INT TERM
  printf 'mvnw: downloading %s\n' "$URL" >&2
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL --retry 3 -o "$TMP/$ARCHIVE_NAME" "$URL" || die "download failed: $URL"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$TMP/$ARCHIVE_NAME" "$URL" || die "download failed: $URL"
  else
    die "neither curl nor wget is available"
  fi
  if [ -n "$SHA256" ]; then
    if command -v sha256sum >/dev/null 2>&1; then
      ACTUAL=$(sha256sum "$TMP/$ARCHIVE_NAME" | cut -d ' ' -f 1)
    else
      ACTUAL=$(shasum -a 256 "$TMP/$ARCHIVE_NAME" | cut -d ' ' -f 1)
    fi
    [ "$ACTUAL" = "$SHA256" ] || die "checksum mismatch for $ARCHIVE_NAME (expected $SHA256, got $ACTUAL)"
  fi
  case "$ARCHIVE_NAME" in
    *.tar.gz) tar -xzf "$TMP/$ARCHIVE_NAME" -C "$TMP" ;;
    *.zip) (cd "$TMP" && unzip -q "$ARCHIVE_NAME") ;;
  esac
  [ -d "$TMP/$DIST_NAME" ] || die "the archive does not contain $DIST_NAME"
  chmod +x "$TMP/$DIST_NAME/bin/mvn"
  # Another build may have finished the same download meanwhile; keep whichever came first.
  mv "$TMP/$DIST_NAME" "$MAVEN_HOME" 2>/dev/null || [ -x "$MAVEN_HOME/bin/mvn" ] || die "cannot install to $MAVEN_HOME"
fi

exec "$MAVEN_HOME/bin/mvn" "$@"
