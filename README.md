# ant-manipulator

A command-line tool to **align versions and coordinates in [Apache Ant](https://ant.apache.org/)
projects**, modelled on the [Maven POM Manipulation Extension (PME)](https://github.com/release-engineering/pom-manipulation-ext)
and its Gradle sibling, the [Gradle Manipulator (GME)](https://github.com/project-ncl/gradle-manipulator).

Like PME and GME, it exists to support **rebuilding large numbers of pre-existing projects in a
cleanroom environment**: discover what a build publishes, look the coordinates up against an external
source of truth (a Dependency Analyzer / DA service), compute the version each artifact should be
*published* under, and rewrite the build files in place — preserving formatting.

## Why a separate tool for Ant?

Ant has no canonical project model. A single `build.xml` can produce many artifacts, versions live
behind chains of `${property}` / `@token@` substitutions, and the same version literal appears both
in version-defining sites and in prose. This tool reuses Ant's own `ProjectHelper`/`Project` to parse
build files and expand properties, then applies a PME-style pipeline on top.

## Pipeline

The stages mirror PME's `ManipulationManager` flow:

1. **Hierarchy crawl** — BFS over `<import>` / `<include>` / `<ant>` / `<subant>` links to find every
   build file that may need manipulating.
2. **GAV resolution** — multi-GAV-with-provenance resolution from pom templates, `ivy.xml`,
   Maven-1 `project.xml`, properties, and `-D` overrides.
3. **Correlation** — group findings by `groupId:artifactId`, flag version conflicts.
4. **Reconciliation** — resolve tokenised versions (`${...}`, `@TOKEN@`) to concrete values by
   following Ant's substitution chains.
5. **Discovery report** — emit a `discovery-report.json` of every coordinate candidate found, with
   provenance, conflicts, and reconciliation (no PME/GME equivalent).
6. **Align** — POST coordinates to a DA lookup service and compute each project's publish version.
7. **Rewrite** — write the aligned versions back into the build files (formatting-preserving).
8. **Alignment report** — emit an `alignmentReport.json` in PME's own JSON shape, so PNC/Repour
   consume Ant Manipulator's GAV changes exactly like PME's and GME's.

## Module layout

The build is a multi-module Maven reactor, mirroring PME's structure:

| Module | Contents |
| --- | --- |
| `common` | Shared value types, the PME-style `VersionIncrementer` (version math), and the Dependency Analyzer REST translator. Request/response model types come from [`da:reports-model`](https://github.com/project-ncl/dependency-analysis) so the wire format stays consistent with PME and GME. |
| `core` | The Ant-tree manipulators — GAV resolution/correlation, version reconciliation, the formatting-preserving rewriter — plus the alignment report. |
| `cli` | The picocli entry point; builds the self-contained (shaded) executable jar. |
| `integration-test` | End-to-end tests that drive the assembled pipeline against fixture Ant projects. |

## Requirements

- **JDK 17+** to build (Spotless requires Java 17). Code (main and test) targets Java 11.
- Maven 3.8+

## Building

```bash
mvn clean package
```

This produces a self-contained (shaded) executable jar at `cli/target/ant-manipulation-cli.jar`.

## Documentation

Full usage, CLI flags, `-D` property reference, and feature guides are on the
[documentation site](https://project-ncl.github.io/ant-manipulator/).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
