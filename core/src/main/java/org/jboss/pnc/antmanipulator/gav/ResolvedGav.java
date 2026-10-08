package org.jboss.pnc.antmanipulator.gav;

import java.io.File;

/**
 * A single groupId:artifactId:version coordinate discovered somewhere in an Ant project, together
 * with where it came from and how much we trust it.
 *
 * <p>
 * Unlike Maven — where {@code pom.xml} holds exactly one authoritative GAV — an Ant project has no
 * canonical place for coordinates, and a single build commonly publishes many artifacts. So instead
 * of returning "the GAV", the resolver returns a list of these, each tagged with its
 * {@link #getSourceFile() source}, a {@link SourceKind} and a {@link Confidence}. Any of
 * {@code groupId}/{@code artifactId}/{@code version} may be {@code null} when a source only supplies
 * part of a coordinate (e.g. a properties file that yields a version but no GA).
 */
public final class ResolvedGav {

    /** Roughly how authoritative a source is for publishing coordinates. */
    public enum Confidence {
        HIGH, MEDIUM, LOW
    }

    /** The kind of source a coordinate was read from. */
    public enum SourceKind {
        POM_TEMPLATE,
        IVY,
        PROPERTY,
        MANIFEST,
        /** Legacy Maven-1 {@code project.xml} ({@code <id>}/{@code <currentVersion>}), e.g. dom4j. */
        MAVEN1_POM,
        /** Supplied by the user via {@code -Dalignment.*} properties rather than discovered in the tree. */
        OVERRIDE
    }

    private final String groupId;
    private final String artifactId;
    private final String version;
    private final File sourceFile;
    private final String detail;
    private final SourceKind kind;
    private final Confidence confidence;

    public ResolvedGav(
            String groupId,
            String artifactId,
            String version,
            File sourceFile,
            String detail,
            SourceKind kind,
            Confidence confidence) {
        this.groupId = emptyToNull(groupId);
        this.artifactId = emptyToNull(artifactId);
        this.version = emptyToNull(version);
        this.sourceFile = sourceFile;
        this.detail = detail;
        this.kind = kind;
        this.confidence = confidence;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
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

    public File getSourceFile() {
        return sourceFile;
    }

    public String getDetail() {
        return detail;
    }

    public SourceKind getKind() {
        return kind;
    }

    public Confidence getConfidence() {
        return confidence;
    }

    /** True when all three coordinate parts are present. */
    public boolean isComplete() {
        return groupId != null && artifactId != null && version != null;
    }

    /**
     * Whether the version still contains an unresolved token. Covers both Ant property syntax
     * ({@code ${...}}) and Ant filter-token syntax ({@code @TOKEN@}, e.g. Tomcat's
     * {@code @MAVEN.DEPLOY.VERSION@}), which is substituted at copy time and cannot be resolved
     * from the file alone.
     */
    public boolean hasUnresolvedVersion() {
        return version != null && (version.contains("${") || version.matches(".*@[A-Za-z0-9._-]+@.*"));
    }

    /** The coordinate as {@code g:a:v}, using {@code ?} for missing parts. */
    public String coordinate() {
        return orQ(groupId) + ":" + orQ(artifactId) + ":" + orQ(version);
    }

    private static String orQ(String s) {
        return s == null ? "?" : s;
    }

    @Override
    public String toString() {
        return String.format(
                "%-45s [%s %s] %s%s",
                coordinate(),
                kind,
                confidence,
                sourceFile,
                detail == null ? "" : " (" + detail + ")");
    }
}
