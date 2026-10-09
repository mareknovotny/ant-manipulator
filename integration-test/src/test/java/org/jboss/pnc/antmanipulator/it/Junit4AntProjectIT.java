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
 * End-to-end tests driving the assembled CLI against a real-world Ant project that declares no
 * Maven-style coordinate anywhere: JUnit 4 at its own {@code 4.8} release (see
 * {@code src/test/resources/junit4-4.8}, a verbatim {@code build.xml} from
 * <a href="https://github.com/junit-team/junit4">junit-team/junit4</a> tag {@code r4.8}).
 *
 * <p>
 * Unlike the Jackson fixture ({@link JacksonAntProjectIT}), this build has no pom template, no
 * {@code ivy.xml}, nothing naming a groupId/artifactId - only a bare {@code version} property. This is
 * exactly the scenario the README documents {@code -Dalignment.groupId}/{@code -Dalignment.artifactId}
 * for (the same situation as Xalan): the user supplies the coordinate the project never declares, and
 * the version is picked up from the root build file's own {@code version} property for free.
 */
class Junit4AntProjectIT {

    private static final String FIXTURE = "junit4-4.8";

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
    void withoutInjectionTheVersionIsFoundButNotAsAGroupArtifact() throws IOException {
        int exitCode = new Cli().run(new String[] { "-f", buildFile.getAbsolutePath() });

        assertThat(exitCode).isEqualTo(0);

        String report = readFile(projectDir.resolve("alignment-report.json").toFile());

        // No groupId/artifactId anywhere in the tree, so no artifact is correlated...
        assertThat(report).contains("\"artifacts\": 0").contains("\"artifacts\": []");
        // ...but the bare `version` property is still surfaced as a project-level candidate.
        assertThat(report)
                .contains("\"projectVersionCandidates\": 1")
                .contains("\"version\": \"4.8\"")
                .contains("\"sourceKind\": \"PROPERTY\"")
                .contains("\"confidence\": \"LOW\"");
    }

    @Test
    void previewWithInjectionDoesNotModifyTheBuildFile() throws IOException {
        String before = readFile(buildFile);
        daServer = stubFirstBuildDaServer();

        int exitCode = new Cli().run(
                new String[] {
                        "-f",
                        buildFile.getAbsolutePath(),
                        "-Dalignment.groupId=junit",
                        "-Dalignment.artifactId=junit",
                        "-DrestURL=" + daBaseUrl(),
                        "--preview" });

        assertThat(exitCode).isEqualTo(0);
        assertThat(readFile(buildFile)).isEqualTo(before);
    }

    @Test
    void withInjectionAlignsAndRewritesTheInjectedJunitJunitCoordinate() throws IOException {
        daServer = stubFirstBuildDaServer();

        int exitCode = new Cli().run(
                new String[] {
                        "-f",
                        buildFile.getAbsolutePath(),
                        "-Dalignment.groupId=junit",
                        "-Dalignment.artifactId=junit",
                        "-DrestURL=" + daBaseUrl() });

        assertThat(exitCode).isEqualTo(0);

        String report = readFile(projectDir.resolve("alignment-report.json").toFile());
        // The injected coordinate picks up its version from the root build file's own `version`
        // property - no -Dalignment.version needed - and resolves it straight away: the build defines
        // version-base/version-status as plain literals, so Ant's own property engine already collapses
        // `version` to a concrete "4.8" with no token left to reconcile.
        assertThat(report)
                .contains("\"groupId\": \"junit\"")
                .contains("\"artifactId\": \"junit\"")
                .contains("\"primaryVersion\": \"4.8\"")
                .contains("\"sourceKind\": \"OVERRIDE\"")
                .contains("\"detail\": \"user-supplied alignment properties (version from root build file)\"");

        // DA has never seen this coordinate before, so the tool synthesises the first build and pins it
        // everywhere "4.8" defines the version: the version-base property, the version property's own
        // ${version-base}${version-status} expression, and the <filter> that stamps it into source.
        String rewrittenBuildFile = readFile(buildFile);
        assertThat(rewrittenBuildFile)
                .contains("<property name=\"version-base\" value=\"4.8.redhat-00001\" />")
                .contains("<property name=\"version\" value=\"4.8.redhat-00001\" />")
                .contains("<filter token=\"version\" value=\"4.8.redhat-00001\" />");
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
