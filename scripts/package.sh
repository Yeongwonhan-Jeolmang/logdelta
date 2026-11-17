#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Builds a native installer (or a portable app image) with jpackage, bundling a trimmed Java
# runtime, so users need no Java installation.
#
#   ./scripts/package.sh                 # default type for this OS (deb, dmg or msi)
#   ./scripts/package.sh app-image       # portable folder, no installer tools needed
#   ./scripts/package.sh rpm             # any type jpackage supports on this OS
#
# Needs a JDK 25+ (jpackage is part of it). Installer types need the platform tools jpackage
# uses: dpkg-deb or rpmbuild on Linux, Xcode command-line tools on macOS, WiX on Windows.
# Set SKIP_BUILD=1 to reuse dist/logdelta.jar. Output goes to dist/installers.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

VERSION=$(sed -n 's:.*<version>\([0-9][0-9.]*\)</version>.*:\1:p' pom.xml | head -1)
MODULES=java.base,java.desktop,java.net.http,java.prefs,jdk.charsets

case "$(uname -s)" in
  Linux*) OS=linux; DEFAULT_TYPE=deb ;;
  Darwin*) OS=mac; DEFAULT_TYPE=dmg ;;
  MINGW*|MSYS*|CYGWIN*) OS=windows; DEFAULT_TYPE=msi ;;
  *) echo "Unsupported OS: $(uname -s)" >&2; exit 1 ;;
esac
TYPE=${1:-$DEFAULT_TYPE}

# Some JDK builds (e.g. Linux distribution packages) ship separate *.debuginfo files of several
# hundred megabytes; keep them out of the bundle.
JLINK="--strip-debug --no-man-pages --no-header-files --compress=zip-6 --exclude-files=**.debuginfo"

command -v jpackage >/dev/null 2>&1 || { echo "jpackage not found: install a JDK 25+" >&2; exit 1; }

if [ "${SKIP_BUILD:-0}" != 1 ]; then
  if command -v mvn >/dev/null 2>&1; then
    mvn --batch-mode --no-transfer-progress -q -DskipTests package
    mkdir -p dist
    cp "logdelta-app/target/logdelta-$VERSION.jar" dist/logdelta.jar
  else
    sh scripts/build-without-maven.sh
  fi
fi

STAGE=build/jpackage-input
rm -rf "$STAGE"
mkdir -p "$STAGE" dist/installers
cp dist/logdelta.jar "$STAGE/logdelta.jar"

ARGS=(
  --type "$TYPE"
  --name LogDelta
  --app-version "$VERSION"
  --vendor "Hana Eun-Seo"
  --copyright "Copyright (C) 2026 Hana Eun-Seo. GPL-3.0-or-later."
  --description "Compare log files while ignoring what always changes"
  --input "$STAGE"
  --main-jar logdelta.jar
  --main-class io.github.logdelta.app.Launcher
  --java-options -Dlogdelta.launcher=gui
  --add-modules "$MODULES"
  --add-launcher "logdelta-cli=packaging/jpackage/cli-launcher.properties"
  --jlink-options "$JLINK"
  --dest dist/installers
)
if [ "$TYPE" != app-image ]; then
  ARGS+=(--license-file LICENSE --file-associations packaging/jpackage/ldsession.properties
         --about-url "https://github.com/Yeongwonhan-Jeolmang/logdelta")
fi

case "$OS" in
  linux)
    ARGS+=(--icon packaging/icons/logdelta.png)
    if [ "$TYPE" != app-image ]; then
      ARGS+=(--linux-package-name logdelta --linux-shortcut --linux-menu-group Development
             --linux-app-category devel --linux-deb-maintainer Yeongwonhan-Jeolmang@users.noreply.github.com)
    fi
    ;;
  mac)
    ARGS+=(--icon packaging/icons/logdelta.icns --mac-package-identifier io.github.logdelta
           --mac-package-name LogDelta)
    ;;
  windows)
    ARGS+=(--icon packaging/icons/logdelta.ico)
    if [ "$TYPE" != app-image ]; then
      ARGS+=(--win-menu --win-menu-group LogDelta --win-shortcut --win-dir-chooser --win-per-user-install
             --win-upgrade-uuid 6c1f6c52-3f0e-4c38-9a77-6d2f1b6e3e41)
    fi
    ;;
esac

echo "Packaging LogDelta $VERSION as $TYPE..."
jpackage "${ARGS[@]}"
echo "Done:"
ls -1 dist/installers
