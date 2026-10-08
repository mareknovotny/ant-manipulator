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
5. **Report** — emit an `alignment-report.json` (PME's `alignmentReport.json` analogue).
6. **Align** — POST coordinates to a DA lookup service and compute each project's publish version.
7. **Rewrite** — write the aligned versions back into the build files (formatting-preserving).

## Module layout

The build is a multi-module Maven reactor, mirroring PME's structure:

| Module | Contents |
|---|---|
| `common` | Shared value types, the PME-style `VersionIncrementer` (version math), and the Dependency Analyzer REST translator. |
| `core` | The Ant-tree manipulators — GAV resolution/correlation, version reconciliation, the formatting-preserving rewriter — plus the alignment report. |
| `cli` | The picocli entry point; builds the self-contained (shaded) executable jar. |
| `integration-test` | End-to-end tests that drive the assembled pipeline against fixture Ant projects. |

## Requirements

- Java 11+ to build (compile target is Java 8, matching PME)
- Maven 3.8+

## Building

```bash
mvn clean package
```

This produces a self-contained (shaded) executable jar at `cli/target/ant-manipulation-cli.jar`.

## Usage

Configuration uses `-D<key>=<value>` system properties with the **same key names as
[pom-manipulation-ext](https://github.com/release-engineering/pom-manipulation-ext) (PME) and
[gradle-manipulator](https://github.com/project-ncl/gradle-manipulator) (GME)**, so the same argument
string is portable across all three tools. Only `-f`, `-d`, and `--preview` are true flags.

```bash
# Discovery + report only (no changes written)
java -jar cli/target/ant-manipulation-cli.jar -f /path/to/build.xml

# Align against a DA lookup service and apply the rewrites in place (like PME/GME, this is the default)
java -jar cli/target/ant-manipulation-cli.jar -f /path/to/build.xml \
  -DrestURL=https://da.example.com/rest/v-1 \
  -DrestMode=PERSISTENT \
  -DversionIncrementalSuffix=redhat \
  -DrestHeaders="Authorization:Bearer $TOKEN"

# Same, but only preview the rewrites without touching any files
java -jar cli/target/ant-manipulation-cli.jar -f /path/to/build.xml \
  -DrestURL=https://da.example.com/rest/v-1 \
  -DrestMode=PERSISTENT \
  -DrestHeaders="Authorization:Bearer $TOKEN" \
  --preview
```

### Flags

| Flag | Description |
|---|---|
| `-f, --file` | Ant build file to operate against (default `./build.xml`) |
| `--preview` | Only show the edits that would be made; do not modify any files. Without it, computed versions are written in place (no backups — assumes version control), matching PME/GME. |
| `-d, --debug` | Enable debug logging |

### `-D` properties

REST/DA and version keys match PME/GME verbatim; `restURL` enables alignment when set.

| Property | Description | Default |
|---|---|---|
| `restURL` | DA lookup service base URL; when set, coordinates are aligned | — |
| `restMode` | DA lookup mode (e.g. `PERSISTENT`, `TEMPORARY`) | — |
| `restHeaders` | Request headers as comma-separated `name:value` pairs (auth token goes here) | — |
| `restBrewPullActive` | Enable DA brew-pull for the lookup | — |
| `restSocketTimeout` | Lookup read/socket timeout in seconds | Unirest default |
| `versionIncrementalSuffix` | Rebuild suffix inserted before the serial (`1.2.3.<suffix>-00001`) | `redhat` |
| `versionIncrementalSuffixPadding` | Zero-padding width of the rebuild serial | `5` |
| `versionSuffixStrip` | Dev markers stripped like `-SNAPSHOT` (comma-separated; e.g. `dev` collapses Tomcat's `10.1.0-dev` to `10.1.0.redhat-00001`). Opt-in. antalignment-specific — no PME equivalent | _(none)_ |
| `reportJSONOutputFile` | JSON report output path | `alignment-report.json` beside the build file |
| `alignment.groupId` / `alignment.artifactId` / `alignment.version` | Inject a coordinate the project never declares (e.g. Xalan) | — |

> **Auth:** like PME/GME, this tool never mints a token. Supply one yourself via
> `-DrestHeaders="Authorization:Bearer <token>"`. No header is needed for an in-cluster DA URL that
> sits behind the auth gateway.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
