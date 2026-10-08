package org.jboss.pnc.antmanipulator.align;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The JSON body POSTed to DA's {@code lookup/maven} endpoint, matching DA's {@code MavenLookupRequest}
 * shape exactly (verified against bacon's {@code DALookupCli}):
 *
 * <pre>
 * {
 *   "mode": "PERSISTENT",
 *   "brewPullActive": false,
 *   "artifacts": [ { "groupId": "...", "artifactId": "...", "version": "..." } ]
 * }
 * </pre>
 *
 * <p>
 * The coordinate set field is {@code artifacts} (not {@code gavs}) — sending it under any other name
 * leaves the server-side set null and produces an HTTP 500. {@code mode}/{@code brewPullActive} are
 * left {@code null} (and thus omitted, since the mapper skips nulls) unless configured.
 */
public class LookupRequest {

    public String mode;
    public Boolean brewPullActive;
    public Set<Gav> artifacts;

    public LookupRequest() {
    }

    public LookupRequest(String mode, Boolean brewPullActive, List<Gav> artifacts) {
        this.mode = mode;
        this.brewPullActive = brewPullActive;
        this.artifacts = artifacts == null ? null : new LinkedHashSet<>(artifacts);
    }
}
