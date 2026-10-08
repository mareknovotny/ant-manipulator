package org.jboss.pnc.antmanipulator.gav;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.jboss.pnc.antmanipulator.gav.GavCorrelator.Result;
import org.jboss.pnc.antmanipulator.gav.ResolvedGav.Confidence;
import org.jboss.pnc.antmanipulator.gav.ResolvedGav.SourceKind;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GavCorrelator} and the {@link CorrelatedGav} view it produces: grouping by
 * {@code g:a}, separating version-only findings as project-version candidates, conflict detection, and
 * the confidence/primary-version selection a downstream aligner relies on.
 */
class GavCorrelatorTest {

    private final GavCorrelator correlator = new GavCorrelator();

    private static ResolvedGav gav(String g, String a, String v, SourceKind kind, Confidence c) {
        return new ResolvedGav(g, a, v, null, "detail", kind, c);
    }

    @Test
    void groupsFindingsByGroupAndArtifact() {
        Result r = correlator.correlate(
                Arrays.asList(
                        gav("org.acme", "widget", "1.0", SourceKind.POM_TEMPLATE, Confidence.HIGH),
                        gav("org.acme", "widget", "1.0", SourceKind.IVY, Confidence.MEDIUM),
                        gav("org.acme", "gadget", "2.0", SourceKind.POM_TEMPLATE, Confidence.HIGH)));

        assertThat(r.getArtifacts()).hasSize(2);
        assertThat(r.getArtifacts()).extracting(CorrelatedGav::ga)
                .containsExactly("org.acme:gadget", "org.acme:widget"); // sorted by g:a
        CorrelatedGav widget = r.getArtifacts().get(1);
        assertThat(widget.getFindings()).hasSize(2);
    }

    @Test
    void findingsWithoutGaBecomeProjectVersionCandidates() {
        Result r = correlator.correlate(
                Arrays.asList(
                        gav(null, null, "3.1", SourceKind.PROPERTY, Confidence.LOW),
                        gav(null, null, "1.9", SourceKind.MANIFEST, Confidence.LOW),
                        gav("org.acme", "widget", "1.0", SourceKind.POM_TEMPLATE, Confidence.HIGH)));

        assertThat(r.getArtifacts()).extracting(CorrelatedGav::ga).containsExactly("org.acme:widget");
        // Version-only candidates sorted by version.
        assertThat(r.getProjectVersionCandidates()).extracting(ResolvedGav::getVersion)
                .containsExactly("1.9", "3.1");
    }

    @Test
    void detectsConcreteVersionConflict() {
        Result r = correlator.correlate(
                Arrays.asList(
                        gav("org.acme", "widget", "1.0", SourceKind.POM_TEMPLATE, Confidence.HIGH),
                        gav("org.acme", "widget", "2.0", SourceKind.IVY, Confidence.MEDIUM)));

        CorrelatedGav widget = r.getArtifacts().get(0);
        assertThat(widget.isConflicting()).isTrue();
        assertThat(widget.resolvedVersions()).containsExactly("1.0", "2.0");
        assertThat(r.getConflicts()).containsExactly(widget);
    }

    @Test
    void tokenisedVersionsDoNotCountAsConflicts() {
        // A concrete version alongside a tokenised one is not a conflict — the token isn't a rival value.
        Result r = correlator.correlate(
                Arrays.asList(
                        gav("org.acme", "widget", "1.0", SourceKind.POM_TEMPLATE, Confidence.HIGH),
                        gav("org.acme", "widget", "@VERSION@", SourceKind.POM_TEMPLATE, Confidence.HIGH)));

        CorrelatedGav widget = r.getArtifacts().get(0);
        assertThat(widget.isConflicting()).isFalse();
        assertThat(widget.resolvedVersions()).containsExactly("1.0");
        assertThat(widget.tokenizedVersions()).containsExactly("@VERSION@");
        assertThat(r.getConflicts()).isEmpty();
    }

    @Test
    void bestConfidenceIsTheHighest() {
        Result r = correlator.correlate(
                Arrays.asList(
                        gav("org.acme", "widget", "1.0", SourceKind.PROPERTY, Confidence.LOW),
                        gav("org.acme", "widget", "1.0", SourceKind.POM_TEMPLATE, Confidence.HIGH)));

        assertThat(r.getArtifacts().get(0).bestConfidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    void primaryVersionPrefersHighestConfidenceConcreteVersion() {
        Result r = correlator.correlate(
                Arrays.asList(
                        gav("org.acme", "widget", "9.9", SourceKind.PROPERTY, Confidence.LOW),
                        gav("org.acme", "widget", "1.0", SourceKind.POM_TEMPLATE, Confidence.HIGH)));

        assertThat(r.getArtifacts().get(0).primaryVersion()).isEqualTo("1.0");
    }

    @Test
    void primaryVersionFallsBackToTokenWhenNoConcreteVersion() {
        Result r = correlator.correlate(
                Collections.singletonList(
                        gav("org.acme", "widget", "@VERSION@", SourceKind.POM_TEMPLATE, Confidence.HIGH)));

        assertThat(r.getArtifacts().get(0).primaryVersion()).isEqualTo("@VERSION@");
    }

    @Test
    void handlesEmptyInput() {
        Result r = correlator.correlate(Collections.emptyList());

        assertThat(r.getArtifacts()).isEmpty();
        assertThat(r.getProjectVersionCandidates()).isEmpty();
        assertThat(r.getConflicts()).isEmpty();
    }

    @Test
    void summarizeFlagsConflict() {
        List<ResolvedGav> findings = Arrays.asList(
                gav("org.acme", "widget", "1.0", SourceKind.POM_TEMPLATE, Confidence.HIGH),
                gav("org.acme", "widget", "2.0", SourceKind.IVY, Confidence.MEDIUM));
        CorrelatedGav widget = correlator.correlate(findings).getArtifacts().get(0);

        assertThat(widget.summarize()).contains("org.acme:widget", "CONFLICT", "1.0", "2.0");
    }
}
