package org.jboss.pnc.antmanipulator.align;

/**
 * One entry of the lookup service's JSON response, matching DA's result shape: the coordinate that was
 * queried plus the version it should be aligned to ({@code bestMatchVersion}). A {@code null}/blank
 * {@code bestMatchVersion} means the service had no aligned build for that coordinate.
 */
public class LookupResult {

    public String groupId;
    public String artifactId;
    public String version;
    public String bestMatchVersion;

    public Gav toGav() {
        return new Gav(groupId, artifactId, version);
    }
}
