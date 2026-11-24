# Architecture

## Modules

```text
                ┌──────────────┐
                │ logdelta-app │  Launcher: GUI without arguments, CLI otherwise; shaded jar
                └──────┬───────┘
          ┌────────────┴────────────┐
   ┌──────▼───────┐          ┌──────▼───────┐
   │ logdelta-cli │          │ logdelta-gui │  Swing desktop application
   └──────┬───────┘          └──────┬───────┘
          └────────────┬────────────┘
                ┌──────▼────────┐
                │ logdelta-core │  no dependencies, no UI
                └───────────────┘
```

`logdelta-core` contains everything needed to compare logs and write reports, so the CLI and
GUI stay thin and the engine can be embedded elsewhere. No module depends on anything outside
the JDK.

## Core packages

| Package | Responsibility |
|---|---|
| `io` | `LogReader` (streaming or memory-mapped read, gzip detection by magic bytes, BOM/UTF-8/Latin-1 detection, time-window slicing), `LogFile`, and `LogSources` (paths and `http(s)` URLs, with size limits and headers) |
| `structured` | `LogFormat`, `StructuredParser` (JSON lines and logfmt, format detection), `FieldFilter` (ignored fields), `StructuredLines` (field changes, message/level/time fields) |
| `preset` | `Presets`: built-in profiles for known formats, with detection |
| `redact` | `Redactor` (secret detectors) and `Redaction` (re-mapping highlight ranges after removal) |
| `normalize` | `Rule`, `RuleSet`, `BuiltinRules`, `Normalizer`, `LineFilter` |
| `diff` | `MyersDiff`, `PatienceDiff`, `InlineDiff`, edit script types |
| `model` | immutable result types: `ProcessedLine`, `DiffRow`, `RowKind`, `DiffStats`, `Hunk` |
| `engine` | `LogDeltaEngine` (the pipeline), `DiffOptions`, `DiffResult`, line pairing, multi-line entries |
| `analysis` | severity detection and `Insights` (new/resolved problems, frequency shifts), `TemplateMiner` (message templates), flaky lines, `ProblemTimeline` |
| `timing` | `TimestampParser`, per-log `Timeline`s, `TimingAnalyzer` (slower and faster phases) and `TimeWindow` (`--since`/`--until`) |
| `suggest` | `RuleSuggester`: rules that would hide run-specific differences |
| `folder` | `FolderComparer`: pairing files of two folders and comparing them in parallel |
| `report` | HTML, JSON, Markdown, JUnit XML and unified writers; `FolderReports` |
| `config` | profile file format (`ProfileIO`) |
| `session` | session files (`SessionIO`, `Session`, `Bookmark`) |
| `util` | progress/cancellation, a small JSON writer and parser, text helpers |

## The pipeline

`LogDeltaEngine.compare` runs these stages. Progress is reported and cancellation is checked
throughout.

1. **Read.** `LogReader` streams the file (or a download) and splits lines on LF, CRLF and CR.
   Files of 32 MB and more in UTF-8 or Latin-1 are memory-mapped in chunks instead. With a time
   window, a slicer skips lines before `since` and stops reading after `until`.
   **Detect.** A sample of lines decides the format (JSON lines or logfmt when 60 % parse) and the
   preset (when 50 % match its signature); a preset's rules are added in front of the others.
2. **Filter.** Lines matching an ignore pattern are dropped. Kept lines keep their original line
   numbers. Each kept line's timestamp is parsed (`TimestampParser`: ISO-8601, syslog, Apache,
   time of day, epoch) for the timing analysis.
3. **Normalize.** Structured lines are first parsed into fields; ignored fields are masked as
   `<IGNORED>` and the fields are sorted by name, so key order does not matter. For each line, `Normalizer` keeps a list of segments, each either untouched
   original text or a mask. Every enabled rule searches only the untouched segments, using
   *transparent bounds* so that `\b` and look-arounds still see the neighbouring characters.
   This gives three useful properties:
   - a later rule never re-matches an earlier rule's placeholder (no `<N>` inside `<TIMESTAMP>`);
   - each mask knows its exact position in the original line, which the GUI and reports use to
     show masked text and to exclude it from word-level highlights;
   - rules can't produce surprising results through overlapping replacements.

   The **comparison key** is the normalized text plus the case and whitespace options. Large files
   are normalized in parallel, and matchers are reused per thread.
4. **Intern.** Keys are mapped to integers, so the diff algorithms compare ints instead of
   strings.
5. **Diff.** With *multi-line entries*, lines are first grouped into entries (a line with a
   timestamp or level plus its continuation lines, such as a stack trace), entries are diffed by
   their combined key, and only the lines inside each pair of matching entries are diffed again.
   This keeps a stack trace attached to the error it belongs to. Then choose one:
   - **Myers** (`MyersDiff`): the O(ND) algorithm with the linear-space bisection
     refinement. Common prefixes and suffixes are stripped first, which makes the typical log
     comparison (mostly identical) cheap. Unless `minimal` is set, a cost limit ends the search
     after about `max(256, 2·√(N+M))` steps and splits at the furthest point reached. The result
     is still a valid edit script, and the worst case stays bounded.
   - **Patience** (`PatienceDiff`): anchors on lines that are unique in both ranges, takes the
     longest increasing subsequence of those anchors (patience sorting), and recurses between
     them. Gaps without unique lines fall back to Myers.
6. **Build rows.** Runs of deletions and insertions between equal lines form a *change block*.
   Inside a block, `LinePairer` decides which removed and added lines are "the same line,
   modified". It scores candidate pairs with a Dice coefficient over word multisets, where each
   token is weighted by its inverse document frequency (`TokenWeights`), so boilerplate such as
   timestamps, levels and logger names counts for little. A dynamic programme then picks the
   order-preserving set of pairs with the highest total score above a threshold. Unpaired lines
   become pure removals or additions.
7. **Detect moves.** Removed lines whose key appears among added lines elsewhere are linked as
   moved.
8. **Mark expected changes.** With extra baselines, each extra log is normalized and its keys
   counted. An added or modified candidate line whose key appears in any extra baseline, or a
   removed line that is missing from at least one extra baseline, is *expected*: the difference
   is normal variation between good runs. Expected rows stay in the result but do not count
   towards `identical`, new problems or frequency shifts.
9. **Inline diff.** For modified rows, `InlineDiff` tokenizes both lines (words, whitespace,
   punctuation; each mask is one token compared by its placeholder) and diffs the tokens. Adjacent
   changed tokens are merged into phrases.
10. **Analyze.** `SeverityDetector` classifies lines by level keywords, structured `level=`
    fields and exception or stack-trace markers. `InsightAnalyzer` groups consecutive new or
    resolved problem rows and finds frequency shifts by counting comparison keys on both sides.
    `TemplateMiner` groups messages into templates in two passes (tokens with digits become
    `<*>`, then positions with at least three distinct words), and reports template shifts that
    cover several variants. With extra baselines, rows that differ between good runs become
    *flaky lines*, grouped by the rule (`RuleSuggester.rulesFor`) or ignore pattern that fixes
    them. `ProblemTimeline` counts warnings and errors in 60 bins of time or position.
11. **Timing.** `TimingAnalyzer` walks the unchanged rows that carry timestamps on both sides
    (the anchors) and compares the time between consecutive anchors. Stretches that changed by at
    least 250 ms (or 2% of the run) and by a factor of 1.5 become slower or faster phases.
12. **Suggest rules.** `RuleSuggester` tokenizes the modified rows that are still different and
    looks for a single `key=value` (or `key: value`) whose value differs while the rest of the line
    is equal. A candidate rule is kept only if it has many distinct values (so it does not hide a
    real change such as an HTTP status) and, applied in a trial normalization, actually makes
    lines equal.

Reports call `DiffResult.redacted(Redactor)` first, which returns a copy with secrets replaced
and highlight and mask ranges re-mapped to the shorter text.

For very large inputs, rows are stored in compact parallel arrays, `TokenWeights` is built from a
sample, and `LinePairer` tokenizes lines lazily, only for change blocks it actually scores.

`DiffResult` is immutable. The GUI's `DiffViewModel` maps result rows to visual rows (folds), and
`SearchModel` finds matches in it. Neither modifies the result.

## GUI structure

| Class | Role |
|---|---|
| `MainFrame` | window, menus, toolbar, tab strip, status bar; implements `TabHost` and routes commands to the selected tab |
| `tab.ComparisonTab` | one comparison: file bar, diff view, sidebar, bookmarks and notes, sessions, live tail; implements `ComparisonController.Listener` |
| `tab.FolderTab` | folder comparison table; opens pairs in new tabs |
| `ComparisonController` | owns sources (`Source`: file, URL or pasted text), extra baselines, options and the current result; runs comparisons with `SwingWorker`; debounces option changes; discards superseded runs |
| `DiffView` | header, filter bar, scroll pane, overview ruler, shared horizontal scroll bar, find bar, keyboard navigation |
| `DiffCanvas` | custom-painted side-by-side view; paints only rows inside the clip; wrapping with a lazily rebuilt prefix-sum row layout; syntax colours, elapsed time, bookmark flags, slow-phase bars; tooltips |
| `SyntaxHighlighter` | finds timestamps, levels, threads and logger names in a line's prefix |
| `DiffViewModel`, `ViewFilter` | visual rows: folds for collapsed regions, gaps for filtered-out rows |
| `OverviewRuler` | change map with click-to-jump |
| `sidebar.*` | Summary, Insights, Timing, Rules (with suggested rules) and Notes tabs |
| `tour.Tour` | first-run tour: a glass pane with an animated spotlight and a card per step |
| `util.AppIcon` | the application icon, drawn with Java 2D; also writes the installer icons |
| `RuleEditorDialog` | rule list, form with live validation, tester |
| `Theme`, `DiffPalette` | tuned Nimbus light/dark/high-contrast look and feel; diff colours, including colour-blind-safe variants |
| `AppSettings` | Java Preferences plus the session profile and the reopened tabs |
| `command.*` | `MenuCommands` (walks the menu bar), `CommandPalette`, `ShortcutsDialog`: both are built from the menus, so they never go out of date |
| `i18n.I18n` | `tr("English {0}", args)`; translations in `messages_<lang>.properties` keyed by the English text |
| `view.ProblemTimelineChart` | the problems-over-time chart in the Summary tab |
| `util.UpdateChecker` | opt-in check of the latest GitHub release |
| `SmokeTest` | `gui --smoke-test`: starts the real app and checks it end to end (CI, under Xvfb) |
| `FileWatcher` | `WatchService` with a quiet period, for auto-reload |

The canvas tracks the viewport width and draws both panes with one shared horizontal offset, so
the panes always stay aligned. When wrapping, each sub-line is painted by drawing the whole line
with a larger horizontal offset into a clip one line high, so highlights, masks, search matches
and syntax colours need no wrap-specific code. All Swing access happens on the event dispatch thread; only the
engine runs on worker threads.

## Design decisions

- **No dependencies.** The tool should build and run anywhere a JDK exists, including offline
  and restricted environments. This is why the CLI parser, JSON writer and icons are small
  in-house implementations.
- **Swing instead of JavaFX.** Swing ships with every JDK, so there are no platform-specific
  runtime downloads. A custom-painted canvas was needed for speed anyway.
- **Masks instead of string replacement.** Keeping the original text with positioned masks costs
  a little bookkeeping, but it enables accurate highlights, tooltips and the original/normalized
  toggle.
- **Rarity-weighted pairing.** Plain token similarity pairs unrelated log lines because they share
  a long prefix. IDF weighting fixed this without format-specific parsing.
- **Profiles as plain text.** Profiles are easy to diff, review and keep next to the code they
  describe. The GUI's session uses the same format.

## Testing

Each module has JUnit 5 tests (`./mvnw verify`). Highlights:

- the diff algorithms are checked against an LCS dynamic programme on hundreds of random inputs
  (minimality) and for script validity (each script must rebuild `b` from `a`);
- every built-in rule has positive and negative examples;
- the CLI is tested end to end against temporary files, including exit codes, `check` with
  baseline updates, folders, sessions and GitHub output;
- downloads are tested against an in-process HTTP server;
- JUnit XML output is parsed with the JDK's XML parser to prove it is well formed;
- the GUI's models (folding, filtering, search, syntax colouring, pattern suggestions) are tested
  without a display.
- every `tr(...)` text in the GUI must have a Korean translation with the same placeholders
  (`I18nTest` scans the sources);
- `MainFrameTest` builds the real window (under Xvfb in CI) and checks for duplicate shortcuts and
  buttons without an accessible name, and `gui --smoke-test` runs the whole app once.
