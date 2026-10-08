package org.jboss.pnc.antmanipulator.align;

import java.util.Objects;

/**
 * An immutable {@code groupId:artifactId:version} coordinate — the unit sent to and keyed on when
 * talking to the version-lookup service. This is the analogue of PME's {@code ProjectVersionRef}: a
 * plain, fully-concrete coordinate with value equality so it can be a map key in the lookup response.
 */
public final class Gav {

    private final String groupId;
    private final String artifactId;
    private final String version;

    public Gav(String groupId, String artifactId, String version) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Gav)) {
            return false;
        }
        Gav other = (Gav) o;
        return Objects.equals(groupId, other.groupId)
                && Objects.equals(artifactId, other.artifactId)
                && Objects.equals(version, other.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, artifactId, version);
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
