package org.jboss.pnc.antmanipulator.gav;

import static org.assertj.core.api.Assertions.assertThat;

import org.jboss.pnc.antmanipulator.gav.ResolvedGav.Confidence;
import org.jboss.pnc.antmanipulator.gav.ResolvedGav.SourceKind;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ResolvedGav}: the empty-to-null normalisation, completeness, the token
 * detection used to decide whether a version still needs reconciling, and the {@code g:a:v} rendering.
 */
class ResolvedGavTest {

    private static ResolvedGav gav(String g, String a, String v) {
        return new ResolvedGav(g, a, v, null, "detail", SourceKind.POM_TEMPLATE, Confidence.HIGH);
    }

    @Test
    void blankPartsBecomeNull() {
        ResolvedGav r = gav("", "   ", null);

        assertThat(r.getGroupId()).isNull();
        assertThat(r.getArtifactId()).isNull();
        assertThat(r.getVersion()).isNull();
    }

    @Test
    void trimsWhitespaceOnParts() {
        ResolvedGav r = gav("  org.acme  ", " widget ", " 1.0 ");

        assertThat(r.getGroupId()).isEqualTo("org.acme");
        assertThat(r.getArtifactId()).isEqualTo("widget");
        assertThat(r.getVersion()).isEqualTo("1.0");
    }

    @Test
    void isCompleteOnlyWhenAllThreePresent() {
        assertThat(gav("org.acme", "widget", "1.0").isComplete()).isTrue();
        assertThat(gav("org.acme", "widget", null).isComplete()).isFalse();
        assertThat(gav(null, "widget", "1.0").isComplete()).isFalse();
    }

    @Test
    void detectsAntPropertyToken() {
        assertThat(gav("g", "a", "${project.version}").hasUnresolvedVersion()).isTrue();
    }

    @Test
    void detectsFilterToken() {
        assertThat(gav("g", "a", "@MAVEN.DEPLOY.VERSION@").hasUnresolvedVersion()).isTrue();
    }

    @Test
    void concreteVersionIsNotUnresolved() {
        assertThat(gav("g", "a", "1.2.3").hasUnresolvedVersion()).isFalse();
    }

    @Test
    void nullVersionIsNotUnresolved() {
        assertThat(gav("g", "a", null).hasUnresolvedVersion()).isFalse();
    }

    @Test
    void coordinateUsesQuestionMarkForMissingParts() {
        assertThat(gav(null, "widget", null).coordinate()).isEqualTo("?:widget:?");
        assertThat(gav("org.acme", "widget", "1.0").coordinate()).isEqualTo("org.acme:widget:1.0");
    }
}
