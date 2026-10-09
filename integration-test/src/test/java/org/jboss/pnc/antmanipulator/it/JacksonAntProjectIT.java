package org.jboss.pnc.antmanipulator.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.jboss.pnc.antmanipulator.cli.Cli;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * End-to-end tests driving the assembled CLI against a real-world Ant project: Jackson 1.x
 * (see {@code src/test/resources/jackson-1-1.9.11}), a trimmed checkout of
 * <a href="https://github.com/FasterXML/jackson-1">FasterXML/jackson-1</a> as it was released as
 * {@code 1.9.11} (commit {@code d1c26ec5}, "update release notes for 1.9.11"). The fixture keeps only
 * the files that drive GAV resolution ({@code build.xml}, {@code src/VERSION.txt}, the eight
 * {@code src/maven/*.pom} templates); the {@code ant/build-*.xml} files the root build imports are
 * deliberately omitted — {@link Cli} only warns about unresolvable {@code <import>} references, it
 * doesn't fail, so trimming them keeps the fixture small without changing the behaviour under test.
 *
 * <p>
 * This project is a good real-world fixture because its version lives entirely behind a token chain —
 * every {@code src/maven/*.pom} declares {@code <version>@VERSION@</version>}, which is only filled in
 * at build time from {@code <property name="IMPL_VERSION" value="1.9.11" />} via Ant's {@code <filter>}
 * mechanism — exactly the kind of indirection {@link org.jboss.pnc.antmanipulator.gav.VersionReconciler}
 * exists to follow, and that a Maven POM would never need.
 */
class JacksonAntProjectIT {

    private static final String FIXTURE = "jackson-1-1.9.11";

    private static final String[] ARTIFACT_IDS = {
            "jackson-core-asl",
            "jackson-core-lgpl",
            "jackson-jaxrs",
            "jackson-mapper-asl",
            "jackson-mapper-lgpl",
            "jackson-mrbean",
            "jackson-smile",
            "jackson-xc" };

    @TempDir
    Path projectDir;

    private File buildFile;
    private WireMockServer daServer;

    @BeforeEach
    void copyFixture() throws IOException, URISyntaxException {
        FixtureCopier.copyResourceDir(FIXTURE, projectDir);
        buildFile = projectDir.resolve("build.xml").toFile();
    }

    @AfterEach
    void stopDaServer() {
        if (daServer != null) {
            daServer.stop();
        }
    }

    @Test
    void discoversAllJacksonArtifactsAndReconcilesTheReleaseVersion() throws IOException {
        int exitCode = new Cli().run(new String[] { "-f", buildFile.getAbsolutePath() });

        assertThat(exitCode).isEqualTo(0);

        File reportFile = projectDir.resolve("alignment-report.json").toFile();
        assertThat(reportFile).exists();
        String report = readFile(reportFile);

        // Every one of Jackson 1.x's eight published modules was found, correlated without conflict,
        // and its tokenised @VERSION@ was reconciled back to the release via IMPL_VERSION.
        assertThat(report).contains("\"artifacts\": 8").contains("\"conflicts\": 0");
        for (String artifactId : ARTIFACT_IDS) {
            assertThat(report)
                    .contains("\"groupId\": \"org.codehaus.jackson\"")
                    .contains("\"artifactId\": \"" + artifactId + "\"");
        }

        // IMPL_VERSION="1.9.11" also matches the (unrelated) `-SNAPSHOT` filter Jackson's build defines for
        // its nightly/snapshot poms, so the token resolves ambiguously to both variants - the tool reports
        // this rather than silently guessing one.
        assertThat(report)
                .contains("\"ambiguous\": true")
                .contains("\"1.9.11\"")
                .contains("\"1.9.11-SNAPSHOT\"");
    }

    @Test
    void previewDoesNotModifyTheBuildFile() throws IOException {
        String before = readFile(buildFile);
        daServer = stubFirstBuildDaServer();

        int exitCode = new Cli().run(
                new String[] {
                        "-f",
                        buildFile.getAbsolutePath(),
                        "-DrestURL=" + daBaseUrl(),
                        "--preview" });

        assertThat(exitCode).isEqualTo(0);
        assertThat(readFile(buildFile)).isEqualTo(before);
    }

    @Test
    void alignsAndRewritesTheReleaseVersionAsAFirstBuild() throws IOException {
        daServer = stubFirstBuildDaServer();

        int exitCode = new Cli().run(
                new String[] {
                        "-f",
                        buildFile.getAbsolutePath(),
                        "-DrestURL=" + daBaseUrl() });

        assertThat(exitCode).isEqualTo(0);

        // DA has never seen this coordinate before (empty lookup/maven/latest response), so the tool
        // synthesises the first build: IMPL_VERSION's definition site is rewritten in place...
        String rewrittenBuildFile = readFile(buildFile);
        assertThat(rewrittenBuildFile).contains("<property name=\"IMPL_VERSION\" value=\"1.9.11.redhat-00001\" />");

        // ...but the @VERSION@ token inside the Maven POM templates is left alone: VersionRewriter only
        // pins Maven-1 project.xml/<currentVersion> and Ant property/param/filter/info sites, not a plain
        // Maven-2 <version> element, so the templates still expand correctly at Jackson's own build time.
        for (String artifactId : ARTIFACT_IDS) {
            File pom = projectDir.resolve("src/maven/" + artifactId + ".pom").toFile();
            assertThat(readFile(pom)).contains("<version>@VERSION@</version>");
        }
    }

    private WireMockServer stubFirstBuildDaServer() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        server.stubFor(
                post(urlEqualTo("/lookup/maven/latest"))
                        .willReturn(
                                aResponse().withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("[]")));
        return server;
    }

    private String daBaseUrl() {
        return "http://localhost:" + daServer.port();
    }

    private static String readFile(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
