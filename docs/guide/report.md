---
title: "Alignment Report"
---

# Alignment Report

* Contents
{:toc}

## Overview

ant-manipulator emits two JSON reports, each with a different job:

| Report | Property | Default path | When written |
| --- | --- | --- | --- |
| [PME alignment report](#pme-alignment-report) | `reportJSONOutputFile` | `alignmentReport.json` beside the build file | After alignment, only if something was realigned (or `reportJsonUnchanged=true`) |
| [Discovery report](#discovery-report) | `discoveryReportJSONOutputFile` | `discovery-report.json` beside the build file | Always, including when `restURL` is not set (discovery/report-only mode) |

The **PME alignment report** is what PNC/Repour consume: it reuses PME's own JSON POJOs
(`org.jboss.pnc.mavenmanipulator.common.json`, from `pom-manipulation-common-lite`) so its shape is
byte-for-byte compatible with PME's and GME's `alignmentReport.json` — a diff of the
groupId:artifactId:version changes that were actually applied.

The **discovery report** is this tool's own, richer diagnostic output: every coordinate candidate
found, how they were grouped into artifacts, which versions are concrete vs. tokenised, and the
result of reconciling tokens. It has no PME/GME equivalent and is independently useful even when
`restURL` isn't set.

## PME Alignment Report

### Output Path

| Property | Description | Default |
| --- | --- | --- |
| `reportJSONOutputFile` | Path of the PME-shaped JSON report file | `alignmentReport.json` beside the build file |
| `reportJsonUnchanged` | Write the report even when no artifact was realigned | `false` |

```bash
-DreportJSONOutputFile=/tmp/alignmentReport.json
```

By default the report is only written when at least one artifact's version was actually realigned
(mirroring PME: there's nothing useful to report otherwise). Set `-DreportJsonUnchanged=true` to force
it to be written regardless.

### JSON Structure

The report has two top-level keys, exactly as PME defines them:

| Key | Type | Description |
| --- | --- | --- |
| `executionRoot` | object | GAV of the artifact treated as "the" project root — see [Choosing executionRoot](#choosing-executionroot) |
| `modules` | array | One entry per artifact whose version was realigned |

Each `executionRoot` / `modules[].gav` object is:

| Field | Description |
| --- | --- |
| `groupId` | Maven group ID of the realigned (new) coordinate |
| `artifactId` | Maven artifact ID of the realigned (new) coordinate |
| `version` | The computed publish version |
| `originalGAV` | The original `groupId:artifactId:version` before realignment |

Ant Manipulator only realigns a project's own publishing coordinates (not its consumed
dependencies), so unlike PME's `ModulesItem`, the `properties`/`dependencies`/`managedDependencies`/
`plugins`/`profiles` fields are always empty and omitted from the output.

### Choosing `executionRoot`

Unlike a Maven reactor, an Ant project has no single canonical root GAV — one `build.xml` can publish
many artifacts. `executionRoot` is chosen as whichever realigned artifact has a finding sourced
directly from the root build file (the one passed to `-f`); if none is declared there, the first
realigned artifact (by `groupId:artifactId` order) is used instead.

When [`versionModification=false`](version-manip.html#disabling-version-modification) nothing is
realigned, so `modules` is empty — but `executionRoot` is still populated, chosen the same way from
the discovered artifacts and reported at its **unchanged** declared version (`originalGAV` equals
`version`). This lets PNC/Repour record and tag the build even when no version was modified.

### Annotated example

```json
{
  "executionRoot": {
    "groupId": "com.example",
    "artifactId": "my-lib",
    "version": "1.2.3.redhat-00001",
    "originalGAV": "com.example:my-lib:1.2.3"
  },
  "modules": [
    {
      "gav": {
        "groupId": "com.example",
        "artifactId": "my-lib",
        "version": "1.2.3.redhat-00001",
        "originalGAV": "com.example:my-lib:1.2.3"
      }
    }
  ]
}
```

## Discovery Report

### Output Path

| Property | Description | Default |
| --- | --- | --- |
| `discoveryReportJSONOutputFile` | Path of the discovery JSON report file | `discovery-report.json` beside the build file |

```bash
-DdiscoveryReportJSONOutputFile=/tmp/my-report.json
```

### JSON Structure

The report has four top-level keys:

| Key | Type | Description |
| --- | --- | --- |
| `rootBuildFile` | string | Absolute path of the build file passed to `-f` |
| `generatedAt` | string | ISO-8601 UTC timestamp |
| `summary` | object | Aggregate counts (see below) |
| `artifacts` | array | One entry per distinct `groupId:artifactId` pair |
| `projectVersionCandidates` | array | Coordinates whose GA could not be definitively attributed to a single artifact |

### `summary` object

| Field | Description |
| --- | --- |
| `coordinateCandidates` | Total number of raw coordinate findings before deduplication |
| `artifacts` | Number of distinct `groupId:artifactId` pairs |
| `conflicts` | Number of GAs where two or more incompatible versions were found |
| `projectVersionCandidates` | Number of version candidates that could not be grouped into a confirmed artifact |
| `tokenisedVersions` | Number of distinct version tokens (`${…}` / `@TOKEN@`) seen |
| `tokensResolved` | Tokens that resolved to exactly one concrete value |
| `tokensAmbiguous` | Tokens that resolved to more than one concrete value |
| `tokensUnresolved` | Tokens that could not be resolved to any concrete value |

### `artifacts` array entry

Each entry represents one `groupId:artifactId` and collects all the findings for that pair.

| Field | Description |
| --- | --- |
| `groupId` | Maven group ID |
| `artifactId` | Maven artifact ID |
| `primaryVersion` | The single best version for this artifact (may be a token) |
| `conflict` | `true` if two or more incompatible versions were found |
| `confidence` | Confidence level of the best finding (`HIGH`, `MEDIUM`, `LOW`) |
| `resolvedVersions` | Concrete version strings found directly |
| `tokenisedVersions` | Version token strings found |
| `reconciled` | Reconciliation results for each tokenised version (see below) |
| `findings` | List of individual coordinate findings that contributed to this artifact |

### `reconciled` / reconciliation object

When a version is tokenised, the tool follows Ant's property-substitution chains to find concrete
values. The result is captured in a reconciliation object:

| Field | Description |
| --- | --- |
| `token` | The original token string (`${project.version}`, `@VERSION@`, etc.) |
| `resolved` | List of concrete values the token resolves to |
| `unresolved` | List of intermediate tokens that still could not be resolved |
| `ambiguous` | `true` if `resolved` contains more than one value |
| `fullyResolved` | `true` if `unresolved` is empty |

### Annotated example

```json
{
  "rootBuildFile": "/builds/my-project/build.xml",
  "generatedAt": "2025-03-01T10:00:00.000000000Z",
  "summary": {
    "coordinateCandidates": 3,
    "artifacts": 2,
    "conflicts": 0,
    "projectVersionCandidates": 1,
    "tokenisedVersions": 1,
    "tokensResolved": 1,
    "tokensAmbiguous": 0,
    "tokensUnresolved": 0
  },
  "artifacts": [
    {
      "groupId": "com.example",
      "artifactId": "my-lib",
      "primaryVersion": "@VERSION@",
      "conflict": false,
      "confidence": "HIGH",
      "resolvedVersions": [],
      "tokenisedVersions": ["@VERSION@"],
      "reconciled": [
        {
          "token": "@VERSION@",
          "resolved": ["1.2.3"],
          "unresolved": [],
          "ambiguous": false,
          "fullyResolved": true
        }
      ],
      "findings": [
        {
          "groupId": "com.example",
          "artifactId": "my-lib",
          "version": "@VERSION@",
          "sourceKind": "POM_TEMPLATE",
          "confidence": "HIGH",
          "sourceFile": "/builds/my-project/src/maven/my-lib.pom",
          "detail": "pom template",
          "reconciled": {
            "token": "@VERSION@",
            "resolved": ["1.2.3"],
            "unresolved": [],
            "ambiguous": false,
            "fullyResolved": true
          }
        }
      ]
    }
  ],
  "projectVersionCandidates": []
}
```

## Programmatic Access

The PME-shaped report is produced by `PmeAlignmentReport` in the `core` module, and serialised with
PME's own `JSONUtils`/`PME` POJOs from `pom-manipulation-common-lite`. The discovery report is
produced by `AlignmentReport`, also in `org.jboss.pnc.antmanipulator.report`, and serialised with a
plain Jackson `ObjectMapper`. If you need to consume either report programmatically rather than
parsing the JSON directly, use the respective `build(...)` factory method after running the pipeline
stages manually.
