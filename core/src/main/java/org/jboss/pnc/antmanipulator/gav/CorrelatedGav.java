package org.jboss.pnc.antmanipulator.gav;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.jboss.pnc.antmanipulator.gav.ResolvedGav.Confidence;
import org.jboss.pnc.antmanipulator.gav.ResolvedGav.SourceKind;

/**
 * All findings for a single {@code groupId:artifactId}, gathered from across the project's sources.
 *
 * <p>
 * This is the per-artifact view an alignment engine actually needs: for one coordinate it shows
 * every version seen and where, so conflicts (a pom template stamping {@code @MAVEN.DEPLOY.VERSION@}
 * while a sibling module pom says {@code 8.0.15-SNAPSHOT}) become explicit rather than being buried
 * in a flat list.
 */
public final class CorrelatedGav {

    private final String groupId;
    private final String artifactId;
    private final List<ResolvedGav> findings;

    CorrelatedGav(String groupId, String artifactId, List<ResolvedGav> findings) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.findings = findings;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String ga() {
        return groupId + ":" + artifactId;
    }

    public List<ResolvedGav> getFindings() {
        return findings;
    }

    /** Distinct concrete versions (excluding tokenised ones such as {@code @X@}/{@code ${x}}). */
    public Set<String> resolvedVersions() {
        return findings.stream()
                .filter(f -> f.getVersion() != null && !f.hasUnresolvedVersion())
                .map(ResolvedGav::getVersion)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Distinct tokenised versions that could not be resolved from the file alone. */
    public Set<String> tokenizedVersions() {
        return findings.stream()
                .filter(ResolvedGav::hasUnresolvedVersion)
                .map(ResolvedGav::getVersion)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public Set<SourceKind> sources() {
        return findings.stream()
                .map(ResolvedGav::getKind)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** True when two or more <em>different concrete</em> versions were found for this coordinate. */
    public boolean isConflicting() {
        return resolvedVersions().size() > 1;
    }

    /** The best confidence across findings ({@code HIGH} beats {@code MEDIUM} beats {@code LOW}). */
    public Confidence bestConfidence() {
        return findings.stream()
                .map(ResolvedGav::getConfidence)
                .min(Comparator.naturalOrder()) // HIGH is ordinal 0
                .orElse(Confidence.LOW);
    }

    /**
     * The version to treat as authoritative: the highest-confidence concrete version, or — if every
     * finding is tokenised — the highest-confidence token (so the caller can see what needs stamping).
     * {@code null} if no version was found at all.
     */
    public String primaryVersion() {
        return findings.stream()
                .filter(f -> f.getVersion() != null && !f.hasUnresolvedVersion())
                .min(Comparator.comparing(ResolvedGav::getConfidence))
                .map(ResolvedGav::getVersion)
                .orElseGet(
                        () -> findings.stream()
                                .filter(f -> f.getVersion() != null)
                                .min(Comparator.comparing(ResolvedGav::getConfidence))
                                .map(ResolvedGav::getVersion)
                                .orElse(null));
    }

    /** A one-line summary suitable for logging. */
    public String summarize() {
        Set<String> resolved = resolvedVersions();
        Set<String> tokens = tokenizedVersions();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-55s ", ga()));
        if (isConflicting()) {
            sb.append("CONFLICT ").append(resolved);
        } else if (!resolved.isEmpty()) {
            sb.append(resolved.iterator().next());
            // A concrete version alongside a tokenised one (e.g. a module pom vs a publish template)
            // is worth showing: the two may disagree once the token is stamped.
            if (!tokens.isEmpty()) {
                sb.append(" (also tokenised: ").append(tokens).append(")");
            }
        } else if (!tokens.isEmpty()) {
            sb.append(tokens.iterator().next()).append(" (tokenised)");
        } else {
            sb.append("<no version>");
        }
        sb.append("  [")
                .append(bestConfidence())
                .append(", ")
                .append(findings.size())
                .append(" finding(s): ")
                .append(sources())
                .append("]");
        return sb.toString();
    }
}
