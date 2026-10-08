package org.jboss.pnc.antmanipulator.gav;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Collapses the flat list of {@link ResolvedGav} findings into a per-artifact view keyed by
 * {@code groupId:artifactId}.
 *
 * <p>
 * Findings that carry a full GA are grouped into {@link CorrelatedGav}s (one per coordinate).
 * Version-only findings — those from properties, {@code version.txt} or manifests that have no GA —
 * cannot be attributed to a specific artifact, so they are collected separately as
 * {@link Result#getProjectVersionCandidates() project-version candidates}: these are typically the
 * project's own version and are what a later stage would reconcile tokenised artifact versions
 * against.
 */
public class GavCorrelator {

    /** Result of correlation: per-artifact coordinates plus unattributed version candidates. */
    public static final class Result {
        private final List<CorrelatedGav> artifacts;
        private final List<ResolvedGav> projectVersionCandidates;

        Result(List<CorrelatedGav> artifacts, List<ResolvedGav> projectVersionCandidates) {
            this.artifacts = artifacts;
            this.projectVersionCandidates = projectVersionCandidates;
        }

        /** Coordinates with a full GA, sorted by {@code g:a}. */
        public List<CorrelatedGav> getArtifacts() {
            return artifacts;
        }

        /** Version-only findings (no groupId/artifactId), sorted by version. */
        public List<ResolvedGav> getProjectVersionCandidates() {
            return projectVersionCandidates;
        }

        /** Just the coordinates that have conflicting concrete versions. */
        public List<CorrelatedGav> getConflicts() {
            return artifacts.stream().filter(CorrelatedGav::isConflicting).collect(Collectors.toList());
        }
    }

    public Result correlate(List<ResolvedGav> findings) {
        final Map<String, List<ResolvedGav>> byGa = new LinkedHashMap<>();
        final List<ResolvedGav> versionOnly = new ArrayList<>();

        for (ResolvedGav gav : findings) {
            if (gav.getGroupId() != null && gav.getArtifactId() != null) {
                byGa.computeIfAbsent(gav.getGroupId() + ":" + gav.getArtifactId(), k -> new ArrayList<>())
                        .add(gav);
            } else {
                // No usable GA — treat as a floating (usually project-level) version candidate.
                versionOnly.add(gav);
            }
        }

        final List<CorrelatedGav> artifacts = byGa.values()
                .stream()
                .map(
                        group -> new CorrelatedGav(
                                group.get(0).getGroupId(),
                                group.get(0).getArtifactId(),
                                group))
                .sorted(Comparator.comparing(CorrelatedGav::ga))
                .collect(Collectors.toList());

        versionOnly.sort(
                Comparator.comparing(
                        g -> g.getVersion() == null ? "" : g.getVersion()));

        return new Result(artifacts, versionOnly);
    }
}
