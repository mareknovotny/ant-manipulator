---
title: "Index of Properties"
---

# Index of Properties

Alphabetical index of all `-D` properties accepted by ant-manipulator, with links to the section
where each property is described in detail.

| Property | Description | Reference |
| --- | --- | --- |
| `alignment.artifactId` | Inject or override the artifact ID | [index.html#-d-properties](../index.html#-d-properties) |
| `alignment.groupId` | Inject or override the group ID | [index.html#-d-properties](../index.html#-d-properties) |
| `alignment.version` | Inject or override the version | [index.html#-d-properties](../index.html#-d-properties) |
| `discoveryReportJSONOutputFile` | Our own pre-alignment discovery report output path | [report.html#discovery-report](report.html#discovery-report) |
| `groovyScripts` | Comma-separated list of Groovy scripts to run for edge cases | [groovy.html#configuring-scripts](groovy.html#configuring-scripts) |
| `reportJSONOutputFile` | PME-shaped `alignmentReport.json` output path | [report.html#pme-alignment-report](report.html#pme-alignment-report) |
| `reportJsonUnchanged` | Write `reportJSONOutputFile` even when nothing was realigned | [report.html#pme-alignment-report](report.html#pme-alignment-report) |
| `restBrewPullActive` | Enable DA brew-pull for the lookup | [rest-da.html#brew-pull](rest-da.html#brew-pull) |
| `restHeaders` | Comma-separated `name:value` request headers | [rest-da.html#authentication](rest-da.html#authentication) |
| `restMode` | DA lookup mode (`PERSISTENT`, `TEMPORARY`, …) | [rest-da.html#lookup-mode](rest-da.html#lookup-mode) |
| `restSocketTimeout` | Lookup read/socket timeout in seconds | [rest-da.html#timeouts](rest-da.html#timeouts) |
| `restURL` | DA lookup service base URL | [rest-da.html#configuring-the-endpoint](rest-da.html#configuring-the-endpoint) |
| `versionIncrementalSuffix` | Rebuild suffix inserted before the serial | [version-manip.html#automatic-version-increment](version-manip.html#automatic-version-increment) |
| `versionModification` | Disable all version modification (skip DA lookup, suffix and rewrite) | [version-manip.html#disabling-version-modification](version-manip.html#disabling-version-modification) |
| `versionIncrementalSuffixPadding` | Zero-padding width of the rebuild serial | [version-manip.html#version-increment-padding](version-manip.html#version-increment-padding) |
| `versionSuffixStrip` | Dev markers stripped before the rebuild suffix is applied | [version-manip.html#suffix-stripping](version-manip.html#suffix-stripping) |
