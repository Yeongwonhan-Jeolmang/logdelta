# Command-line reference

```text
logdelta [diff] [options] BASELINE [MORE_BASELINES...] CANDIDATE
logdelta [diff] [options] BASELINE_DIR CANDIDATE_DIR
logdelta [diff] [options] --session FILE
logdelta check [options] (--baseline FILE... | --baseline-dir DIR) CANDIDATE
logdelta normalize [options] FILE
logdelta explain [options] [LINE...]
logdelta rules [options]
logdelta gui [BASELINE... CANDIDATE | BASELINE_DIR CANDIDATE_DIR | SESSION]
logdelta cli [ARGS...]
logdelta help [COMMAND]
logdelta --version
```

When the first argument is not a command name, `diff` is assumed. `gui` is available in the
all-in-one jar. Running the jar without arguments also opens the GUI; `cli` forces the command
line (the native installers ship a `logdelta-cli` launcher for this).

## Inputs

Every place that takes a log accepts:

- a file, plain or gzip-compressed (detected from the content, not the name);
- `-` for standard input;
- an `http://` or `https://` URL, downloaded into memory. Use `-H 'Authorization: Bearer …'`
  (repeatable) for servers that need a header and `--timeout SECONDS` (default 30) for slow
  ones. Downloads larger than 512 MB are refused.

**More than one baseline.** With three or more inputs, all but the last are known-good runs.
The first is shown side by side; a difference that any other good run shows too (a line that
is sometimes there, a value that varies between good runs) is marked as *normal variation*: it
stays visible (faded, marked `≈`) but no longer counts as a change or a new problem.
`--baseline FILE` adds a good run without changing the positional order.

**Folders.** Two folders are compared file by file, matched by relative path. Files whose names
differ only in digits (dates, counters: `app-2026-11-02.log` and `app-2026-11-03.log`) are
paired too, when the match is unambiguous. The table lists every file with its similarity and
number of new problems; files that only exist in the candidate folder are still scanned for
errors. Hidden files are skipped, and so are binary files.

## Options shared by all commands that normalize

| Option | Description |
|---|---|
| `-p, --profile FILE` | Load options, ignore patterns and rules from a [profile](PROFILES.md). Command-line options override it. |
| `-m, --mask REGEX[=>REPL]` | Add a rule. Matches are replaced by `REPL` (default `<MASK>`). Repeatable; these rules run before all others. |
| `--enable ID` / `--disable ID` | Switch a rule on or off by id (see `logdelta rules`). `all` affects every rule. Repeatable. |
| `--no-builtins` | Disable all built-in rules. |
| `-I, --ignore REGEX` | Leave out lines matching `REGEX` (searched anywhere in the line). Repeatable. |
| `-i, --ignore-case` | Compare case-insensitively. |
| `-w, --ignore-whitespace` | Treat whitespace runs as one space; ignore leading/trailing whitespace. |
| `-M, --multiline` | Treat a line and its continuation lines (stack traces, wrapped messages) as one entry when diffing (`diff` and `check`). |

Precedence, lowest to highest: built-in defaults, then the profile, then command-line options.

## `diff`

| Option | Description |
|---|---|
| `-f, --format FMT` | `side-by-side` (`sbs`), `unified`, `summary`, `html`, `json`, `markdown` (`md`) or `junit`. Folders: `summary`, `markdown`, `json`, `junit`. |
| `-o, --output FILE` | Write to a file. Without `--format` the format follows the extension (`.html`, `.json`, `.md`, `.xml`, `.diff`). |
| `-r, --report [FMT:]FILE` | Also write a report file, in addition to the normal output. Repeatable. The format follows the extension unless given as a prefix, e.g. `junit:build/logdelta.xml`. |
| `-C, --context N` | Unchanged lines shown around each change (default 3). |
| `--full` | Include all unchanged lines. In HTML they are collapsed until clicked. |
| `--max-hunks N` | Show at most N change blocks. |
| `-a, --algorithm NAME` | `myers` (default) or `patience`. |
| `--minimal` | Always compute a minimal diff, even when it is expensive. |
| `--no-moves` | Do not detect moved lines. |
| `-b, --baseline FILE` | Another known-good run. Repeatable. |
| `-s, --session FILE` | Compare the logs saved in a [session](SESSIONS.md), with its options. |
| `--include GLOB` / `--exclude GLOB` | Folders: which files to compare, e.g. `--include '*.log'`. A pattern without `/` also matches names in subfolders. Repeatable. |
| `--no-recursive` | Folders: ignore subfolders. |
| `--no-rotated` | Folders: only pair files with identical relative paths. |
| `-H, --header 'NAME: VALUE'` | HTTP header for URL inputs. Repeatable. |
| `--timeout SECONDS` | Timeout for URL inputs (default 30). |
| `-e, --encoding CHARSET` | Input encoding. Default: auto-detect (BOM, UTF-8, Latin-1). |
| `--width N` | Width for side-by-side and summary output (default `$COLUMNS` or 160). |
| `--color WHEN` | `auto` (default), `always` or `never`. `auto` honours [`NO_COLOR`](https://no-color.org). |
| `--exit-code MODE` | `any` (default), `problems` or `never`. See below. |
| `-q, --quiet` | Print nothing; only set the exit status. |
| `--progress` | Show progress on standard error (useful for very large files). |

Default format: `side-by-side` when standard output is a terminal, `unified` otherwise.

### Exit status

| Code | `--exit-code any` | `--exit-code problems` | `--exit-code never` |
|---|---|---|---|
| 0 | no differences | no new warnings or errors | always |
| 1 | differences (unexplained by other baselines) | new warnings or errors | never |
| 2 | error (bad usage, missing file, ...) | error | error |

### Reading the side-by-side output

```text
  12 - removed line                     |
  13 ~ text with old value              |   14 ~ text with new value
                                        |   15 + added line
  16 ↕ moved line                       |
       ⋯ 120 unchanged lines
```

Removed text is red and added text green. Moved lines are magenta and marked `↕` (`^` on
terminals without Unicode). Word-level changes appear in reverse video, and masked values are
dimmed.

### Summary output

`-f summary` prints, besides the counts and problems:

- **Timing**: when both logs have timestamps, the run durations and the phases between matching
  lines that got notably slower or faster (for example `132 ms → 15.1 s` between "Validating
  cart" and "Responded 502"). A phase is reported when it changed by at least 250 ms and by a
  factor of 1.5.
- **Suggested rules**: when many modified lines differ only in the value of one key (such as
  `session=…`), the `--mask` that would hide that noise.

## `check`

A CI gate. It compares a run with known-good baselines and **fails only when something new
goes wrong**: by default when the candidate has warnings or errors that no baseline has. Other
differences are reported but do not fail the build.

```sh
logdelta check --baseline expected/app.log build/app.log
logdelta check -b good-1.log -b good-2.log --report junit:build/logdelta.xml build/app.log
logdelta check --baseline-dir .logdelta/runs --update-on-pass --keep 10 build/app.log
logdelta check --baseline logs/expected logs/actual --include '*.log'      # folders
```

| Option | Description |
|---|---|
| `-b, --baseline FILE` | A known-good run: a file, URL or (with a candidate folder) a folder. Repeatable; the first is the one reports show side by side. |
| `-B, --baseline-dir DIR` | Every file in `DIR` is a known-good run, newest first. |
| `-u, --update-baseline` | Accept the candidate as the new baseline and pass, whatever the result. |
| `--update-on-pass` | Update the baseline only when the check passes (a rolling baseline). |
| `--keep N` | With `--baseline-dir`: keep the newest `N` stored runs (default 5). Only runs that `check` stored (named `<date>-<time>_<name>`) are ever deleted; other files in the folder are used as baselines but left alone. |
| `--fail-on WHAT` | `problems` (new warnings or errors; default), `errors` (new errors only) or `changes` (any unexplained difference). |
| `-f, --format FMT` | Standard output format: `summary` (default), `markdown`, `json` or `junit`. |
| `-o, --output FILE` | Write that output to a file. |
| `-r, --report [FMT:]FILE` | Also write a report; repeatable. |
| `--github` | Print GitHub Actions annotations for new problems and append the Markdown summary to `$GITHUB_STEP_SUMMARY`. |
| `-q, --quiet` | Print only the verdict line. |

All normalization options, `--multiline`, `--algorithm`, the folder options, `--header`,
`--timeout` and `--encoding` work as for `diff`.

**Updating baselines.** With `--baseline FILE`, the file is replaced by the candidate. With
`--baseline-dir`, the candidate is added as `<date-time>_<name>` and older runs beyond `--keep`
are deleted. With a candidate folder, the candidate's files are copied over the baseline folder
(a rotated file replaces its predecessor). If the baseline does not exist yet, either update
option creates it from the candidate and passes, so the first CI run bootstraps itself.

**Exit status:** `0` pass, `1` fail, `2` error (bad usage, unreadable input, missing baseline
without an update option). When some `--baseline` files exist and others are missing, `check`
stops with an error instead of creating them, so an existing baseline is never replaced by a
bootstrap.

**Output.** The summary is followed by a verdict line. With `-f markdown|json|junit` on standard
output, the verdict goes to standard error so the report stays machine-readable:

```text
FAIL  6 new problem(s)
PASS  no new problems; baseline updated: .logdelta/runs/20261117-084316_app.log
```

For GitHub, the [LogDelta action](GITHUB_ACTION.md) wraps `check` and also posts the summary as a
pull-request comment.

## `normalize`

Prints a file the way LogDelta sees it.

| Option | Description |
|---|---|
| `-n, --line-numbers` | Prefix lines with their original line number. |
| `--keep-ignored` | Also print lines matched by `--ignore`. |
| `-e, --encoding CHARSET` | Input encoding. |

```sh
# The most frequent message templates in a log
logdelta normalize app.log | sort | uniq -c | sort -rn | head
```

## `explain`

Shows which rule masked which characters. Reads lines from standard input if none are given.

```text
$ logdelta explain "2026-11-12 08:49:03 INFO pid=42 took 3ms"
  2026-11-12 08:49:03 INFO pid=42 took 3ms
  ───────────────────      ──────      ───
  [0..19]   iso-timestamp  → <TIMESTAMP> ISO-8601 timestamp
  [25..31]  pid            → pid=<N>     Process / thread id
  [37..40]  duration       → <DURATION>  Duration
  = <TIMESTAMP> INFO pid=<N> took <DURATION>
```

## `rules`

Lists the effective rules in evaluation order.

| Option | Description |
|---|---|
| `-v, --verbose` | Also show patterns and descriptions. |
| `--export FILE` | Write the effective options as a profile instead of listing. |

```sh
logdelta rules --mask 'ORD-\d+=>ORD-<ID>' --disable epoch --export team.ldprofile
```

## Environment

| Variable | Effect |
|---|---|
| `COLUMNS` | Terminal width for side-by-side and summary output. |
| `NO_COLOR` | Disables colour when `--color auto`. |
| `JAVA_OPTS` / `-Xmx` | For very large logs, give the JVM more memory: `java -Xmx4g -jar logdelta.jar ...`. |
