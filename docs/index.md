---
title: "Ant Manipulator"
---

# Ant Manipulator

* Contents
{:toc}

A command-line tool to **align versions and coordinates in [Apache Ant](https://ant.apache.org/)
projects**, modelled on the
[Maven POM Manipulation Extension (PME)](https://github.com/release-engineering/pom-manipulation-ext)
and its Gradle sibling, the
[Gradle Manipulator (GME)](https://github.com/project-ncl/gradle-manipulator).

Like PME and GME, it supports **rebuilding large numbers of pre-existing projects in a cleanroom
environment**: discover what a build publishes, look the coordinates up against an external source of
truth (a Dependency Analyzer / DA service), compute the version each artifact should be *published*
under, and rewrite the build files in place — preserving formatting.

## Installation

Download the self-contained shaded jar from the
[Releases](https://github.com/project-ncl/ant-manipulator/releases) page. No installation step is
required — the jar is self-contained and runs on any JRE 8+.

```bash
java -jar ant-manipulation-cli.jar [options]
```

## Usage

Configuration uses `-D<key>=<value>` system properties with the **same key names as PME and GME**, so
the same argument string is portable across all three tools. Only `-f`, `-d`, and `--preview` are
true CLI flags.

```bash
# Discovery + report only (no changes written; no versioning option, so the version is left as declared)
java -jar ant-manipulation-cli.jar -f /path/to/build.xml

# Align against a DA lookup service and apply rewrites in place
java -jar ant-manipulation-cli.jar -f /path/to/build.xml \
  -DrestURL=https://da.example.com/rest/v-1 \
  -DversionIncrementalSuffix=redhat \
  -DrestHeaders="Authorization:Bearer $TOKEN"

# Preview the rewrites without touching any files
java -jar ant-manipulation-cli.jar -f /path/to/build.xml \
  -DrestURL=https://da.example.com/rest/v-1 \
  -DversionIncrementalSuffix=redhat \
  -DrestHeaders="Authorization:Bearer $TOKEN" \
  --preview
```

### Flags

| Flag | Description |
| --- | --- |
| `-d, --debug` | Enable debug logging |
| `-f, --file` | Ant build file to operate against (default `./build.xml`) |
| `--preview` | Show the edits that would be made without modifying any files. Without this flag, computed versions are written in place (no backups — assumes version control), matching PME/GME. |

## `-D` Properties

REST/DA and version keys match PME/GME verbatim. As in PME, version modification is **opt-in**: the DA
lookup and rewrite only happen when `restURL` is set **and** a versioning option such as
`versionIncrementalSuffix` is configured. There is no default suffix.

| Property | Description | Default |
| --- | --- | --- |
| `alignment.artifactId` | Inject an artifact ID the project never declares | — |
| `alignment.groupId` | Inject a group ID the project never declares | — |
| `alignment.version` | Inject a version the project never declares | — |
| `discoveryReportJSONOutputFile` | Our own richer pre-alignment discovery report output path (no PME/GME equivalent) | `discovery-report.json` beside the build file |
| `groovyScripts` | Comma-separated list of Groovy scripts (`https://` URLs, `file://` URLs, or file paths) to run for edge cases | — |
| `reportJSONOutputFile` | PME-shaped `alignmentReport.json` output path (what PNC/Repour consume) | `alignmentReport.json` beside the build file |
| `reportJsonUnchanged` | Write `reportJSONOutputFile` even when nothing was realigned | `false` |
| `restBrewPullActive` | Enable DA brew-pull for the lookup | `false` |
| `restHeaders` | Request headers as comma-separated `name:value` pairs (auth token goes here) | — |
| `restMode` | DA lookup mode (`PERSISTENT`, `TEMPORARY`, …) | `PERSISTENT` |
| `restSocketTimeout` | Lookup read/socket timeout in seconds | Unirest default (~10 s) |
| `restURL` | DA lookup service base URL; used when version modification is requested (see `versionIncrementalSuffix`) | — |
| `versionIncrementalSuffix` | Rebuild suffix inserted before the serial (`1.2.3.<suffix>-00001`). Opt-in: versioning is skipped when unset, as in PME. An empty value is rejected (exit code 10). | — (none) |
| `versionIncrementalSuffixPadding` | Zero-padding width of the rebuild serial | `5` |
| `versionModification` | When `false`, leave the project version untouched: skip the DA lookup, suffix and rewrite, and publish under the declared version. The same happens when no versioning option (such as `versionIncrementalSuffix`) is configured. Matches PME. | `true` |
| `versionSuffixStrip` | Dev markers stripped like `-SNAPSHOT` before the suffix is applied (comma-separated). Opt-in; no PME equivalent. | _(none)_ |

> **Auth:** like PME/GME, this tool never mints a token. Supply one yourself via
> `-DrestHeaders="Authorization:Bearer <token>"`. No header is needed for an in-cluster DA URL that
> sits behind the auth gateway.

For a complete alphabetical index of all properties, see
[Index of Properties](guide/property-index.html).

## Feature Guide

- [Version Manipulation](guide/version-manip.html) — version suffix, padding, SNAPSHOT handling,
  `versionSuffixStrip`
- [DA REST Integration](guide/rest-da.html) — `restURL`, `restMode`, authentication, timeouts
- [Alignment Report](guide/report.html) — both JSON reports, `reportJSONOutputFile` and
  `discoveryReportJSONOutputFile`
- [Groovy Scripts](guide/groovy.html) — edge-case scripting with `groovyScripts`
- [Index of Properties](guide/property-index.html) — alphabetical `-D` property reference

## Contributing

See [CONTRIBUTING.md](https://github.com/project-ncl/ant-manipulator/blob/main/CONTRIBUTING.md) in
the repository root.

## License

Licensed under the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
