# Session files

A session (`*.ldsession`) saves one comparison: its sources, its options and rules, and the
reviewer's bookmarks and notes. The desktop app writes it with **File > Save Session**
(Ctrl+S); `logdelta diff --session FILE` re-runs the same comparison on the command line.

A session file is a [profile](PROFILES.md) with two extra kinds of section, so everything a
profile can hold is part of the session:

```ini
# LogDelta session (1.1.0)
[session]
left = logs/good.log
right = https://ci.example.com/job/812/artifact/app.log
# repeatable: more known-good runs
baseline = logs/good-2.log
notes = "Started after the pool resize.\nSee ticket OPS-311."

[bookmark]
side = right
line = 57
text = "2026-11-02 10:14:07.902 ERROR [pool-3] Connection refused"
note = "first sign of the outage"

[options]
algorithm = patience
ignore-whitespace = true
multiline-entries = true

[rule order-id]
name = Order number
pattern = ORD-\d{6}
replacement = ORD-<ID>
```

## `[session]`

| Key | Meaning |
|---|---|
| `left` | Baseline: a path or an `http(s)` URL. Required. |
| `right` | Candidate. Required. |
| `baseline` | Another known-good run. Repeatable. |
| `notes` | Free-form notes. |

Relative paths are resolved against the folder that contains the session file. When saving, the
app writes paths below that folder relatively, so a folder holding logs and a session can be
zipped and shared. Logs that were pasted from the clipboard are written next to the session as
`<session>-baseline.log` / `<session>-candidate.log`.

Request headers for URLs (such as tokens) are never saved.

## `[bookmark]`

One section per bookmark.

| Key | Meaning |
|---|---|
| `side` | `left` (baseline) or `right` (candidate). |
| `line` | 1-based line number. |
| `text` | The line's text when the bookmark was set. Optional. |
| `note` | The note. Optional. |

When the log has changed since the bookmark was set, LogDelta looks for `text` near the stored
line number (up to 2,000 lines either way) and moves the bookmark there, so bookmarks survive
regenerated logs.

## Values

Values may be written in double quotes with `\n`, `\t`, `\"` and `\\` escapes (the app always
writes them that way); unquoted values are taken as they are, without leading and trailing
spaces. Lines starting with `#` or `;` are comments. All other sections are read as a profile;
see [PROFILES.md](PROFILES.md).
