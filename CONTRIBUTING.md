# Contributing to LogDelta

Thanks for your interest! Bug reports, new built-in rules, and improvements to the engine or the UI
are all welcome.

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

## Releasing

1. Update the version in all `pom.xml` files (`mvn versions:set -DnewVersion=X.Y.Z`) and in
   `io.github.logdelta.core.Version`.
2. Move the "Unreleased" notes in `CHANGELOG.md` under the new version.
3. Tag `vX.Y.Z`. CI builds the jar, which is attached to the GitHub release.
