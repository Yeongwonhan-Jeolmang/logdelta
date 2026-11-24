# Contributing to LogDelta

Thanks for your interest! Bug reports, new built-in rules, and improvements to the engine or the UI
are all welcome. Everyone taking part is expected to follow the
[code of conduct](CODE_OF_CONDUCT.md).

## Getting started

Requirements: JDK 25+. Maven is optional: `./mvnw` (or `mvnw.cmd` on Windows) downloads the
version the project uses.

```sh
git clone https://github.com/Yeongwonhan-Jeolmang/logdelta.git
cd logdelta
./mvnw verify                                     # build and run all tests
java -jar logdelta-app/target/logdelta-2.0.0.jar  # try it
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
- Tests that need a real display call `Assumptions.assumeFalse(GraphicsEnvironment.isHeadless())`.
  They are skipped in a normal `./mvnw verify` (`java.awt.headless` defaults to `true`); CI runs
  them on Linux with `xvfb-run -a ./mvnw verify -Dlogdelta.headless=false`, followed by
  `java -jar logdelta-app/target/logdelta-*.jar gui --smoke-test`.

## Translations

Every text the desktop app shows goes through `I18n.tr("English text {0}", args)`; the English
text is the key. Translations live in
`logdelta-gui/src/main/resources/io/github/logdelta/gui/i18n/messages_<lang>.properties`
(UTF-8). When you add or change a text:

- wrap it in `tr(...)` with a string literal (not a variable), so it can be found;
- add the Korean translation to `messages_ko.properties`, keeping the `{0}` placeholders.
  `I18nTest` fails when a `tr` text has no translation or the placeholders differ. If you
  can't translate it, say so in the pull request and a maintainer will help.

To add a language, copy `messages_ko.properties`, translate the values and add the language to
`I18n.LANGUAGES`. Command-line output and reports are English only.

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
3. Run `./mvnw verify` and make sure it passes.
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

1. Update the version in all `pom.xml` files (`./mvnw versions:set -DnewVersion=X.Y.Z`) and in
   `io.github.logdelta.core.Version`.
2. Move the "Unreleased" notes in `CHANGELOG.md` under the new version.
3. Tag `vX.Y.Z` (with the `v` directly before the number; release notes are grouped by the
   labels in `.github/release.yml`). CI builds the jar, which is attached to the GitHub release.
