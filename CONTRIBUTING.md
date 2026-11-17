# Contributing to LogDelta

Thanks for your interest! Bug reports, new built-in rules, and improvements to the engine or the UI
are all welcome. Everyone taking part is expected to follow the
[code of conduct](CODE_OF_CONDUCT.md).

## Getting started

Requirements: JDK 25+ and Maven 3.9+.

```sh
git clone https://github.com/Yeongwonhan-Jeolmang/logdelta.git
cd logdelta
mvn verify                                        # build and run all tests
java -jar logdelta-app/target/logdelta-1.1.0.jar  # try it
```

The modules are described in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Most changes touch
only `logdelta-core`.

## Reporting bugs

Please include:

- the LogDelta version (`logdelta --version`) and Java version;
- the command or the steps in the GUI;
- a few **anonymised** lines from both logs that show the problem. `logdelta explain "<line>"`
  output is especially useful for rule problems.

Security problems are the exception: report them privately as described in
[SECURITY.md](SECURITY.md).

## Proposing a built-in rule

Good built-in rules:

- match values that differ between runs **without** indicating a real change;
- are unlikely to match anything else (false positives hide real differences);
- are cheap: avoid nested quantifiers and lazy groups that backtrack at every position.

Add the rule to `BuiltinRules` in the right order, add positive and negative examples to
`NormalizerTest`, and add a row to `docs/RULES.md`.

## Code style

- Java 25, four-space indentation, 120-column lines (see `.editorconfig`).
- Use modern Java where it makes code clearer: records and sealed types for data, pattern-matching
  `switch`, `_` for intentionally unused variables, `getFirst()`/`getLast()` on lists.
- Public types and methods have documentation comments that explain *why* as well as *what*.
  Write them as Markdown doc comments (`///`, JEP 467), not `/** ... */`: use backticks for code,
  `[Type]` or `[Type#method(Arg)]` for links, and fenced code blocks for examples. Check them
  with `mvn javadoc:javadoc`, which must finish without warnings.
- Keep `logdelta-core` free of UI code and dependencies.
- Swing code runs on the event dispatch thread; long work goes through `ComparisonController`.
- Tests use JUnit 5 with `@DisplayName` sentences that describe the behaviour.

## License of contributions

LogDelta is licensed under the GNU General Public License v3.0 or later. By submitting a pull
request you agree that your contribution is licensed under the same terms. New Java files start
with the same SPDX header as the existing ones:

```java
// SPDX-License-Identifier: GPL-3.0-or-later
```

## Pull requests

1. Create a branch from `main`.
2. Keep changes focused, and add or update tests and documentation.
3. Run `mvn verify` and make sure it passes.
4. Describe what changed and why in the pull request, with screenshots for UI changes.

## Dependency updates

Dependabot opens monthly pull requests for the Maven plugins, JUnit and the GitHub Actions used
in the workflows (`.github/dependabot.yml`). The `Dependabot auto-merge` workflow turns on
auto-merge for them, so each one is squash-merged as soon as all required checks pass. One with
a failing check stays open for a person to look at.

This needs a one-time setup in the repository settings:

1. **Settings > General > Pull Requests:** enable **Allow auto-merge**.
2. **Settings > Rules > Rulesets > New ruleset > Import a ruleset:** import the three files in
   `.github/rulesets/`:
   - `main-history.json` keeps `main` safe for everyone, admins included: it cannot be deleted or
     force-pushed, and history stays linear.
   - `main-checks.json` requires changes to `main` to come through pull requests (no approval
     needed) whose checks pass:
     - `Build (Java 25, ubuntu-latest)`
     - `Build (Java 25, windows-latest)`
     - `Build (Java 25, macos-latest)`
     - `GitHub Action self-test`

     Repository admins bypass this ruleset, so the maintainer can push directly to `main`.
     Dependabot and other contributors still go through pull requests and checks.
   - `release-tags.json` protects `v*` tags: only repository admins can create, move or delete
     them, because pushing one builds the installers and publishes a release.

   Without required checks, GitHub has nothing to wait for and merges the update straight away.
   If the CI matrix or a job name changes, update `main-checks.json` and the ruleset on GitHub, or pull
   requests will wait forever for a check that never runs.

Merges made by the workflow's token do not start new workflow runs, so CI does not run again on
`main` after an automatic merge; it already ran on the pull request. To keep major version bumps
for manual review, uncomment the condition in `.github/workflows/dependabot-auto-merge.yml`.

## Releasing

1. Update the version in all `pom.xml` files (`mvn versions:set -DnewVersion=X.Y.Z`) and in
   `io.github.logdelta.core.Version`.
2. Move the "Unreleased" notes in `CHANGELOG.md` under the new version.
3. Tag `vX.Y.Z`. CI builds the jar, which is attached to the GitHub release.
