package org.jboss.pnc.antmanipulator.groovy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GroovyScriptRunnerTest {

    private static final String HEADER = "import org.jboss.pnc.antmanipulator.groovy.AMEBaseScript\n"
            + "import org.jboss.pnc.antmanipulator.groovy.BaseScript\n"
            + "import org.jboss.pnc.antmanipulator.groovy.InvocationPoint\n"
            + "import org.jboss.pnc.antmanipulator.groovy.InvocationStage\n";

    @TempDir
    Path dir;

    private File script(String name, String body) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, body.getBytes(StandardCharsets.UTF_8));
        return p.toFile();
    }

    private GroovyScriptRunner runner(String scripts, Properties props) {
        return new GroovyScriptRunner(scripts, new File(dir.toFile(), "build.xml"), props, null);
    }

    @Test
    void noScriptsConfigured() {
        assertThat(runner(null, new Properties()).hasScripts()).isFalse();
        assertThat(runner("  , ", new Properties()).hasScripts()).isFalse();
    }

    @Test
    void scriptSeesApiAndRunsAtItsStage() throws IOException {
        File s = script(
                "a.groovy",
                HEADER + "@InvocationPoint(invocationPoint = InvocationStage.FIRST)\n"
                        + "@AMEBaseScript BaseScript ame\n"
                        + "new File(ame.getBaseDir(), 'out.txt').text = "
                        + "ame.getInvocationStage().toString() + ':' + ame.getUserProperties().getProperty('k')\n");
        Properties props = new Properties();
        props.setProperty("k", "v");

        runner(s.getAbsolutePath(), props).run(InvocationStage.FIRST, null);

        assertThat(dir.resolve("out.txt")).hasContent("FIRST:v");
    }

    @Test
    void preparseIsAliasForFirst() throws IOException {
        File s = script(
                "a.groovy",
                HEADER + "@InvocationPoint(invocationPoint = InvocationStage.PREPARSE)\n"
                        + "@AMEBaseScript BaseScript ame\n"
                        + "new File(ame.getBaseDir(), 'out.txt').text = ame.getInvocationStage().toString()\n");

        runner(s.getAbsolutePath(), new Properties()).run(InvocationStage.FIRST, null);

        assertThat(dir.resolve("out.txt")).hasContent("FIRST");
    }

    @Test
    void preparseDoesNotMatchLast() throws IOException {
        File s = script(
                "a.groovy",
                HEADER + "@InvocationPoint(invocationPoint = InvocationStage.PREPARSE)\n"
                        + "@AMEBaseScript BaseScript ame\n"
                        + "new File(ame.getBaseDir(), 'out.txt').text = 'ran'\n");

        runner(s.getAbsolutePath(), new Properties()).run(InvocationStage.LAST, null);

        assertThat(dir.resolve("out.txt")).doesNotExist();
    }

    @Test
    void scriptIsSkippedWhenStageDoesNotMatch() throws IOException {
        File s = script(
                "a.groovy",
                HEADER + "@InvocationPoint(invocationPoint = InvocationStage.LAST)\n"
                        + "@AMEBaseScript BaseScript ame\n"
                        + "new File(ame.getBaseDir(), 'out.txt').text = 'ran'\n");

        runner(s.getAbsolutePath(), new Properties()).run(InvocationStage.FIRST, null);

        assertThat(dir.resolve("out.txt")).doesNotExist();
    }

    @Test
    void allRunsAtEveryStageAndExposesAlignedVersions() throws IOException {
        File s = script(
                "a.groovy",
                HEADER + "@InvocationPoint(invocationPoint = InvocationStage.ALL)\n"
                        + "@AMEBaseScript BaseScript ame\n"
                        + "new File(ame.getBaseDir(), 'out.txt').append("
                        + "ame.getInvocationStage().toString() + '=' + ame.getAlignedVersions().get('1.0') + ';')\n");
        GroovyScriptRunner r = runner(s.getAbsolutePath(), new Properties());

        r.run(InvocationStage.FIRST, null);
        r.run(InvocationStage.LAST, Collections.singletonMap("1.0", "1.0.redhat-00001"));

        assertThat(dir.resolve("out.txt")).hasContent("FIRST=null;LAST=1.0.redhat-00001;");
    }

    @Test
    void scriptsRunInListedOrder() throws IOException {
        String tpl = HEADER + "@InvocationPoint(invocationPoint = InvocationStage.FIRST)\n"
                + "@AMEBaseScript BaseScript ame\n"
                + "new File(ame.getBaseDir(), 'out.txt').append('%s')\n";
        File one = script("one.groovy", String.format(tpl, "1"));
        File two = script("two.groovy", String.format(tpl, "2"));

        runner(two.getAbsolutePath() + "," + one.getAbsolutePath(), new Properties()).run(InvocationStage.FIRST, null);

        assertThat(dir.resolve("out.txt")).hasContent("21");
    }

    @Test
    void missingInvocationPointFails() throws IOException {
        File s = script("a.groovy", HEADER + "@AMEBaseScript BaseScript ame\n");

        assertThatThrownBy(() -> runner(s.getAbsolutePath(), new Properties()).run(InvocationStage.FIRST, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("InvocationPoint");
    }

    @Test
    void missingBaseScriptFails() throws IOException {
        File s = script(
                "a.groovy",
                HEADER + "@InvocationPoint(invocationPoint = InvocationStage.FIRST)\n" + "def x = 1\n");

        assertThatThrownBy(() -> runner(s.getAbsolutePath(), new Properties()).run(InvocationStage.FIRST, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AMEBaseScript");
    }

    @Test
    void scriptFailureIsReported() throws IOException {
        File s = script(
                "a.groovy",
                HEADER + "@InvocationPoint(invocationPoint = InvocationStage.FIRST)\n"
                        + "@AMEBaseScript BaseScript ame\n"
                        + "throw new RuntimeException('boom')\n");

        assertThatThrownBy(() -> runner(s.getAbsolutePath(), new Properties()).run(InvocationStage.FIRST, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("a.groovy")
                .hasRootCauseMessage("boom");
    }

    @Test
    void restApiWithoutRestUrlFails() throws IOException {
        File s = script(
                "a.groovy",
                HEADER + "@InvocationPoint(invocationPoint = InvocationStage.FIRST)\n"
                        + "@AMEBaseScript BaseScript ame\n"
                        + "ame.getRESTAPI()\n");

        assertThatThrownBy(() -> runner(s.getAbsolutePath(), new Properties()).run(InvocationStage.FIRST, null))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("restURL");
    }

    @Test
    void plainHttpIsRejected() {
        assertThatThrownBy(
                () -> runner("http://example.com/x.groovy", new Properties()).run(InvocationStage.FIRST, null))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage(
                        "Refusing to load Groovy script over plain HTTP (use https://): http://example.com/x.groovy");
    }

    @Test
    void missingFileFails() {
        assertThatThrownBy(
                () -> runner(dir.resolve("nope.groovy").toString(), new Properties()).run(InvocationStage.FIRST, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nope.groovy");
    }

    @Test
    void fileUrlIsAccepted() throws IOException {
        File s = script(
                "a.groovy",
                HEADER + "@InvocationPoint(invocationPoint = InvocationStage.FIRST)\n"
                        + "@AMEBaseScript BaseScript ame\n"
                        + "new File(ame.getBaseDir(), 'out.txt').text = 'ok'\n");

        runner(s.toURI().toString(), new Properties()).run(InvocationStage.FIRST, null);

        assertThat(dir.resolve("out.txt")).hasContent("ok");
    }
}
