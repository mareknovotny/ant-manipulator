package org.jboss.pnc.antmanipulator.align;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The JSON body POSTed to DA's {@code lookup/maven/latest} endpoint, matching DA's
 * {@code MavenLatestRequest} shape exactly (verified by decompiling {@code reports-model}):
 *
 * <pre>
 * {
 *   "mode": "PERSISTENT",
 *   "artifacts": [ { "groupId": "...", "artifactId": "...", "version": "..." } ]
 * }
 * </pre>
 *
 * <p>
 * Unlike {@link LookupRequest} this has <b>no {@code brewPullActive}</b> field — {@code MavenLatestRequest}
 * carries only {@code mode} and {@code artifacts}. {@code mode} is left {@code null} (and omitted, since the
 * mapper skips nulls) unless configured.
 */
public class LatestRequest {

    public String mode;
    public Set<Gav> artifacts;

    public LatestRequest() {
    }

    public LatestRequest(String mode, List<Gav> artifacts) {
        this.mode = mode;
        this.artifacts = artifacts == null ? null : new LinkedHashSet<>(artifacts);
    }
}
