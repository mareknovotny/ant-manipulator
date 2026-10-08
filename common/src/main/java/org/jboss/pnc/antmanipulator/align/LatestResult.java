package org.jboss.pnc.antmanipulator.align;

/**
 * One entry of DA's {@code lookup/maven/latest} response, matching {@code MavenLatestResult}: the
 * coordinate that was queried plus DA's {@code latestVersion} — the highest <em>existing</em> build of
 * that coordinate for the requested mode ("used for version increment"). A {@code null}/blank
 * {@code latestVersion} means the coordinate has never been built, so this is a first build.
 *
 * <p>
 * Note the field is {@code latestVersion}, <b>not</b> {@code bestMatchVersion} (that is the
 * dependency-alignment endpoint {@code lookup/maven}); the two endpoints return different shapes.
 */
public class LatestResult {

    public String groupId;
    public String artifactId;
    public String version;
    public String latestVersion;

    public Gav toGav() {
        return new Gav(groupId, artifactId, version);
    }
}
