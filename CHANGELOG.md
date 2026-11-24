# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [2.0.0] - 2026-11-23

### Added
- **Structured logs.** JSON lines and logfmt are detected and compared field by field: key order
  no longer matters, changed fields are listed (`@@ field changes @@` in Markdown, a tooltip in
  the app, `rows[].fieldChanges` in JSON) and timestamps and trace/span ids are ignored by
  default (`--log-format`, `--ignore-field`, `--compare-field`; `log-format` and `ignore-fields`
  in profiles).
- **Built-in presets** for Spring Boot, nginx/Apache access logs, Kubernetes/containerd,
  journald/syslog, Android logcat and Python logging, detected automatically (`--preset`,
  `logdelta rules --presets`). Each preset's rules can be switched off individually.
- **Message templates.** Lines are grouped into templates (`Connection to <*> failed`), so a
  change in how often a kind of message occurs is reported once, not once per value.
- **Flaky lines.** With several good runs, lines that vary between them are listed with a
  suggested mask or ignore rule; one click applies it in the app.
- **Secret redaction.** Exported reports remove passwords, tokens, API keys (AWS, GitHub,
  GitLab, Slack, Google, Stripe, npm, OpenAI-style), JWTs, private keys, credentials in URLs and
  e-mail addresses. On by default for reports; `--no-redact` and `--redact REGEX` adjust it,
  `logdelta rules --redaction` lists the detectors.
- **Time windows.** `--since` and `--until` compare only part of each run (absolute times, time
  of day, or `+5m` from the first line); reading stops at the end of the window.
- **Error-over-time chart** in the app's summary, the HTML report and as a sparkline in the
  terminal and Markdown summary (`problemTimeline` in JSON).
- **Command palette** (Ctrl+Shift+P / ⌘⇧P) that runs any menu command by name, and a
  **keyboard shortcut reference** (Help > Keyboard Shortcuts) built from the menus.
- **Reopen tabs at start.** The app restores the previous session's tabs, rules and bookmarks.
- **Accessibility:** a colour-blind-safe palette, a high-contrast theme and screen-reader names
  for the comparison view, buttons and charts.
- **Korean translation**; the app's texts come from resource bundles (View > Language).
- **Opt-in update check** against GitHub Releases (Help > Check for Updates).
- **Shell completions** for bash, zsh, fish and PowerShell (`logdelta completion SHELL`) and a
  **man page** (`logdelta manpage`), installed by the .deb package and shipped with the others.
- **Automatic profiles.** A `.logdelta` file (or `.logdelta/profile.ldprofile`) in the working
  folder or a parent, up to the repository root, is used automatically (`--no-profile`, or
  `LOGDELTA_AUTO_PROFILE=0`, turns it off).
- Maven wrapper (`./mvnw`), YAML issue forms, release-note categories and a GUI smoke test
  (`logdelta gui --smoke-test`).

### Changed
- **Large files** are memory-mapped and processed in compact form: two 300 MB logs compare in a
  few minutes with a 4 GB heap. Installed launchers use up to 75 % of the machine's memory.
- Lines that differ only in numbers are paired as modified lines more often.
- Reports are redacted by default, and so are `check` output (CI logs are often public) and
  terminal views written to a file with `-o`; terminal output is not.
- `normalize` and `explain` prepare lines exactly like a comparison (time window, JSON/logfmt
  fields, presets).
- Report and `-o` files are written into folders that don't exist yet.
- JSON reports have new fields (`detected`, `problemTimeline`, `redactedSecrets`,
  `insights.templateShifts`, `insights.flakyLines`, `occurrences` on problems, `fieldChanges` on
  rows, `firstLine` on each input, and `options.logFormat`, `preset`, `ignoreFields` and
  `timeWindow`); the schema version stays 1.
- Profiles accept `log-format`, `ignore-fields`, `preset`, `since`, `until`, `redact` and a
  `[redact]` section.
- CI: job time limits, a concurrency group, grouped Dependabot updates, GUI tests under Xvfb.

## [1.1.0] - 2026-11-17

### Added
- **Several baselines.** Compare a candidate with more than one known-good run (`diff a b c`,
  `--baseline`, **+ Baselines** in the app). Differences another good run shares are marked as
  normal variation: faded with a `≈` marker, not counted as changes or new problems.
- **Timing.** Timestamps are parsed (ISO-8601, syslog, Apache, time of day, epoch); the app can
  show each line's elapsed time, and slower or faster phases between matching lines are reported
  in the app (Timing tab, slow-phase bars), the CLI summary and every report.
- **Suggested rules.** When modified lines differ only in one key's value (`session=…`), the
  matching mask is suggested; one click applies it in the app.
- **Multi-line entries.** Optionally compare a line and its stack trace as one entry
  (`--multiline`, `multiline-entries` in profiles).
- **`logdelta check`**: a CI gate that fails only on new problems (or errors, or any change),
  with `--baseline`, `--baseline-dir`, `--update-baseline`, `--update-on-pass`, `--keep`,
  `--github` annotations and job summaries.
- **Folder comparison** in the CLI (`logdelta dir1 dir2`, `--include`, `--exclude`, rotated-name
  pairing) and the app (a table of files; double-click opens a pair).
- **URLs and pasted text** as inputs (`--header`, `--timeout`; **File > Open URLs** and **Paste as
  Candidate** in the app).
- **JUnit XML** report format; `--report FORMAT:FILE` to write extra reports; folder reports in
  Markdown, JSON and JUnit.
- **GitHub Action** (`action.yml`) that runs `check`, posts or updates a pull-request comment and
  writes JUnit XML.
- **Desktop app:** tabs; a filter bar (all lines, changes, new problems, errors, text); line
  wrapping; syntax colouring of timestamps, levels, threads and loggers; bookmarks with notes;
  session files (`.ldsession`) that save sources, rules, bookmarks and notes; live tail; a
  Timing tab; an application icon; a first-run tour.
- **Native installers** built with `jpackage` (`scripts/package.sh`; msi, dmg, deb and rpm in
  CI) with a bundled runtime and a `logdelta-cli` launcher.

### Changed
- JSON reports have new fields (`extraBaselines`, `stats.expected`, `timing`,
  `suggestedRules`, `options.multilineEntries`, `rows[].expected`); the schema version stays 1.
- The example logs now include a second good run and realistic timing.

## [1.0.0] - 2026-11-12

### Added
- Core engine: log reading with gzip and charset detection; 23 built-in normalization rules;
  custom rules with group references; ignore patterns; case/whitespace options.
- Myers (linear space, cost-limited) and Patience diff algorithms.
- Rarity-weighted pairing of modified lines, moved-line detection and word-level highlights.
- Insights: new and resolved problems (grouped), frequency shifts, first divergence.
- Reports: self-contained HTML, JSON (schema version 1), Markdown, unified diff.
- Profile file format shared by the CLI and GUI.
- CLI: `diff`, `normalize`, `explain` and `rules` commands; side-by-side, unified and summary
  terminal output; `diff`-compatible exit codes; `--exit-code problems` for CI.
- Desktop app: side-by-side view with a resizable divider, folding, overview ruler, search, normalized-text toggle,
  insights and rules sidebar, rule editor with live tester, drag and drop, recent files,
  auto-reload, light/dark/system themes, exports.
- All-in-one jar and builds without Maven.
- Requires Java 25 (LTS). Uses sealed types, record patterns, pattern-matching `switch`, unnamed
  variables, sequenced collections and a virtual thread for file watching.
- API documentation written as Markdown doc comments (`///`).

[Unreleased]: https://github.com/Yeongwonhan-Jeolmang/logdelta/compare/v2.0.0...HEAD
[2.0.0]: https://github.com/Yeongwonhan-Jeolmang/logdelta/compare/v.1.1.0...v2.0.0
[1.1.0]: https://github.com/Yeongwonhan-Jeolmang/logdelta/compare/v1.0.0...v.1.1.0
[1.0.0]: https://github.com/Yeongwonhan-Jeolmang/logdelta/releases/tag/v1.0.0
