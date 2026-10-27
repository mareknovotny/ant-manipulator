---
title: "DA REST Integration"
---

# DA REST Integration

* Contents
{:toc}

## Overview

ant-manipulator can look up artifact coordinates against a **Dependency Analyzer (DA)** REST service
to obtain the exact rebuild version each artifact should be published under. Alignment is opt-in: it
only happens when `restURL` is set **and** a versioning option such as `versionIncrementalSuffix` is
configured (see [Version Manipulation](version-manip.html#automatic-version-increment)). Otherwise the
tool runs in discovery/report-only mode and writes no changes.

The DA REST layer is provided by PME's own
`pom-manipulation-io` module (`DefaultTranslator`/`Translator`), so the wire protocol and lookup
semantics are identical to those used by PME and GME.

## Configuring the Endpoint

| Property | Description | Default |
| --- | --- | --- |
| `restURL` | Base URL of the DA REST service | — |

Set `restURL` to the base URL of your DA instance, without a trailing slash:

```bash
-DrestURL=https://da.example.com/rest/v-1
```

When this property is set, ant-manipulator POSTs the discovered coordinates to the DA service and
uses the response to compute each artifact's publish version. Without it, the pipeline stops after
the report stage.

## Lookup Mode

| Property | Description | Default |
| --- | --- | --- |
| `restMode` | DA lookup mode | `PERSISTENT` |

The DA service supports several lookup modes that control how it stores and retrieves version
mappings. The most common values are:

| Value | Behaviour |
| --- | --- |
| `PERSISTENT` | Results are stored and reused across builds (default; matches PME/GME default) |
| `TEMPORARY` | Results are stored for a limited time only |
| `SERVICE` | Live lookup against the upstream artifact repository; results are not stored |
| `SERVICE_TEMPORARY` | Live lookup with temporary storage |

Example:

```bash
-DrestMode=TEMPORARY
```

## Authentication {#authentication}

| Property | Description | Default |
| --- | --- | --- |
| `restHeaders` | Comma-separated `name:value` request headers | — |

Like PME and GME, ant-manipulator **never mints a token**. If your DA endpoint requires
authentication, supply the token yourself via `restHeaders`:

```bash
-DrestHeaders="Authorization:Bearer $TOKEN"
```

Multiple headers are comma-separated:

```bash
-DrestHeaders="Authorization:Bearer $TOKEN,X-Correlation-Id:build-42"
```

No header is needed for an in-cluster DA URL that sits behind an auth gateway.

## Brew Pull

| Property | Description | Default |
| --- | --- | --- |
| `restBrewPullActive` | Instruct DA to pull artifact information from Brew | `false` |

When set to `true`, the DA service will attempt to pull artifact metadata from Brew as part of the
lookup. This is rarely needed outside Red Hat's internal infrastructure.

```bash
-DrestBrewPullActive=true
```

## Timeouts

| Property | Description | Default |
| --- | --- | --- |
| `restSocketTimeout` | Read/socket timeout for DA requests, in seconds | Unirest default (~10 s) |

Increase this if your DA instance is slow to respond or if the lookup involves a large number of
coordinates:

```bash
-DrestSocketTimeout=60
```
