# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

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

[Unreleased]: https://github.com/Yeongwonhan-Jeolmang/logdelta/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/Yeongwonhan-Jeolmang/logdelta/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/Yeongwonhan-Jeolmang/logdelta/releases/tag/v1.0.0
