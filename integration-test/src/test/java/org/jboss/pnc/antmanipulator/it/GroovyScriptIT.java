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
 * End-to-end test of {@code -DgroovyScripts}: against the Jackson fixture, a {@code FIRST} script edits the
 * build file on disk before it is read (so the pipeline aligns the edited version), and a {@code LAST} script
 * observes the computed versions.
 */
class GroovyScriptIT {

    private static final String HEADER = "import org.jboss.pnc.antmanipulator.groovy.AMEBaseScript\n"
            + "import org.jboss.pnc.antmanipulator.groovy.BaseScript\n"
            + "import org.jboss.pnc.antmanipulator.groovy.InvocationPoint\n"
            + "import org.jboss.pnc.antmanipulator.groovy.InvocationStage\n";

    @TempDir
    Path projectDir;

    private File buildFile;
    private WireMockServer daServer;

    @BeforeEach
    void copyFixture() throws IOException, URISyntaxException {
        FixtureCopier.copyResourceDir("jackson-1-1.9.11", projectDir);
        buildFile = projectDir.resolve("build.xml").toFile();
    }

    @AfterEach
    void stopDaServer() {
        if (daServer != null) {
            daServer.stop();
        }
    }

    @Test
    void firstScriptEditsBuildFileBeforeAlignmentAndLastScriptSeesComputedVersions() throws IOException {
        Path first = projectDir.resolve("first.groovy");
        write(
                first,
                HEADER + "@InvocationPoint(invocationPoint = InvocationStage.FIRST)\n"
                        + "@AMEBaseScript BaseScript ame\n"
                        + "def f = ame.getBuildFile()\n"
                        + "f.text = f.text.replace('name=\"IMPL_VERSION\" value=\"1.9.11\"', "
                        + "'name=\"IMPL_VERSION\" value=\"1.9.12\"')\n");
        Path last = projectDir.resolve("last.groovy");
        write(
                last,
                HEADER + "@InvocationPoint(invocationPoint = InvocationStage.LAST)\n"
                        + "@AMEBaseScript BaseScript ame\n"
                        + "new File(ame.getBaseDir(), 'seen.txt').text = ame.getAlignedVersions().get('1.9.12')\n");

        daServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        daServer.start();
        daServer.stubFor(
                post(urlEqualTo("/lookup/maven/latest"))
                        .willReturn(
                                aResponse().withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("[]")));

        int exitCode = new Cli().run(
                new String[] {
                        "-f",
                        buildFile.getAbsolutePath(),
                        "-DrestURL=http://localhost:" + daServer.port(),
                        "-DversionIncrementalSuffix=redhat",
                        "-DgroovyScripts=" + first + "," + last });

        assertThat(exitCode).isEqualTo(0);
        assertThat(read(buildFile.toPath()))
                .contains("<property name=\"IMPL_VERSION\" value=\"1.9.12.redhat-00001\" />");
        assertThat(read(projectDir.resolve("seen.txt"))).isEqualTo("1.9.12.redhat-00001");
    }

    @Test
    void failingScriptAbortsTheRun() throws IOException {
        Path script = projectDir.resolve("bad.groovy");
        write(
                script,
                HEADER + "@InvocationPoint(invocationPoint = InvocationStage.FIRST)\n"
                        + "@AMEBaseScript BaseScript ame\n"
                        + "throw new RuntimeException('boom')\n");

        int exitCode = new Cli().run(new String[] { "-f", buildFile.getAbsolutePath(), "-DgroovyScripts=" + script });

        assertThat(exitCode).isEqualTo(10);
        assertThat(projectDir.resolve("discovery-report.json")).doesNotExist();
        assertThat(projectDir.resolve("alignmentReport.json")).doesNotExist();
    }

    private static void write(Path p, String s) throws IOException {
        Files.write(p, s.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
