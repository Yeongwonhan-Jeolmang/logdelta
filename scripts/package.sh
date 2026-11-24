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
#
# Every installer also contains shell completions (bash, zsh, fish, PowerShell) and the man
# page, generated from the jar. The .deb additionally installs them where bash-completion, zsh,
# fish and man look for them, and links /usr/bin/logdelta to the command-line launcher.
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
  if [ -f ./mvnw ]; then
    sh ./mvnw --batch-mode --no-transfer-progress -q -DskipTests package
    mkdir -p dist
    cp "logdelta-app/target/logdelta-$VERSION.jar" dist/logdelta.jar
  elif command -v mvn >/dev/null 2>&1; then
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

# Shell completions and the man page, generated from the jar so they always match it. On macOS
# extra content must live in a standard bundle folder, so it goes under Contents/Resources.
CONTENT=build/jpackage-content
rm -rf "$CONTENT"
EXTRA="$CONTENT"
if [ "$OS" = mac ]; then
  EXTRA="$CONTENT/Resources"
fi
mkdir -p "$EXTRA/completions" "$EXTRA/man"
run_jar() { java -jar dist/logdelta.jar "$@"; }
run_jar completion bash > "$EXTRA/completions/logdelta.bash"
run_jar completion zsh > "$EXTRA/completions/_logdelta"
run_jar completion fish > "$EXTRA/completions/logdelta.fish"
run_jar completion powershell > "$EXTRA/completions/logdelta.ps1"
run_jar manpage --date "$(date -u +%Y-%m-%d)" > "$EXTRA/man/logdelta.1"
if [ "$OS" = mac ]; then
  APP_CONTENT="$EXTRA"
else
  APP_CONTENT="$EXTRA/completions,$EXTRA/man"
fi

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
  --java-options -XX:MaxRAMPercentage=75
  --add-modules "$MODULES"
  --add-launcher "logdelta-cli=packaging/jpackage/cli-launcher.properties"
  --jlink-options "$JLINK"
  --app-content "$APP_CONTENT"
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

# Debian package: also install the completions, the man page and a `logdelta` command where the
# system looks for them. jpackage has no option for files outside /opt, so the package is
# unpacked, extended and packed again.
if [ "$TYPE" = deb ]; then
  DEB=$(ls dist/installers/logdelta_*.deb | head -1)
  WORK=build/deb-repack
  rm -rf "$WORK"
  dpkg-deb -R "$DEB" "$WORK"
  SRC="$EXTRA"
  install -d "$WORK/usr/bin" "$WORK/usr/share/bash-completion/completions" "$WORK/usr/share/zsh/vendor-completions" \
    "$WORK/usr/share/fish/vendor_completions.d" "$WORK/usr/share/man/man1"
  ln -s /opt/logdelta/bin/logdelta-cli "$WORK/usr/bin/logdelta"
  install -m 644 "$SRC/completions/logdelta.bash" "$WORK/usr/share/bash-completion/completions/logdelta"
  ln -s logdelta "$WORK/usr/share/bash-completion/completions/logdelta-cli"
  install -m 644 "$SRC/completions/_logdelta" "$WORK/usr/share/zsh/vendor-completions/_logdelta"
  install -m 644 "$SRC/completions/logdelta.fish" "$WORK/usr/share/fish/vendor_completions.d/logdelta.fish"
  ln -s logdelta.fish "$WORK/usr/share/fish/vendor_completions.d/logdelta-cli.fish"
  gzip -9 -n -c "$SRC/man/logdelta.1" > "$WORK/usr/share/man/man1/logdelta.1.gz"
  chmod 644 "$WORK/usr/share/man/man1/logdelta.1.gz"
  ln -s logdelta.1.gz "$WORK/usr/share/man/man1/logdelta-cli.1.gz"
  if [ -f "$WORK/DEBIAN/md5sums" ]; then
    (cd "$WORK" && find usr -type f -exec md5sum {} + >> DEBIAN/md5sums)
  fi
  SIZE=$(du -sk --exclude=DEBIAN "$WORK" | cut -f1)
  sed -i "s/^Installed-Size:.*/Installed-Size: $SIZE/" "$WORK/DEBIAN/control"
  dpkg-deb --root-owner-group -Zxz -b "$WORK" "$DEB" >/dev/null
  echo "Added completions, man page and /usr/bin/logdelta to $DEB"
fi

echo "Done:"
ls -1 dist/installers
