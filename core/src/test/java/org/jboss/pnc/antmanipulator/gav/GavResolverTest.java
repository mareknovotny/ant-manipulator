package org.jboss.pnc.antmanipulator.gav;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.jboss.pnc.antmanipulator.gav.ResolvedGav.Confidence;
import org.jboss.pnc.antmanipulator.gav.ResolvedGav.SourceKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link GavResolver} and its {@link GavResolver.GavSource}s, exercised against tiny
 * synthetic project trees under a {@link TempDir}. Each test targets one source (pom template, Maven-1
 * {@code project.xml}, Ivy, properties, manifest/version.txt, and the {@code -Dalignment.*} override),
 * plus the parent/property fallbacks and the consumer-ivy exclusion that are easy to regress.
 */
class GavResolverTest {

    private final GavResolver resolver = new GavResolver();

    private static File write(Path dir, String name, String content) throws IOException {
        File f = dir.resolve(name).toFile();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    /** Findings of one kind, for focused assertions on a tree that may also emit low-confidence noise. */
    private static List<ResolvedGav> ofKind(List<ResolvedGav> all, SourceKind kind) {
        return all.stream().filter(g -> g.getKind() == kind).collect(java.util.stream.Collectors.toList());
    }

    // ---- pom templates ----

    @Test
    void readsPomTemplateCoordinate(@TempDir Path dir) throws IOException {
        File build = write(dir, "build.xml", "<project name=\"p\"/>");
        write(
                dir,
                "pom.xml",
                "<project><groupId>org.acme</groupId><artifactId>widget</artifactId>"
                        + "<version>1.0</version></project>");

        List<ResolvedGav> pom = ofKind(resolver.resolve(build), SourceKind.POM_TEMPLATE);

        assertThat(pom).hasSize(1);
        assertThat(pom.get(0).coordinate()).isEqualTo("org.acme:widget:1.0");
        assertThat(pom.get(0).getConfidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    void pomTemplateInheritsGroupAndVersionFromParent(@TempDir Path dir) throws IOException {
        File build = write(dir, "build.xml", "<project name=\"p\"/>");
        write(
                dir,
                "pom.xml",
                "<project>\n"
                        + "  <parent><groupId>org.acme</groupId><version>2.0</version></parent>\n"
                        + "  <artifactId>child</artifactId>\n"
                        + "</project>");

        List<ResolvedGav> pom = ofKind(resolver.resolve(build), SourceKind.POM_TEMPLATE);

        assertThat(pom).hasSize(1);
        assertThat(pom.get(0).coordinate()).isEqualTo("org.acme:child:2.0");
    }

    @Test
    void pomTemplateVersionExpandedFromBuildProperties(@TempDir Path dir) throws IOException {
        File build = write(
                dir,
                "build.xml",
                "<project name=\"p\"><property name=\"pom.version\" value=\"3.1.4\"/></project>");
        write(
                dir,
                "widget-pom-template.xml",
                "<project><groupId>org.acme</groupId><artifactId>widget</artifactId>"
                        + "<version>${pom.version}</version></project>");

        List<ResolvedGav> pom = ofKind(resolver.resolve(build), SourceKind.POM_TEMPLATE);

        assertThat(pom).hasSize(1);
        assertThat(pom.get(0).getVersion()).isEqualTo("3.1.4");
    }

    // ---- Maven-1 project.xml ----

    @Test
    void readsMaven1IdAsGroupAndArtifact(@TempDir Path dir) throws IOException {
        File build = write(dir, "build.xml", "<project name=\"p\"/>");
        write(
                dir,
                "project.xml",
                "<project><id>dom4j</id><currentVersion>1.6.1</currentVersion></project>");

        List<ResolvedGav> m1 = ofKind(resolver.resolve(build), SourceKind.MAVEN1_POM);

        assertThat(m1).hasSize(1);
        assertThat(m1.get(0).coordinate()).isEqualTo("dom4j:dom4j:1.6.1");
    }

    @Test
    void maven1PrefersExplicitGroupAndArtifact(@TempDir Path dir) throws IOException {
        File build = write(dir, "build.xml", "<project name=\"p\"/>");
        write(
                dir,
                "project.xml",
                "<project><id>legacy</id><groupId>org.acme</groupId><artifactId>widget</artifactId>"
                        + "<currentVersion>1.6.1</currentVersion></project>");

        List<ResolvedGav> m1 = ofKind(resolver.resolve(build), SourceKind.MAVEN1_POM);

        assertThat(m1).hasSize(1);
        assertThat(m1.get(0).coordinate()).isEqualTo("org.acme:widget:1.6.1");
    }

    @Test
    void projectXmlWithoutMaven1MarkersIsNotAMaven1Coordinate(@TempDir Path dir) throws IOException {
        // A Maven-2 POM that merely happens to be named project.xml must not be read as Maven-1.
        File build = write(dir, "build.xml", "<project name=\"p\"/>");
        write(
                dir,
                "project.xml",
                "<project><groupId>org.acme</groupId><artifactId>widget</artifactId>"
                        + "<version>1.0</version></project>");

        assertThat(ofKind(resolver.resolve(build), SourceKind.MAVEN1_POM)).isEmpty();
    }

    // ---- Ivy ----

    @Test
    void readsIvyPublicationsWithNormalisedOrganisation(@TempDir Path dir) throws IOException {
        File build = write(dir, "build.xml", "<project name=\"p\"/>");
        write(
                dir,
                "ivy.xml",
                "<ivy-module version=\"2.0\">\n"
                        + "  <info organisation=\"org/apache\" module=\"widget\" revision=\"1.0\"/>\n"
                        + "  <publications>\n"
                        + "    <artifact name=\"widget\"/>\n"
                        + "    <artifact name=\"widget-tools\"/>\n"
                        + "  </publications>\n"
                        + "</ivy-module>");

        List<ResolvedGav> ivy = ofKind(resolver.resolve(build), SourceKind.IVY);

        assertThat(ivy).extracting(ResolvedGav::coordinate)
                .containsExactlyInAnyOrder("org.apache:widget:1.0", "org.apache:widget-tools:1.0");
        assertThat(ivy).allMatch(g -> g.getConfidence() == Confidence.MEDIUM);
    }

    @Test
    void ivyInfoUsedAsFallbackWhenNoPublications(@TempDir Path dir) throws IOException {
        File build = write(dir, "build.xml", "<project name=\"p\"/>");
        write(
                dir,
                "ivy.xml",
                "<ivy-module version=\"2.0\">"
                        + "<info organisation=\"org.acme\" module=\"widget\" revision=\"1.0\"/></ivy-module>");

        List<ResolvedGav> ivy = ofKind(resolver.resolve(build), SourceKind.IVY);

        assertThat(ivy).hasSize(1);
        assertThat(ivy.get(0).coordinate()).isEqualTo("org.acme:widget:1.0");
        assertThat(ivy.get(0).getConfidence()).isEqualTo(Confidence.LOW);
    }

    @Test
    void consumerIvyWithDependenciesAndNoPublicationsIsSkipped(@TempDir Path dir) throws IOException {
        File build = write(dir, "build.xml", "<project name=\"p\"/>");
        write(
                dir,
                "ivy.xml",
                "<ivy-module version=\"2.0\">\n"
                        + "  <info organisation=\"org.acme\" module=\"builder\" revision=\"1.0\"/>\n"
                        + "  <dependencies><dependency org=\"junit\" name=\"junit\" rev=\"4.13\"/></dependencies>\n"
                        + "</ivy-module>");

        assertThat(ofKind(resolver.resolve(build), SourceKind.IVY)).isEmpty();
    }

    // ---- properties ----

    @Test
    void readsWellKnownVersionPropertyFromBuild(@TempDir Path dir) throws IOException {
        File build = write(
                dir,
                "build.xml",
                "<project name=\"p\"><property name=\"project.version\" value=\"2.0\"/></project>");

        List<ResolvedGav> props = ofKind(resolver.resolve(build), SourceKind.PROPERTY);

        assertThat(props).extracting(ResolvedGav::getVersion).contains("2.0");
        assertThat(props).allMatch(g -> g.getGroupId() == null && g.getArtifactId() == null);
    }

    @Test
    void readsVersionKeyFromPropertiesFile(@TempDir Path dir) throws IOException {
        File build = write(dir, "build.xml", "<project name=\"p\"/>");
        write(dir, "versions.properties", "release.version=3.0\njunit.version=4.13\n");

        List<ResolvedGav> props = ofKind(resolver.resolve(build), SourceKind.PROPERTY);

        // release.version is a well-known key; junit.version is dependency noise we deliberately ignore.
        assertThat(props).extracting(ResolvedGav::getVersion).containsExactly("3.0");
    }

    // ---- manifest / version.txt ----

    @Test
    void readsManifestCoordinate(@TempDir Path dir) throws IOException {
        File build = write(dir, "build.xml", "<project name=\"p\"/>");
        write(
                dir,
                "MANIFEST.MF",
                "Manifest-Version: 1.0\r\n"
                        + "Implementation-Vendor-Id: org.acme\r\n"
                        + "Implementation-Title: widget\r\n"
                        + "Implementation-Version: 1.2.3\r\n");

        List<ResolvedGav> mf = ofKind(resolver.resolve(build), SourceKind.MANIFEST);

        assertThat(mf).extracting(ResolvedGav::coordinate).contains("org.acme:widget:1.2.3");
    }

    @Test
    void readsVersionTxt(@TempDir Path dir) throws IOException {
        File build = write(dir, "build.xml", "<project name=\"p\"/>");
        write(dir, "version.txt", "VERSION=1.2.3\n");

        List<ResolvedGav> mf = ofKind(resolver.resolve(build), SourceKind.MANIFEST);

        assertThat(mf).extracting(ResolvedGav::getVersion).contains("1.2.3");
    }

    // ---- override source ----

    @Test
    void overrideSuppliesCompleteCoordinate(@TempDir Path dir) throws IOException {
        File build = write(dir, "build.xml", "<project name=\"p\"/>");
        Properties overrides = new Properties();
        overrides.setProperty(GavResolver.ALIGN_GROUP_ID, "org.acme");
        overrides.setProperty(GavResolver.ALIGN_ARTIFACT_ID, "widget");
        overrides.setProperty(GavResolver.ALIGN_VERSION, "9.9");

        List<ResolvedGav> ov = ofKind(resolver.resolve(build, overrides), SourceKind.OVERRIDE);

        assertThat(ov).hasSize(1);
        assertThat(ov.get(0).coordinate()).isEqualTo("org.acme:widget:9.9");
        assertThat(ov.get(0).getConfidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    void overrideBorrowsVersionFromRootBuildWhenOmitted(@TempDir Path dir) throws IOException {
        File build = write(
                dir,
                "build.xml",
                "<project name=\"p\"><property name=\"widget.version\" value=\"4.0\"/></project>");
        Properties overrides = new Properties();
        overrides.setProperty(GavResolver.ALIGN_GROUP_ID, "org.acme");
        overrides.setProperty(GavResolver.ALIGN_ARTIFACT_ID, "widget");

        List<ResolvedGav> ov = ofKind(resolver.resolve(build, overrides), SourceKind.OVERRIDE);

        assertThat(ov).hasSize(1);
        assertThat(ov.get(0).coordinate()).isEqualTo("org.acme:widget:4.0");
    }

    // ---- aggregate behaviour ----

    @Test
    void emptyProjectYieldsNoFindings(@TempDir Path dir) throws IOException {
        File build = write(dir, "build.xml", "<project name=\"p\"/>");

        assertThat(resolver.resolve(build)).isEmpty();
    }

    @Test
    void findingsAreSortedMostTrustworthyFirst(@TempDir Path dir) throws IOException {
        File build = write(
                dir,
                "build.xml",
                "<project name=\"p\"><property name=\"project.version\" value=\"1.0\"/></project>");
        write(
                dir,
                "pom.xml",
                "<project><groupId>org.acme</groupId><artifactId>widget</artifactId>"
                        + "<version>1.0</version></project>");

        List<ResolvedGav> all = resolver.resolve(build);

        // The HIGH-confidence pom template must sort ahead of the LOW-confidence property finding.
        assertThat(all.get(0).getConfidence()).isEqualTo(Confidence.HIGH);
        assertThat(all).extracting(ResolvedGav::getConfidence).isSorted();
    }
}
