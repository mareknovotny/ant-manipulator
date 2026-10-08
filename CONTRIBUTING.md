# Contributing

Thanks for your interest in improving the Ant Manipulation CLI. This document describes how to build,
test, and format the project, and the conventions we follow.

## Requirements

- **JDK 11 or later** to build. Like pom-manipulation-ext, the compiler target is Java 8
  (`maven.compiler.release=8`), so source must not use APIs newer than Java 8.
- **Maven 3.8+**.

## Building

```bash
mvn clean package
```

The self-contained jar is written to `cli/target/ant-manipulation-cli.jar`.

The build is a multi-module reactor (mirroring pom-manipulation-ext): `common` (value types, version
math, DA translator), `core` (the Ant-tree manipulators + report), `cli` (the picocli entry point and
shaded jar), and `integration-test` (end-to-end tests). Unit tests live in the module they cover;
end-to-end tests go in `integration-test`.

## Running the tests

```bash
mvn test
```

Please add or update tests for any behavioural change. The version-manipulation classes
(`VersionReconciler`, `VersionIncrementer`, `VersionRewriter`, `GavCorrelator`) are the heart of the
tool and should stay well covered — a bug in version computation silently mis-publishes artifacts.

## Code style

Formatting is enforced by [Spotless](https://github.com/diffplug/spotless), using the Eclipse JDT
formatter and the shared `org.jboss.pnc:ide-config` configuration — the same setup as
pom-manipulation-ext. The Eclipse formatter is JDK-independent, so it works under any build JVM.
Spotless is bound to `apply` at the `compile` phase, so `mvn package` reformats your code as part of
the build.

- **Apply** formatting (also happens during `mvn package`): `mvn spotless:apply`
- **Check** formatting without changing files: `mvn spotless:check`

The formatter also removes unused imports and orders imports consistently, so you don't have to
manage those by hand.

## Code recommendations

- **Logging** — use SLF4J (`org.slf4j.Logger`), not `System.out`/`System.err`. Prefer parameterised
  messages (`logger.info("aligned {} -> {}", a, b)`) over string concatenation.
- **Exceptions** — never swallow an exception silently; either handle it meaningfully or log it with
  enough context to diagnose. A resolution/reconciliation failure for one artifact should not abort
  the whole run.
- **Formatting-preserving rewrites** — the rewrite step deliberately does surgical text edits rather
  than a DOM round-trip, so that user formatting (indentation, quoting, CRLF) is preserved. Keep it
  that way; do not introduce a serializer that reflows build files.
- **Provenance over guessing** — when resolving coordinates or versions, prefer carrying provenance
  (where a value came from, how confident we are) rather than collapsing to a single guess early.

## Modelled on PME/GME

This tool intentionally mirrors the structure and behaviour of
[pom-manipulation-ext](https://github.com/release-engineering/pom-manipulation-ext) and
[gradle-manipulator](https://github.com/project-ncl/gradle-manipulator). When in doubt about how a
stage should behave (version suffix computation, SNAPSHOT handling, report shape, DA interaction),
check what PME does and match it.

## Submitting changes

1. Create a topic branch off `main`.
2. Make your change, with tests.
3. Run `mvn verify` locally (this reformats via Spotless and runs the tests).
4. Open a pull request describing the change and the reasoning behind it.
