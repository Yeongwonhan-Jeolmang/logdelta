# GitHub Action

The repository is also a GitHub Action. It runs [`logdelta check`](CLI.md#check) on a log your
workflow produced, fails the job only when **new** warnings or errors appear, and posts the
Markdown summary on the pull request, updating the same comment on every push.

```yaml
name: Integration tests
on: pull_request

permissions:
  contents: read
  pull-requests: write     # for the comment

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: ./run-integration-tests.sh > build/app.log 2>&1
      - uses: Yeongwonhan-Jeolmang/logdelta@v1.1.0
        with:
          baseline: tests/expected/app.log
          candidate: build/app.log
          profile: tests/logdelta.ldprofile
```

The comment looks like this:

> ❌ **LogDelta:** new problems found · run
>
> # Log comparison
> **Baseline:** `app.log` (85 lines, 11.9 KB) · **Candidate:** `app.log` (101 lines, 14.1 KB)
>
> | Similarity | Modified | Removed | Added | Moved | New problems |
> |---:|---:|---:|---:|---:|---:|
> | 56.0% | 21 | 11 | 27 | 0 | 6 |
>
> ## New problems (6) …

New problems also appear as annotations on the run, the summary is added to the job summary page,
and a JUnit XML report is written for test-report actions.

## Inputs

| Input | Default | Description |
|---|---|---|
| `candidate` | (required) | Log file, folder or URL produced by this run. |
| `baseline` | | Known-good run(s), one per line: files, folders or URLs. |
| `baseline-dir` | | A folder of accepted runs; every file in it is a known-good run. |
| `profile` | | A [profile](PROFILES.md) with rules and options. |
| `fail-on` | `problems` | `problems` (new warnings or errors), `errors`, or `changes`. |
| `update-on-pass` | `false` | Add the candidate to the baselines when the check passes. |
| `args` | | Further `logdelta check` options, e.g. `--multiline --mask 'tenant=\w+'`. |
| `comment` | `true` | Comment on the pull request. |
| `comment-mode` | `update` | `update` edits LogDelta's previous comment; `new` always adds one. |
| `comment-on-pass` | `false` | Also comment when nothing new went wrong. An existing comment is always updated, so a fixed PR shows ✅. |
| `junit` | `logdelta-junit.xml` | Where to write the JUnit XML report. |
| `fail` | `true` | `false` only reports and never fails the step. |
| `java-version` | `25` | Java used to build and run LogDelta. |
| `github-token` | `${{ github.token }}` | Token for the comment. |

`args` is split like a shell command line; it comes from your workflow file, so do not build it
from untrusted pull-request content.

## Outputs

| Output | Description |
|---|---|
| `result` | `pass` or `fail` |
| `new-problems` | number of new problems |
| `markdown` | path of the Markdown summary |
| `json` | path of the [JSON report](REPORTS.md#json) |
| `junit` | path of the JUnit XML report |

## Recipes

**Several good runs.** List more baselines, so that normal variation (a message that only
sometimes appears) does not fail the job:

```yaml
      - uses: Yeongwonhan-Jeolmang/logdelta@v1.1.0
        with:
          baseline: |
            tests/expected/run-1.log
            tests/expected/run-2.log
            tests/expected/run-3.log
          candidate: build/app.log
```

**A rolling baseline.** Keep the last accepted runs in the cache: the first run bootstraps the
baseline, and every passing run on `main` adds itself.

```yaml
      - uses: actions/cache@v4
        with:
          path: .logdelta/runs
          key: logdelta-runs-${{ github.run_id }}
          restore-keys: logdelta-runs-
      - uses: Yeongwonhan-Jeolmang/logdelta@v1.1.0
        with:
          baseline-dir: .logdelta/runs
          candidate: build/app.log
          update-on-pass: ${{ github.ref == 'refs/heads/main' }}
          args: --keep 10
```

**Folders.** Compare every log a test suite wrote:

```yaml
      - uses: Yeongwonhan-Jeolmang/logdelta@v1.1.0
        with:
          baseline: tests/expected/logs
          candidate: build/logs
          args: --include '*.log'
```

**Test reports.** Feed the JUnit file to a reporter so each new problem shows as a failed test:

```yaml
      - uses: Yeongwonhan-Jeolmang/logdelta@v1.1.0
        id: logdelta
        with: { baseline: tests/expected/app.log, candidate: build/app.log, fail: 'false' }
      - uses: mikepenz/action-junit-report@v4
        if: always()
        with:
          report_paths: ${{ steps.logdelta.outputs.junit }}
```

## Other CI systems

The action is a thin wrapper; on any CI, run the jar directly:

```sh
java -jar logdelta.jar check --baseline expected/app.log --report junit:build/logdelta.xml \
     --report markdown:build/logdelta.md build/app.log
```

`check` exits with 0 (pass), 1 (new problems) or 2 (error), and GitLab, Jenkins and Azure
Pipelines all read the JUnit file.
