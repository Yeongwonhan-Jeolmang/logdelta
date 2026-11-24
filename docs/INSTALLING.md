# Installing

## Installers

Every release has native installers with their own Java runtime, so nothing else needs to be
installed:

| System | File | Notes |
|---|---|---|
| Windows | `LogDelta-<version>.msi` | Installs for the current user (no administrator rights needed), with a Start menu entry and a desktop shortcut. |
| macOS (Apple silicon / Intel) | `LogDelta-<version>-arm64.dmg` / `-x86_64.dmg` | Drag LogDelta to Applications. The app is not notarized: the first time, right-click it and choose **Open**. |
| Debian, Ubuntu | `logdelta_<version>_amd64.deb` | `sudo apt install ./logdelta_<version>_amd64.deb` |
| Fedora, RHEL, openSUSE | `logdelta-<version>-1.x86_64.rpm` | `sudo dnf install ./logdelta-<version>-1.x86_64.rpm` |

The installers register `.ldsession` files, so double-clicking a saved session opens it.

Besides the desktop app, each installer contains a command-line launcher, `logdelta-cli`:

| System | Location |
|---|---|
| Windows | `%LOCALAPPDATA%\LogDelta\logdelta-cli.exe` |
| macOS | `/Applications/LogDelta.app/Contents/MacOS/logdelta-cli` |
| Linux | `/opt/logdelta/bin/logdelta-cli` |

The .deb package also installs `/usr/bin/logdelta`, shell completions for bash, zsh and fish,
and a man page (`man logdelta`). Elsewhere, link the launcher into your `PATH` to use it as
`logdelta`, for example with the .rpm:

```sh
sudo ln -s /opt/logdelta/bin/logdelta-cli /usr/local/bin/logdelta
```

### Shell completions and the man page

Every installer contains the completion scripts and the man page (`completions/` and `man/` in
the installation folder, e.g. `/opt/logdelta/lib/` on Linux and
`LogDelta.app/Contents/Resources/` on macOS). You can also generate them at any time:

```sh
logdelta completion bash > ~/.local/share/bash-completion/completions/logdelta
logdelta completion zsh  > "${fpath[1]}/_logdelta"
logdelta completion fish > ~/.config/fish/completions/logdelta.fish
logdelta completion powershell | Out-String | Invoke-Expression   # put this line in $PROFILE
logdelta manpage > ~/.local/share/man/man1/logdelta.1
```

### Memory

The installed launchers let Java use up to 75 % of the computer's memory
(`-XX:MaxRAMPercentage=75`), enough for logs of several hundred megabytes. With the jar, pass
`-Xmx` yourself, e.g. `java -Xmx4g -jar logdelta-2.0.0.jar big-1.log big-2.log`.

## The jar

If you have Java 25 or newer, the all-in-one jar runs everywhere:

```sh
java -jar logdelta-2.0.0.jar                    # desktop app
java -jar logdelta-2.0.0.jar a.log b.log        # command line
```

## Updates

The app can check GitHub Releases for a newer version (**Help > Check for Updates**, or
automatically once a day with **Help > Check Automatically**, off by default). It only reads the
latest release's version number; it never downloads or installs anything.

## Building the installers yourself

The installers are made with `jpackage`, which is part of the JDK. `scripts/package.sh` builds
the jar and runs `jpackage` with the right options for the current system:

```sh
./scripts/package.sh               # deb on Linux, dmg on macOS, msi on Windows (Git Bash)
./scripts/package.sh rpm           # another installer type
./scripts/package.sh app-image     # a portable folder; needs no packaging tools
```

The result is in `dist/installers`. The bundled runtime contains only the modules LogDelta uses
(`java.base`, `java.desktop`, `java.net.http`, `java.prefs`, `jdk.charsets`), which keeps the
installed size around 60 MB.

Installer types need the tools `jpackage` relies on: `dpkg-deb` and `fakeroot` for deb,
`rpmbuild` for rpm, the Xcode command-line tools for dmg, and the WiX Toolset for msi. The
release workflow in `.github/workflows/ci.yml` builds all of them on GitHub-hosted runners when a
`v*` tag is pushed, or on demand from the Actions tab (**Run workflow**, *Build the native
installers*).

## The icon

The application icon is drawn in code (`io.github.logdelta.gui.util.AppIcon`): a delta on a
navy tile with three log lines inside, the middle one amber, the line that changed. The
installer icons in `packaging/icons` are generated from it:

```sh
java -cp dist/logdelta.jar io.github.logdelta.gui.util.AppIcon packaging/icons
```
