package org.jboss.pnc.antmanipulator.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jboss.pnc.antmanipulator.gav.GavCorrelator;
import org.jboss.pnc.antmanipulator.gav.ResolvedGav;
import org.jboss.pnc.antmanipulator.gav.ResolvedGav.Confidence;
import org.jboss.pnc.antmanipulator.gav.ResolvedGav.SourceKind;
import org.jboss.pnc.antmanipulator.gav.VersionReconciler;
import org.jboss.pnc.mavenmanipulator.common.json.PME;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link PmeAlignmentReport}: verifies it produces PME's own {@link PME} JSON shape (a diff
 * of groupId:artifactId:version changes), not a rediscovery of every candidate.
 */
class PmeAlignmentReportTest {

    @TempDir
    File tempDir;

    private final GavCorrelator correlator = new GavCorrelator();

    private VersionReconciler reconciler() {
        File buildFile = new File(tempDir, "build.xml");
        return VersionReconciler.forTree(tempDir, buildFile);
    }

    @Test
    void reportsOnlyRealignedArtifacts() {
        File buildFile = new File(tempDir, "build.xml");
        GavCorrelator.Result correlated = correlator.correlate(
                Arrays.asList(
                        new ResolvedGav(
                                "org.acme",
                                "widget",
                                "1.0",
                                buildFile,
                                "detail",
                                SourceKind.POM_TEMPLATE,
                                Confidence.HIGH),
                        new ResolvedGav(
                                "org.acme",
                                "gadget",
                                "2.0",
                                null,
                                "detail",
                                SourceKind.IVY,
                                Confidence.HIGH)));

        Map<String, String> versionRewrites = new LinkedHashMap<>();
        versionRewrites.put("1.0", "1.0.redhat-00001");
        // gadget's 2.0 is intentionally absent from versionRewrites: it was not realigned.

        PME report = PmeAlignmentReport.build(buildFile, correlated, reconciler(), versionRewrites);

        assertThat(report.getModules()).hasSize(1);
        assertThat(report.getModules().get(0).getGav().getOriginalGAV()).isEqualTo("org.acme:widget:1.0");
        assertThat(report.getModules().get(0).getGav().getGroupId()).isEqualTo("org.acme");
        assertThat(report.getModules().get(0).getGav().getArtifactId()).isEqualTo("widget");
        assertThat(report.getModules().get(0).getGav().getVersion()).isEqualTo("1.0.redhat-00001");
    }

    @Test
    void executionRootPrefersArtifactDeclaredInRootBuildFile() {
        File buildFile = new File(tempDir, "build.xml");
        File otherFile = new File(tempDir, "ivy.xml");
        GavCorrelator.Result correlated = correlator.correlate(
                Arrays.asList(
                        new ResolvedGav(
                                "org.acme",
                                "gadget",
                                "2.0",
                                otherFile,
                                "detail",
                                SourceKind.IVY,
                                Confidence.HIGH),
                        new ResolvedGav(
                                "org.acme",
                                "widget",
                                "1.0",
                                buildFile,
                                "detail",
                                SourceKind.POM_TEMPLATE,
                                Confidence.HIGH)));

        Map<String, String> versionRewrites = new LinkedHashMap<>();
        versionRewrites.put("1.0", "1.0.redhat-00001");
        versionRewrites.put("2.0", "2.0.redhat-00001");

        PME report = PmeAlignmentReport.build(buildFile, correlated, reconciler(), versionRewrites);

        assertThat(report.getModules()).hasSize(2);
        assertThat(report.getGav().getArtifactId()).isEqualTo("widget");
        assertThat(report.getGav().getOriginalGAV()).isEqualTo("org.acme:widget:1.0");
        assertThat(report.getGav().getVersion()).isEqualTo("1.0.redhat-00001");
    }

    @Test
    void emptyWhenNothingRealigned() {
        File buildFile = new File(tempDir, "build.xml");
        GavCorrelator.Result correlated = correlator.correlate(
                Collections.singletonList(
                        new ResolvedGav(
                                "org.acme",
                                "widget",
                                "1.0",
                                buildFile,
                                "detail",
                                SourceKind.POM_TEMPLATE,
                                Confidence.HIGH)));

        PME report = PmeAlignmentReport.build(buildFile, correlated, reconciler(), Collections.emptyMap());

        assertThat(report.getModules()).isEmpty();
        assertThat(report.getGav().getGroupId()).isNull();
    }

    @Test
    void buildUnchangedReportsExecutionRootAtDeclaredVersion() {
        File buildFile = new File(tempDir, "build.xml");
        GavCorrelator.Result correlated = correlator.correlate(
                Collections.singletonList(
                        new ResolvedGav(
                                "org.acme",
                                "widget",
                                "1.0",
                                buildFile,
                                "detail",
                                SourceKind.POM_TEMPLATE,
                                Confidence.HIGH)));

        PME report = PmeAlignmentReport.buildUnchanged(buildFile, correlated, reconciler());

        // Nothing is realigned, so there are no module entries...
        assertThat(report.getModules()).isEmpty();
        // ...but the execution root still carries the project's *unchanged* coordinate, so PNC/Repour can
        // record and tag the build under its declared version (original == new, no suffix).
        assertThat(report.getGav().getArtifactId()).isEqualTo("widget");
        assertThat(report.getGav().getOriginalGAV()).isEqualTo("org.acme:widget:1.0");
        assertThat(report.getGav().getVersion()).isEqualTo("1.0");
    }

    @Test
    void writeToProducesPmeShapedJson() throws Exception {
        File buildFile = new File(tempDir, "build.xml");
        GavCorrelator.Result correlated = correlator.correlate(
                Collections.singletonList(
                        new ResolvedGav(
                                "org.acme",
                                "widget",
                                "1.0",
                                buildFile,
                                "detail",
                                SourceKind.POM_TEMPLATE,
                                Confidence.HIGH)));
        Map<String, String> versionRewrites = Collections.singletonMap("1.0", "1.0.redhat-00001");

        PME report = PmeAlignmentReport.build(buildFile, correlated, reconciler(), versionRewrites);
        File out = new File(tempDir, "alignmentReport.json");
        PmeAlignmentReport.writeTo(report, out);

        String json = new String(java.nio.file.Files.readAllBytes(out.toPath()));
        assertThat(json).contains("\"executionRoot\"").contains("\"modules\"").contains("org.acme");
    }
}
