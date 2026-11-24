# Normalization rules

Before two logs are compared, every line is **normalized**: each rule replaces the parts of a
line that differ between runs, but don't indicate a real change, with a placeholder.

```text
original    2026-11-12 08:49:03.117 INFO [pool-1-thread-4] Order 3f2b8c1e-9a4d-4e7b-8c2a-1d5e6f7a8b9c took 42ms
normalized  <TIMESTAMP> INFO [pool-1-thread-<N>] Order <UUID> took <DURATION>
```

Two lines are equal when their normalized forms are equal (plus the case and whitespace
options).

## How rules are applied

- Rules run **in order**, top to bottom.
- A rule only searches text that no earlier rule has replaced. Specific rules should therefore
  come before generic ones: a full timestamp before a bare time, a UUID before a hex hash.
- Word boundaries (`\b`) and look-arounds see the original neighbouring characters, even across
  a region an earlier rule replaced.
- Each replaced region remembers its position in the original line. That is how the GUI can show
  the original text with masked parts underlined, and why masked values are never highlighted as
  word-level changes.

## Built-in rules

| Id | Name | Category | Replaced by | Default | Examples |
|---|---|---|---|:---:|---|
| `iso-timestamp` | ISO-8601 timestamp | Timestamps | `<TIMESTAMP>` | on | 2026-11-12T08:49:03.123Z, 2026-11-12 08:49:03,123 |
| `syslog-timestamp` | Syslog timestamp | Timestamps | `<TIMESTAMP>` | on | Nov 12 08:49:03 |
| `clf-timestamp` | Apache/NCSA timestamp | Timestamps | `<TIMESTAMP>` | on | 12/Nov/2026:08:49:03 -0500 |
| `date` | Calendar date | Timestamps | `<DATE>` | on | 2026-11-12, 11/12/2026, 12.11.2026 |
| `time` | Time of day | Timestamps | `<TIME>` | on | 08:49:03.123 |
| `epoch` | Unix epoch timestamp | Timestamps | `<EPOCH>` | on | 1762955343, 1762955343123 (seconds, millis or micros since 2017) |
| `uuid` | UUID | Identifiers | `<UUID>` | on | 3f2b8c1e-9a4d-4e7b-8c2a-1d5e6f7a8b9c |
| `trace-id` | Trace / request id | Identifiers | `$1<ID>` | on | traceId=4bf92f3577b34da6, request_id: abc-123 |
| `hash` | Hex digest (MD5/SHA) | Identifiers | `<HASH>` | on | 32, 40 or 64 hex characters |
| `hex-id` | Long hex identifier | Identifiers | `<HEX>` | on | Container ids, commit prefixes: 4bf92f3577b3 |
| `k8s-pod` | Kubernetes pod suffix | Identifiers | `<POD>` | on | checkout-7d9f8b6c5d-x2x7q |
| `hex-address` | Memory address | Memory | `<ADDR>` | on | 0x7ffd5e8c3a10 |
| `object-identity` | Object identity hash | Memory | `@<ID>` | on | com.example.Order@1b6d3586 |
| `size` | Byte sizes | Memory | `<SIZE>` | off | 512MB, 1.5 GiB, 2048 bytes |
| `thread-name` | Numbered thread name | Processes & threads | `$1<N>` | on | pool-1-thread-7, http-nio-8080-exec-3, Thread-12 |
| `pid` | Process / thread id | Processes & threads | `$1<N>` | on | pid=4242, tid: 17, thread #9 |
| `syslog-pid` | Syslog process id | Processes & threads | `[<PID>]` | on | sshd[1234]: |
| `ipv4` | IPv4 address | Network | `<IP>` | on | 10.0.12.7 |
| `ipv6` | IPv6 address | Network | `<IP>` | on | Full (uncompressed) form only: 2001:0db8:0000:0000:0000:ff00:0042:8329 |
| `ephemeral-port` | Ephemeral port | Network | `<PORT>` | on | Client-side ports 49200-65535 after a colon: 10.0.0.1:53412 |
| `duration` | Duration | Metrics | `<DURATION>` | on | took 132ms, 1.5 s, 3 seconds |
| `number` | Any number | Metrics | `<N>` | off | Aggressive: masks every remaining number |
| `temp-path` | Temporary path | Paths | `<TMPDIR>` | on | /tmp/junit8812/out.txt, C:\Users\me\AppData\Local\Temp\x.tmp |

`size` and `number` are off by default because they easily hide real differences. Enable them
when sizes or counters are known noise.

To list the rules with their patterns: `logdelta rules -v`.

## Presets

A preset adds rules for one well-known log format. It is chosen automatically when at least half
of the sampled lines look like that format (`--preset auto`, the default), or explicitly with
`--preset ID` or the **Format** section of the app's Rules tab. Preset rules run before the
built-in ones and can be switched off one by one (`--disable spring-boot.pid`, or
`[rule spring-boot.pid]` `enabled = false` in a profile).

| Preset | Recognises | Rules |
|---|---|---|
| `spring-boot` | Spring Boot console lines: `timestamp LEVEL pid --- [thread] logger : message` | `pid`, `reactor-thread`, `uptime` |
| `access-log` | nginx / Apache common and combined access logs | `bytes`, `timing`, `trailing-time`, `cache-buster` |
| `kubernetes` | CRI container logs (containerd, CRI-O) and klog lines | `cri-prefix`, `klog-header`, `node-name`, `resource-version` |
| `journald` | `journalctl` output and syslog files | `host`, `host-iso`, `kernel-uptime`, `session` |
| `logcat` | `adb logcat` in the threadtime and brief formats | `ids`, `date`, `brief-pid` |
| `python` | Python `logging` default and `asctime - name - LEVEL - message` formats | `worker`, `server-pid` |

`logdelta rules --presets` prints this list with each rule's description; `logdelta rules
--preset logcat -v` shows the patterns.

## Structured logs

JSON lines and logfmt are not compared as text. Each line is split into fields, ignored fields
(timestamps and trace/span ids by default, see `--ignore-field`) are masked as `<IGNORED>`, the
rest are sorted by name, and the rules above run over the values. So
`{"user":"a","ts":1,"msg":"hi"}` and `{"msg":"hi","ts":2,"user":"a"}` are equal.

## Writing your own rules

A rule has:

| Field | Meaning |
|---|---|
| id | Lower-case letters, digits, `.`, `_`, `-`. Unique. Used in profiles and with `--enable`/`--disable`. |
| name | Shown in the GUI and listings. |
| category | For grouping only: `timestamp`, `identifier`, `network`, `process`, `memory`, `metric`, `path`, `custom`. |
| pattern | A [Java regular expression](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/regex/Pattern.html). |
| replacement | Placeholder text. `$1` or `${name}` keep captured text; `\$` is a literal dollar sign. |
| case-insensitive | Match regardless of case. |
| enabled | Whether the rule is active. |

### Tips

- **Keep context, mask the value.** Capture the key and replace only the value, so that
  `user=alice` and `session=alice` still differ:
  `pattern = (user=)\w+` and `replacement = $1<USER>`.
- **Be specific.** `ORD-\d{6}` is better than `\d+`. A broad rule hides real changes.
- **Use the tester.** In the GUI rule editor, paste a few real lines into the tester, or run
  `logdelta explain` on the command line. Both show exactly what each rule matches.
- **Order matters.** If a built-in rule eats part of what your rule should match, move your rule
  up (GUI) or rely on the default: custom rules run before built-in ones.
- **Ignore instead of mask** when a whole line is noise (health checks, progress bars):
  `--ignore 'heartbeat'` or an `[ignore]` entry in a profile.
