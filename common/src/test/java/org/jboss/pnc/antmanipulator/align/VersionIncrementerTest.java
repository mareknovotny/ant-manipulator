package org.jboss.pnc.antmanipulator.align;

import static org.assertj.core.api.Assertions.assertThat;

import org.jboss.pnc.antmanipulator.align.VersionIncrementer.Result;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VersionIncrementer}, the PME {@code VersionCalculator} analogue. These lock in
 * the two branches (first build vs increment of an existing DA build), the OSGi-style separator choice,
 * padding preservation/widening, and — critically — the SNAPSHOT handling: a {@code -SNAPSHOT} in the
 * input must NEVER be carried through into the middle of the rebuild version.
 */
class VersionIncrementerTest {

    private final VersionIncrementer incrementer = VersionIncrementer.defaults();

    @Nested
    class FirstBuild {

        @Test
        void numericTailUsesDotSeparator() {
            Result r = incrementer.nextVersion("4.13.2", null);

            assertThat(r.getNewVersion()).isEqualTo("4.13.2.redhat-00001");
            assertThat(r.isFirstBuild()).isTrue();
            assertThat(r.getBasedOn()).isNull();
        }

        @Test
        void qualifierTailUsesDashSeparator() {
            assertThat(incrementer.nextVersion("3.8-beta1", null).getNewVersion())
                    .isEqualTo("3.8-beta1-redhat-00001");
        }

        @Test
        void alphaFinalQualifierUsesDashSeparator() {
            assertThat(incrementer.nextVersion("1.0.0.Final", null).getNewVersion())
                    .isEqualTo("1.0.0.Final-redhat-00001");
        }

        @Test
        void nullBaseAndNullLatestStillProducesFirstSerial() {
            // Degenerate input: no base, no DA latest. Should not throw.
            Result r = incrementer.nextVersion(null, null);

            assertThat(r.getNewVersion()).isEqualTo("-redhat-00001");
            assertThat(r.isFirstBuild()).isTrue();
        }

        @Test
        void blankDaLatestIsTreatedAsFirstBuildFromBase() {
            Result r = incrementer.nextVersion("4.13.2", "   ");

            assertThat(r.getNewVersion()).isEqualTo("4.13.2.redhat-00001");
            assertThat(r.getBasedOn()).isNull();
        }
    }

    @Nested
    class IncrementExisting {

        @Test
        void incrementsSerialPreservingStemSeparatorAndWidth() {
            Result r = incrementer.nextVersion("30.1-jre", "30.1.0.jre-redhat-00003");

            assertThat(r.getNewVersion()).isEqualTo("30.1.0.jre-redhat-00004");
            assertThat(r.isFirstBuild()).isFalse();
            assertThat(r.getBasedOn()).isEqualTo("30.1.0.jre-redhat-00003");
        }

        @Test
        void preservesDotSeparatorFromDa() {
            assertThat(incrementer.nextVersion("4.13.2", "4.13.2.redhat-00001").getNewVersion())
                    .isEqualTo("4.13.2.redhat-00002");
        }

        @Test
        void widensUnderPaddedSerialToDefaultWidth() {
            // "redhat-9" is only one digit; the next serial must be zero-padded to the default width.
            assertThat(incrementer.nextVersion("1.0", "1.0-redhat-9").getNewVersion())
                    .isEqualTo("1.0-redhat-00010");
        }

        @Test
        void keepsWiderThanDefaultPadding() {
            assertThat(incrementer.nextVersion("1.0", "1.0-redhat-0000042").getNewVersion())
                    .isEqualTo("1.0-redhat-0000043");
        }

        @Test
        void daLatestWithoutRedhatSuffixBecomesFirstSerialFromDaStem() {
            // DA returned a bare version with no recognisable redhat suffix: use it as the stem,
            // flagged as a first build, but remembering what DA returned.
            Result r = incrementer.nextVersion("2.0-declared", "9.9.9");

            assertThat(r.getNewVersion()).isEqualTo("9.9.9.redhat-00001");
            assertThat(r.isFirstBuild()).isTrue();
            assertThat(r.getBasedOn()).isEqualTo("9.9.9");
        }

        @Test
        void trimsSurroundingWhitespaceOnDaLatest() {
            assertThat(incrementer.nextVersion("1.0", "  1.0-redhat-00003  ").getNewVersion())
                    .isEqualTo("1.0-redhat-00004");
        }
    }

    @Nested
    class SnapshotHandling {

        @Test
        void dropsSnapshotFromBaseByDefault() {
            // The bug we fixed: must be 12.0.0-M1-redhat-00001, NEVER 12.0.0-M1-SNAPSHOT-redhat-00001.
            String result = incrementer.nextVersion("12.0.0-M1-SNAPSHOT", null).getNewVersion();

            assertThat(result).isEqualTo("12.0.0-M1-redhat-00001");
            assertThat(result).doesNotContain("SNAPSHOT");
        }

        @Test
        void dropsDotSnapshotDelimiterToo() {
            // After stripping ".SNAPSHOT" the stem "1.2.3" ends numeric, so the separator is ".".
            assertThat(incrementer.nextVersion("1.2.3.SNAPSHOT", null).getNewVersion())
                    .isEqualTo("1.2.3.redhat-00001");
        }

        @Test
        void isCaseInsensitiveAboutSnapshot() {
            assertThat(incrementer.nextVersion("1.2.3-snapshot", null).getNewVersion())
                    .isEqualTo("1.2.3.redhat-00001");
        }

        @Test
        void dropsSnapshotWhenIncrementingExistingBuild() {
            assertThat(incrementer.nextVersion("1.0", "1.0-redhat-00003-SNAPSHOT").getNewVersion())
                    .isEqualTo("1.0-redhat-00004");
        }

        @Test
        void preserveSnapshotReappendsAtVeryEndForFirstBuild() {
            VersionIncrementer preserving = new VersionIncrementer("redhat", 5, true);

            assertThat(preserving.nextVersion("12.0.0-M1-SNAPSHOT", null).getNewVersion())
                    .isEqualTo("12.0.0-M1-redhat-00001-SNAPSHOT");
        }

        @Test
        void preserveSnapshotReappendsAtVeryEndWhenIncrementing() {
            VersionIncrementer preserving = new VersionIncrementer("redhat", 5, true);

            assertThat(preserving.nextVersion("1.0", "1.0-redhat-00003-SNAPSHOT").getNewVersion())
                    .isEqualTo("1.0-redhat-00004-SNAPSHOT");
        }

        @Test
        void preserveSnapshotDoesNotAppendWhenInputHadNoSnapshot() {
            VersionIncrementer preserving = new VersionIncrementer("redhat", 5, true);

            assertThat(preserving.nextVersion("4.13.2", null).getNewVersion())
                    .isEqualTo("4.13.2.redhat-00001");
        }
    }

    @Nested
    class DevMarkerHandling {

        /** Opt-in stripper: dev markers are kept by default, so callers must configure them. */
        private final VersionIncrementer stripsDev = new VersionIncrementer("redhat", 5, false, "dev");

        @Test
        void keepsDevMarkerByDefault() {
            // Default keeps -dev; stripping is opt-in via versionSuffixStrip.
            assertThat(incrementer.nextVersion("10.1.0-dev", null).getNewVersion())
                    .isEqualTo("10.1.0-dev-redhat-00001");
        }

        @Test
        void stripsDevMarkerWhenConfigured() {
            // With versionSuffixStrip=dev, Tomcat's install version 10.1.0-dev aligns to
            // 10.1.0.redhat-00001, matching the release path, not 10.1.0-dev-redhat-00001.
            assertThat(stripsDev.nextVersion("10.1.0-dev", null).getNewVersion())
                    .isEqualTo("10.1.0.redhat-00001");
        }

        @Test
        void isCaseInsensitiveAboutDevMarker() {
            assertThat(stripsDev.nextVersion("10.1.0-DEV", null).getNewVersion())
                    .isEqualTo("10.1.0.redhat-00001");
        }

        @Test
        void stripsDevMarkerWithDotDelimiter() {
            assertThat(stripsDev.nextVersion("10.1.0.dev", null).getNewVersion())
                    .isEqualTo("10.1.0.redhat-00001");
        }

        @Test
        void stripsBothSnapshotAndDevTails() {
            assertThat(stripsDev.nextVersion("10.1.0-dev-SNAPSHOT", null).getNewVersion())
                    .isEqualTo("10.1.0.redhat-00001");
        }

        @Test
        void collapsesStackedDevMarkers() {
            assertThat(stripsDev.nextVersion("10.1.0-dev-dev", null).getNewVersion())
                    .isEqualTo("10.1.0.redhat-00001");
        }

        @Test
        void honoursCustomDevMarkers() {
            VersionIncrementer custom = new VersionIncrementer("redhat", 5, false, "dev,alpha");

            assertThat(custom.nextVersion("2.0.0-alpha", null).getNewVersion())
                    .isEqualTo("2.0.0.redhat-00001");
        }

        @Test
        void emptyDevMarkersDisablesStripping() {
            VersionIncrementer noStrip = new VersionIncrementer("redhat", 5, false, "");

            assertThat(noStrip.nextVersion("10.1.0-dev", null).getNewVersion())
                    .isEqualTo("10.1.0-dev-redhat-00001");
        }

        @Test
        void leavesUnrelatedQualifiersAlone() {
            // "Final" is not a dev marker, so it stays (and drives the '-' separator).
            assertThat(stripsDev.nextVersion("1.0.0.Final", null).getNewVersion())
                    .isEqualTo("1.0.0.Final-redhat-00001");
        }
    }

    @Nested
    class Configuration {

        @Test
        void honoursCustomSuffixAndPadding() {
            VersionIncrementer custom = new VersionIncrementer("temporary-redhat", 3);

            assertThat(custom.nextVersion("1.0.0", null).getNewVersion())
                    .isEqualTo("1.0.0.temporary-redhat-001");
        }

        @Test
        void nonPositivePaddingFallsBackToDefault() {
            VersionIncrementer custom = new VersionIncrementer("redhat", 0);

            assertThat(custom.nextVersion("1.0.0", null).getNewVersion())
                    .isEqualTo("1.0.0.redhat-00001");
        }
    }

    @Nested
    class Summarize {

        @Test
        void describesFirstBuild() {
            assertThat(incrementer.nextVersion("4.13.2", null).summarize())
                    .isEqualTo("4.13.2.redhat-00001  (first build)");
        }

        @Test
        void describesIncrement() {
            assertThat(incrementer.nextVersion("1.0", "1.0-redhat-00003").summarize())
                    .isEqualTo("1.0-redhat-00004  (increment of 1.0-redhat-00003)");
        }
    }
}
