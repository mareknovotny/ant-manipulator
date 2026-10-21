package org.jboss.pnc.antmanipulator.align;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.jboss.pnc.antmanipulator.align.VersionIncrementer.Result;
import org.jboss.pnc.mavenmanipulator.common.exception.ManipulationException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VersionIncrementer}. These lock in the two branches (first build, which delegates
 * to PME's real {@code Version.appendQualifierSuffix()}/{@code getOsgiVersion()}, vs increment of an
 * existing DA build, which stays our own stem-preserving logic), padding preservation/widening, and —
 * critically — the SNAPSHOT handling: a {@code -SNAPSHOT} in the input must NEVER be carried through into
 * the middle of the rebuild version.
 */
class VersionIncrementerTest {

    private final VersionIncrementer incrementer = VersionIncrementer.defaults();

    @Nested
    class FirstBuild {

        @Test
        void numericTailUsesDotSeparator() throws ManipulationException {
            Result r = incrementer.nextVersion("4.13.2", null);

            assertThat(r.getNewVersion()).isEqualTo("4.13.2.redhat-00001");
            assertThat(r.isFirstBuild()).isTrue();
            assertThat(r.getBasedOn()).isNull();
        }

        @Test
        void qualifierTailIsZeroFilledWithDotBoundary() throws ManipulationException {
            // The 2-component numeric head ("3.8") is OSGi-zero-filled to "3.8.0"; PME's
            // Version.getOsgiVersion() always rejoins the numeric head to the qualifier with "." (not
            // whatever delimiter the original version used), so "3.8-beta1" -> "3.8.0.beta1-redhat-00001".
            assertThat(incrementer.nextVersion("3.8-beta1", null).getNewVersion())
                    .isEqualTo("3.8.0.beta1-redhat-00001");
        }

        @Test
        void twoComponentNumericStemIsOsgiZeroFilledToThreeComponents() throws ManipulationException {
            // NCL-? / GH #11: JUnit4's "4.9" must publish as 4.9.0.redhat-00001, matching PME's
            // VersionCalculator (which OSGi-normalizes the stem once a qualifier suffix is appended),
            // not the invalid-looking 4.9.redhat-00001.
            assertThat(incrementer.nextVersion("4.9", null).getNewVersion()).isEqualTo("4.9.0.redhat-00001");
            assertThat(incrementer.nextVersion("1.0", null).getNewVersion()).isEqualTo("1.0.0.redhat-00001");
        }

        @Test
        void threeComponentNumericStemIsLeftUnchanged() throws ManipulationException {
            assertThat(incrementer.nextVersion("4.13.2", null).getNewVersion())
                    .isEqualTo("4.13.2.redhat-00001");
        }

        @Test
        void singleComponentNumericStemIsOsgiZeroFilledToThreeComponents() throws ManipulationException {
            assertThat(incrementer.nextVersion("4", null).getNewVersion()).isEqualTo("4.0.0.redhat-00001");
        }

        @Test
        void alphaFinalQualifierUsesDashSeparator() throws ManipulationException {
            assertThat(incrementer.nextVersion("1.0.0.Final", null).getNewVersion())
                    .isEqualTo("1.0.0.Final-redhat-00001");
        }

        @Test
        void blankDaLatestIsTreatedAsFirstBuildFromBase() throws ManipulationException {
            Result r = incrementer.nextVersion("4.13.2", "   ");

            assertThat(r.getNewVersion()).isEqualTo("4.13.2.redhat-00001");
            assertThat(r.getBasedOn()).isNull();
        }
    }

    @Nested
    class IncrementExisting {

        @Test
        void incrementsSerialPreservingStemSeparatorAndWidth() throws ManipulationException {
            Result r = incrementer.nextVersion("30.1-jre", "30.1.0.jre-redhat-00003");

            assertThat(r.getNewVersion()).isEqualTo("30.1.0.jre-redhat-00004");
            assertThat(r.isFirstBuild()).isFalse();
            assertThat(r.getBasedOn()).isEqualTo("30.1.0.jre-redhat-00003");
        }

        @Test
        void preservesDotSeparatorFromDa() throws ManipulationException {
            assertThat(incrementer.nextVersion("4.13.2", "4.13.2.redhat-00001").getNewVersion())
                    .isEqualTo("4.13.2.redhat-00002");
        }

        @Test
        void widensUnderPaddedSerialToDefaultWidth() throws ManipulationException {
            // "redhat-9" is only one digit; the next serial must be zero-padded to the default width.
            assertThat(incrementer.nextVersion("1.0", "1.0-redhat-9").getNewVersion())
                    .isEqualTo("1.0-redhat-00010");
        }

        @Test
        void keepsWiderThanDefaultPadding() throws ManipulationException {
            assertThat(incrementer.nextVersion("1.0", "1.0-redhat-0000042").getNewVersion())
                    .isEqualTo("1.0-redhat-0000043");
        }

        @Test
        void daLatestWithoutRedhatSuffixBecomesFirstSerialFromDaStem() throws ManipulationException {
            // DA returned a bare version with no recognisable redhat suffix: use it as the stem,
            // flagged as a first build, but remembering what DA returned.
            Result r = incrementer.nextVersion("2.0-declared", "9.9.9");

            assertThat(r.getNewVersion()).isEqualTo("9.9.9.redhat-00001");
            assertThat(r.isFirstBuild()).isTrue();
            assertThat(r.getBasedOn()).isEqualTo("9.9.9");
        }

        @Test
        void trimsSurroundingWhitespaceOnDaLatest() throws ManipulationException {
            assertThat(incrementer.nextVersion("1.0", "  1.0-redhat-00003  ").getNewVersion())
                    .isEqualTo("1.0-redhat-00004");
        }
    }

    @Nested
    class SnapshotHandling {

        @Test
        void dropsSnapshotFromBaseByDefault() throws ManipulationException {
            // Must never carry SNAPSHOT into the rebuild version. PME's getOsgiVersion() always rejoins
            // the numeric head to the qualifier with "." (12.0.0.M1), not the original "-" (12.0.0-M1).
            String result = incrementer.nextVersion("12.0.0-M1-SNAPSHOT", null).getNewVersion();

            assertThat(result).isEqualTo("12.0.0.M1-redhat-00001");
            assertThat(result).doesNotContain("SNAPSHOT");
        }

        @Test
        void dropsDotSnapshotDelimiterToo() throws ManipulationException {
            // After stripping ".SNAPSHOT" the stem "1.2.3" ends numeric, so the separator is ".".
            assertThat(incrementer.nextVersion("1.2.3.SNAPSHOT", null).getNewVersion())
                    .isEqualTo("1.2.3.redhat-00001");
        }

        @Test
        void isCaseInsensitiveAboutSnapshot() throws ManipulationException {
            assertThat(incrementer.nextVersion("1.2.3-snapshot", null).getNewVersion())
                    .isEqualTo("1.2.3.redhat-00001");
        }

        @Test
        void dropsSnapshotWhenIncrementingExistingBuild() throws ManipulationException {
            assertThat(incrementer.nextVersion("1.0", "1.0-redhat-00003-SNAPSHOT").getNewVersion())
                    .isEqualTo("1.0-redhat-00004");
        }

        @Test
        void preserveSnapshotReappendsAtVeryEndForFirstBuild() throws ManipulationException {
            VersionIncrementer preserving = new VersionIncrementer("redhat", 5, true);

            assertThat(preserving.nextVersion("12.0.0-M1-SNAPSHOT", null).getNewVersion())
                    .isEqualTo("12.0.0.M1-redhat-00001-SNAPSHOT");
        }

        @Test
        void preserveSnapshotReappendsAtVeryEndWhenIncrementing() throws ManipulationException {
            VersionIncrementer preserving = new VersionIncrementer("redhat", 5, true);

            assertThat(preserving.nextVersion("1.0", "1.0-redhat-00003-SNAPSHOT").getNewVersion())
                    .isEqualTo("1.0-redhat-00004-SNAPSHOT");
        }

        @Test
        void preserveSnapshotDoesNotAppendWhenInputHadNoSnapshot() throws ManipulationException {
            VersionIncrementer preserving = new VersionIncrementer("redhat", 5, true);

            assertThat(preserving.nextVersion("4.13.2", null).getNewVersion())
                    .isEqualTo("4.13.2.redhat-00001");
        }
    }

    @Nested
    class UnresolvableBaseVersionHandling {

        @Test
        void bareSnapshotBaseThrows() throws ManipulationException {
            // GH #10: "SNAPSHOT" alone has no real base to build a serial from. Continuing would publish
            // the coordinate under a meaningless "-redhat-00001" (or, worse, silently leave it unchanged
            // with only a log line to notice by) — so this is a hard failure instead.
            assertThatThrownBy(() -> incrementer.nextVersion("SNAPSHOT", null))
                    .isInstanceOf(ManipulationException.class)
                    .hasMessageContaining("SNAPSHOT");
        }

        @Test
        void bareSnapshotIsCaseInsensitive() throws ManipulationException {
            assertThatThrownBy(() -> incrementer.nextVersion("snapshot", null))
                    .isInstanceOf(ManipulationException.class);
        }

        @Test
        void bareSnapshotDaLatestThrows() throws ManipulationException {
            // DA itself returning a bare "SNAPSHOT" as latestVersion is just as unusable as a declared one.
            assertThatThrownBy(() -> incrementer.nextVersion("1.2.3", "SNAPSHOT"))
                    .isInstanceOf(ManipulationException.class);
        }

        @Test
        void blankDeclaredVersionWithNoDaLatestThrows() throws ManipulationException {
            // Degenerate input: no base, no DA latest. PME/Maven/Atlas would never accept a blank or
            // empty version in the first place, so there is no sane version to synthesise here either.
            assertThatThrownBy(() -> incrementer.nextVersion(null, null))
                    .isInstanceOf(ManipulationException.class);
        }

        @Test
        void normalFirstBuildDoesNotThrow() throws ManipulationException {
            assertThat(incrementer.nextVersion("4.13.2", null).getNewVersion()).isNotNull();
        }

        @Test
        void normalIncrementDoesNotThrow() throws ManipulationException {
            assertThat(incrementer.nextVersion("1.0", "1.0-redhat-00003").getNewVersion()).isNotNull();
        }
    }

    @Nested
    class DevMarkerHandling {

        /** Opt-in stripper: dev markers are kept by default, so callers must configure them. */
        private final VersionIncrementer stripsDev = new VersionIncrementer("redhat", 5, false, "dev");

        @Test
        void keepsDevMarkerByDefault() throws ManipulationException {
            // Default keeps -dev; stripping is opt-in via versionSuffixStrip. PME's getOsgiVersion()
            // rejoins the numeric head to the qualifier with "." (10.1.0.dev), not the original "-".
            assertThat(incrementer.nextVersion("10.1.0-dev", null).getNewVersion())
                    .isEqualTo("10.1.0.dev-redhat-00001");
        }

        @Test
        void stripsDevMarkerWhenConfigured() throws ManipulationException {
            // With versionSuffixStrip=dev, Tomcat's install version 10.1.0-dev aligns to
            // 10.1.0.redhat-00001, matching the release path, not 10.1.0-dev-redhat-00001.
            assertThat(stripsDev.nextVersion("10.1.0-dev", null).getNewVersion())
                    .isEqualTo("10.1.0.redhat-00001");
        }

        @Test
        void isCaseInsensitiveAboutDevMarker() throws ManipulationException {
            assertThat(stripsDev.nextVersion("10.1.0-DEV", null).getNewVersion())
                    .isEqualTo("10.1.0.redhat-00001");
        }

        @Test
        void stripsDevMarkerWithDotDelimiter() throws ManipulationException {
            assertThat(stripsDev.nextVersion("10.1.0.dev", null).getNewVersion())
                    .isEqualTo("10.1.0.redhat-00001");
        }

        @Test
        void stripsBothSnapshotAndDevTails() throws ManipulationException {
            assertThat(stripsDev.nextVersion("10.1.0-dev-SNAPSHOT", null).getNewVersion())
                    .isEqualTo("10.1.0.redhat-00001");
        }

        @Test
        void collapsesStackedDevMarkers() throws ManipulationException {
            assertThat(stripsDev.nextVersion("10.1.0-dev-dev", null).getNewVersion())
                    .isEqualTo("10.1.0.redhat-00001");
        }

        @Test
        void honoursCustomDevMarkers() throws ManipulationException {
            VersionIncrementer custom = new VersionIncrementer("redhat", 5, false, "dev,alpha");

            assertThat(custom.nextVersion("2.0.0-alpha", null).getNewVersion())
                    .isEqualTo("2.0.0.redhat-00001");
        }

        @Test
        void emptyDevMarkersDisablesStripping() throws ManipulationException {
            VersionIncrementer noStrip = new VersionIncrementer("redhat", 5, false, "");

            assertThat(noStrip.nextVersion("10.1.0-dev", null).getNewVersion())
                    .isEqualTo("10.1.0.dev-redhat-00001");
        }

        @Test
        void leavesUnrelatedQualifiersAlone() throws ManipulationException {
            // "Final" is not a dev marker, so it stays (and drives the '-' separator).
            assertThat(stripsDev.nextVersion("1.0.0.Final", null).getNewVersion())
                    .isEqualTo("1.0.0.Final-redhat-00001");
        }
    }

    @Nested
    class Configuration {

        @Test
        void honoursCustomSuffixAndPadding() throws ManipulationException {
            VersionIncrementer custom = new VersionIncrementer("temporary-redhat", 3);

            assertThat(custom.nextVersion("1.0.0", null).getNewVersion())
                    .isEqualTo("1.0.0.temporary-redhat-001");
        }

        @Test
        void nonPositivePaddingFallsBackToDefault() throws ManipulationException {
            VersionIncrementer custom = new VersionIncrementer("redhat", 0);

            assertThat(custom.nextVersion("1.0.0", null).getNewVersion())
                    .isEqualTo("1.0.0.redhat-00001");
        }
    }

    @Nested
    class Summarize {

        @Test
        void describesFirstBuild() throws ManipulationException {
            assertThat(incrementer.nextVersion("4.13.2", null).summarize())
                    .isEqualTo("4.13.2.redhat-00001  (first build)");
        }

        @Test
        void describesIncrement() throws ManipulationException {
            assertThat(incrementer.nextVersion("1.0", "1.0-redhat-00003").summarize())
                    .isEqualTo("1.0-redhat-00004  (increment of 1.0-redhat-00003)");
        }
    }
}
