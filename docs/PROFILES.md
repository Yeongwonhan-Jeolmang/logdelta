# Profiles

A profile (`*.ldprofile`) stores comparison options, ignore patterns and rules in a small text
file. Profiles are shared by the CLI (`-p FILE`) and the GUI (**File > Load Profile / Save
Profile As**), so a team can keep one per service in its repository.

The GUI also saves the active setup as a profile in `~/.logdelta/session.ldprofile` and restores
it on the next start. [Session files](SESSIONS.md) embed a profile too.

## Automatic profiles

The CLI picks up a profile without `-p` when the working folder, or any parent up to the
repository root (the first folder containing `.git`), has either

- a file named `.logdelta`, or
- a folder `.logdelta/` containing `profile.ldprofile`.

The search also stops at your home folder, and folders that every user may write to (such
as `/tmp`) are skipped, so nobody else can slip a profile into your comparisons. It prints
`logdelta: using profile PATH` to standard error (unless `-q`). An explicit `-p` wins;
`--no-profile` or the environment variable `LOGDELTA_AUTO_PROFILE=0` turns the search off.
Commit the file next to your code so everyone, and CI, compares runs the same way.

## Example

```ini
# Checkout service: compare test runs
[options]
algorithm = patience
ignore-whitespace = true
detect-moves = true

[ignore]
pattern = Heartbeat - heartbeat ok
pattern = ^DEBUG .*cache (hit|miss)

# Built-in rules only need 'enabled'.
[rule epoch]
enabled = false

[rule order-id]
name = Order number
category = identifier
pattern = ORD-\d{6}
replacement = ORD-<ID>
description = Order numbers are generated per test run

[rule amount]
name = Payment amount
category = metric
pattern = (amount=)\d+\.\d{2}
replacement = $1<AMOUNT>
```

## Syntax

- One `key = value` per line. Whitespace around keys and values is ignored.
- Lines starting with `#` or `;` are comments. Comments must be on their own line, because `#`
  is common in regular expressions.
- To keep leading or trailing spaces, wrap the value in double quotes. Inside quotes, `\"` is a
  quote and `\\` a backslash. Outside quotes, backslashes are literal, so regular expressions
  are written exactly as usual.
- Booleans: `true`/`false`, `yes`/`no`, `on`/`off`, `1`/`0`.

## Sections

### `[options]`

| Key | Values | Default |
|---|---|---|
| `algorithm` | `myers`, `patience` | `myers` |
| `ignore-case` | boolean | `false` |
| `ignore-whitespace` | boolean | `false` |
| `detect-moves` | boolean | `true` |
| `inline-diff` | boolean | `true` |
| `minimal` | boolean | `false` |
| `multiline-entries` | boolean; compare a line and its continuation lines (stack traces) as one entry | `false` |
| `builtins` | boolean; `off` disables every built-in rule not enabled explicitly below | `on` |
| `order` | comma-separated rule ids; listed rules run first in that order, the rest keep their position | (none) |
| `log-format` | `auto`, `plain`, `json`, `logfmt` | `auto` |
| `ignore-fields` | comma-separated JSON/logfmt field names or globs (`*.id`); an undotted name also matches the last part of a nested name | timestamps and trace/span ids |
| `preset` | `auto`, `none` or a preset id (`spring-boot`, `access-log`, `kubernetes`, `journald`, `logcat`, `python`) | `auto` |
| `since`, `until` | a date-time (`2026-11-12T08:49`), a time of day (`08:49:03`) or an offset from the first line (`+5m`, `+90s`, `+1h30m`) | whole log |
| `redact` | boolean; remove secrets from exported reports | `true` |

### `[ignore]`

Repeat `pattern = REGEX` once per pattern. A line is left out of the comparison when any pattern
is found anywhere in it.

### `[redact]`

Repeat `pattern = REGEX` once per pattern. Matches are replaced with `[REDACTED]` in
exported reports, in addition to the built-in detectors (see [Reports](REPORTS.md#secrets-in-reports)).

### `[rule ID]`

For a **built-in** rule, only `enabled` is allowed, unless you redefine the rule completely by
giving a `pattern` (then all fields apply).

For a **custom** rule:

| Key | Required | Notes |
|---|---|---|
| `pattern` | yes | Java regular expression |
| `replacement` | no | default `<MASKED>`; `$1`/`${name}` insert captured groups |
| `name` | no | default: the id |
| `category` | no | `timestamp`, `identifier`, `network`, `process`, `memory`, `metric`, `path`, `custom` |
| `case-insensitive` | no | default `false` |
| `enabled` | no | default `true` |
| `description` | no | free text |

Custom rules run before the built-in ones, in file order, unless `order` says otherwise.

Rules of a [preset](RULES.md#presets) are switched off the same way as built-in ones, by id:

```ini
[rule spring-boot.pid]
enabled = false
```

## Errors

Invalid profiles are rejected with the line number of the problem, for example:

```text
logdelta: Invalid profile team.ldprofile: line 14: Rule 'order-id' has an invalid pattern: Unclosed group near index 8
```

## Creating a profile

- In the GUI: set things up, then **File > Save Profile As**.
- On the command line: `logdelta rules [options] --export team.ldprofile` writes the effective
  options, including any `--mask`, `--enable`, `--disable` and `--ignore` you passed.
