# AGENTS.md

Guidance for AI coding agents working in this repository.

## What this is

A CLI tool (`ant-manipulator`) that aligns versions and coordinates in Apache Ant projects, modelled
directly on the Maven POM Manipulation Extension (PME) and the Gradle Manipulator (GME). It discovers
what an Ant build publishes, looks the coordinates up against a Dependency Analyzer (DA) REST service,
computes the version each artifact should be published under, and rewrites the build files in place —
preserving formatting.

**When in doubt about how a stage should behave (version suffix computation, SNAPSHOT handling, report
shape, DA interaction), check what PME does and match it.** This project intentionally mirrors PME's
structure and behaviour rather than inventing its own conventions.

Read `README.md` for the full pipeline description and CLI usage/flags.

## Module layout

Multi-module Maven reactor, mirroring PME's structure. Package root: `org.jboss.pnc.antmanipulator`.

| Module | Artifact | Contents |
| --- | --- | --- |
| `common` | `ant-manipulation-common` | `align.VersionIncrementer` (version math). The DA REST lookup now comes from `org.jboss.pnc.maven-manipulator:pom-manipulation-io` (PME's own `DefaultTranslator`/`Translator`/`RestException`) rather than a local implementation — see `common/src/test/.../align/DefaultTranslatorTest.java` for how it's exercised against a WireMock stub. |
| `core` | `ant-manipulation-core` | The Ant-tree manipulators: `gav.AntPropertyResolver`, `GavResolver`, `GavCorrelator`, `ResolvedGav`, `CorrelatedGav`, `VersionReconciler`, `VersionRewriter`; plus `report.AlignmentReport`/`Json`. This is the heart of the tool. |
| `cli` | `ant-manipulation-cli` | Picocli entry point (`cli.Cli`, main class `org.jboss.pnc.antmanipulator.cli.Cli`), `BuildFileScanner`/`BuildFilePeek`/`RawBuildFilePeek`. Builds the shaded executable jar `cli/target/ant-manipulation-cli.jar`. |
| `integration-test` | `ant-manipulation-integration-test` | Intended for end-to-end tests driving the assembled pipeline against fixture Ant projects (via failsafe, bound to the `integration-test`/`verify` phases). Currently has no test sources yet — it's a placeholder module. |

Unit tests live beside the code they cover, in the module they belong to (`src/test/java`, same
package). End-to-end tests belong in `integration-test`.

## Build & test

- **JDK 17+** is required to build (Spotless 3.4.0 requires Java 17+ to run), even though the
  **compile target is Java 8** (`maven.compiler.release=8`, matching PME) — don't use language
  features newer than Java 8 in `main` sources.
  - Note: `CONTRIBUTING.md` still says "JDK 11 or later" — that's stale; trust `README.md`
    (`JDK 17+`) and the `bdc349e` commit that bumped Spotless.
- Maven 3.8+.

```bash
mvn clean package      # build everything, produce cli/target/ant-manipulation-cli.jar
mvn test                # unit tests only
mvn verify               # unit + integration tests + reformats via Spotless
mvn spotless:check       # check formatting without modifying files
mvn spotless:apply       # apply formatting (also happens automatically at the compile phase)
```

Spotless (Eclipse JDT formatter + shared `org.jboss.pnc:ide-config`) is bound to the `compile` phase,
so a normal `mvn package`/`mvn verify` run **reformats code as part of the build** and also strips/
reorders imports. Don't hand-fight import order or formatting — run `mvn spotless:apply` (or just
build) instead of manually tweaking whitespace.

If `mvn clean verify` reports a surefire fork error like `Unable to create test class '...'` with no
further detail, it's usually a flaky forked-JVM hiccup, not a real failure — re-run the full build
(and/or the single module with `-pl <module> -am test -Dtest=<ClassName>`) before concluding something
is broken.

Tests use JUnit 5 + AssertJ throughout. `common`'s DA-lookup tests stub the REST endpoint with
WireMock (`wiremock-jre8-standalone`) rather than hitting a live DA service.

## Code conventions

See `CONTRIBUTING.md` for the full list; the ones most load-bearing for agents:

- **Logging** — SLF4J only, never `System.out`/`System.err`. Prefer parameterised messages
  (`logger.info("aligned {} -> {}", a, b)`) over concatenation.
- **Exceptions** — never swallow silently; log with enough context to diagnose. A single artifact's
  resolution/reconciliation failure should not abort the whole run.
- **Formatting-preserving rewrites** — `VersionRewriter` does surgical text edits, not a DOM
  round-trip, so user formatting (indentation, quoting, line endings) survives. Don't introduce a
  serializer that reflows build files.
- **Provenance over guessing** — when resolving coordinates/versions, carry provenance (where a value
  came from, confidence) rather than collapsing to a single guess early; see `ResolvedGav`/
  `CorrelatedGav`.
- **`-D` property names must match PME/GME verbatim** wherever the CLI exposes an equivalent concept
  (e.g. `restURL`, `restMode`, `restHeaders`, `versionIncrementalSuffix`) — this is what lets the same
  argument string be portable across PME, GME, and this tool. Don't rename or diverge from PME's key
  names without a strong reason.

## Submitting changes

1. Topic branch off `main`.
2. Make the change with tests (especially for `VersionReconciler`, `VersionIncrementer`,
   `VersionRewriter`, `GavCorrelator` — bugs there silently mis-publish artifacts).
3. Run `mvn verify` locally before considering the change done.
4. PR description should explain the change and the reasoning (this repo's commits reference Jira
   keys like `NCL-9990` in the subject, e.g. `refactor(NCL-9990): ...`).
