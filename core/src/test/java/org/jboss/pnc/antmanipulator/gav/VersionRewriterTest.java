package org.jboss.pnc.antmanipulator.gav;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jboss.pnc.antmanipulator.gav.VersionRewriter.Edit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link VersionRewriter}. Fixtures are written to a {@link TempDir}; tests cover literal
 * rewrites, reconciled-expression rewrites, the ambiguity guard, the various version-defining site kinds
 * (Ant attribute, Ivy revision, Maven-1 element, .properties), multi-line elements, and that
 * {@link VersionRewriter#apply} is surgical, writes no backup, preserves CRLF, and skips stale edits.
 */
class VersionRewriterTest {

    private static File write(Path dir, String name, String content) throws IOException {
        File f = dir.resolve(name).toFile();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static Map<String, String> map(String base, String target) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(base, target);
        return m;
    }

    // ---- planning: literal sites ----

    @Test
    void rewritesLiteralPropertyValue(@TempDir Path dir) throws IOException {
        File build = write(
                dir,
                "build.xml",
                "<project><property name=\"version.id\" value=\"3.8-beta1\"/></project>");

        List<Edit> edits = VersionRewriter.plan(dir.toFile(), map("3.8-beta1", "3.8-beta1-redhat-00001"));

        assertThat(edits).hasSize(1);
        Edit e = edits.get(0);
        assertThat(e.getOldVersion()).isEqualTo("3.8-beta1");
        assertThat(e.getNewVersion()).isEqualTo("3.8-beta1-redhat-00001");
        assertThat(e.getFile()).isEqualTo(build);
        assertThat(e.summarize()).contains("version.id");
    }

    @Test
    void doesNotRewriteVersionLiteralInProse(@TempDir Path dir) throws IOException {
        // The literal appears only in element text, not in a version-defining attribute.
        write(dir, "build.xml", "<project><echo>please use 3.8-beta1 today</echo></project>");

        List<Edit> edits = VersionRewriter.plan(dir.toFile(), map("3.8-beta1", "3.8-beta1-redhat-00001"));

        assertThat(edits).isEmpty();
    }

    @Test
    void rewritesIvyRevision(@TempDir Path dir) throws IOException {
        write(
                dir,
                "ivy.xml",
                "<ivy-module><info organisation=\"org.acme\" module=\"widget\" revision=\"1.0\"/></ivy-module>");

        List<Edit> edits = VersionRewriter.plan(dir.toFile(), map("1.0", "1.0-redhat-00001"));

        assertThat(edits).hasSize(1);
        assertThat(edits.get(0).getNewVersion()).isEqualTo("1.0-redhat-00001");
        assertThat(edits.get(0).summarize()).contains("ivy revision");
    }

    @Test
    void rewritesMaven1CurrentVersion(@TempDir Path dir) throws IOException {
        write(dir, "project.xml", "<project><currentVersion>1.4</currentVersion></project>");

        List<Edit> edits = VersionRewriter.plan(dir.toFile(), map("1.4", "1.4-redhat-00001"));

        assertThat(edits).hasSize(1);
        assertThat(edits.get(0).getNewVersion()).isEqualTo("1.4-redhat-00001");
        assertThat(edits.get(0).summarize()).contains("maven-1");
    }

    @Test
    void rewritesPropertiesFileEntry(@TempDir Path dir) throws IOException {
        write(dir, "build.properties", "# header\nversion.id=3.8-beta1\nother=keep\n");

        List<Edit> edits = VersionRewriter.plan(dir.toFile(), map("3.8-beta1", "3.8-beta1-redhat-00001"));

        assertThat(edits).hasSize(1);
        assertThat(edits.get(0).getNewVersion()).isEqualTo("3.8-beta1-redhat-00001");
    }

    @Test
    void ignoresCommentedPropertiesLine(@TempDir Path dir) throws IOException {
        write(dir, "build.properties", "#version.id=3.8-beta1\n");

        List<Edit> edits = VersionRewriter.plan(dir.toFile(), map("3.8-beta1", "3.8-beta1-redhat-00001"));

        assertThat(edits).isEmpty();
    }

    // ---- planning: reconciled expressions ----

    @Test
    void rewritesExpressionThatReconcilesToSingleBase(@TempDir Path dir) throws IOException {
        File build = write(
                dir,
                "build.xml",
                "<project name=\"p\">\n"
                        + "  <property name=\"impl.version\" value=\"1.9.1\"/>\n"
                        + "  <filter token=\"VERSION\" value=\"${impl.version}\"/>\n"
                        + "  <property name=\"pinned\" value=\"@VERSION@\"/>\n"
                        + "</project>");
        VersionReconciler reconciler = VersionReconciler.forTree(dir.toFile(), build);

        List<Edit> edits = VersionRewriter.plan(dir.toFile(), map("1.9.1", "1.9.1-redhat-00001"), reconciler);

        Edit pinned = edits.stream()
                .filter(e -> "@VERSION@".equals(e.getOldVersion()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected the @VERSION@ site to be rewritten"));
        assertThat(pinned.getNewVersion()).isEqualTo("1.9.1-redhat-00001");
    }

    @Test
    void skipsExpressionThatReconcilesAmbiguously(@TempDir Path dir) throws IOException {
        File build = write(
                dir,
                "build.xml",
                "<project name=\"p\">\n"
                        + "  <property name=\"impl.version\" value=\"1.9.1\"/>\n"
                        + "  <filter token=\"VERSION\" value=\"${impl.version}\"/>\n"
                        + "  <filter token=\"VERSION\" value=\"${impl.version}-SNAPSHOT\"/>\n"
                        + "  <property name=\"pinned\" value=\"@VERSION@\"/>\n"
                        + "</project>");
        VersionReconciler reconciler = VersionReconciler.forTree(dir.toFile(), build);
        Map<String, String> m = map("1.9.1", "1.9.1-redhat-00001");
        m.put("1.9.1-SNAPSHOT", "1.9.1-SNAPSHOT-redhat-00001");

        List<Edit> edits = VersionRewriter.plan(dir.toFile(), m, reconciler);

        // The @VERSION@ site resolves to two different targets, so it must be left alone.
        assertThat(edits).noneMatch(e -> "@VERSION@".equals(e.getOldVersion()));
    }

    @Test
    void rewritesValueAttributeSplitAcrossLines(@TempDir Path dir) throws IOException {
        File build = write(
                dir,
                "build.xml",
                "<project name=\"p\">\n"
                        + "  <property name=\"impl.version\" value=\"1.9.1\"/>\n"
                        + "  <param name=\"deploy\"\n"
                        + "         value=\"${impl.version}\"/>\n"
                        + "</project>");
        VersionReconciler reconciler = VersionReconciler.forTree(dir.toFile(), build);

        List<Edit> edits = VersionRewriter.plan(dir.toFile(), map("1.9.1", "1.9.1-redhat-00001"), reconciler);

        // The continuation-line value= must be recognised via the element-aware scan.
        assertThat(edits).anyMatch(
                e -> "${impl.version}".equals(e.getOldVersion())
                        && "1.9.1-redhat-00001".equals(e.getNewVersion()));
    }

    @Test
    void emptyVersionMapPlansNothing(@TempDir Path dir) throws IOException {
        write(dir, "build.xml", "<project><property name=\"v\" value=\"1.0\"/></project>");

        assertThat(VersionRewriter.plan(dir.toFile(), Collections.emptyMap())).isEmpty();
    }

    // ---- apply ----

    @Test
    void applyEditsSurgicallyWithoutBackup(@TempDir Path dir) throws IOException {
        File build = write(
                dir,
                "build.xml",
                "<project>\n  <property name=\"version.id\" value=\"3.8-beta1\"/>\n  <echo>keep me</echo>\n</project>");
        List<Edit> edits = VersionRewriter.plan(dir.toFile(), map("3.8-beta1", "3.8-beta1-redhat-00001"));

        List<File> changed = VersionRewriter.apply(edits);

        assertThat(changed).containsExactly(build);
        String after = read(build);
        assertThat(after).contains("value=\"3.8-beta1-redhat-00001\"");
        assertThat(after).contains("<echo>keep me</echo>"); // untouched lines preserved
        assertThat(new File(dir.toFile(), "build.xml.bak")).doesNotExist(); // no backups written
    }

    @Test
    void applyPreservesCrlfLineEndings(@TempDir Path dir) throws IOException {
        File build = write(
                dir,
                "build.xml",
                "<project>\r\n  <property name=\"version.id\" value=\"3.8-beta1\"/>\r\n</project>\r\n");
        List<Edit> edits = VersionRewriter.plan(dir.toFile(), map("3.8-beta1", "3.8-beta1-redhat-00001"));

        VersionRewriter.apply(edits);

        String after = read(build);
        assertThat(after).contains("\r\n");
        assertThat(after).contains("value=\"3.8-beta1-redhat-00001\"");
    }

    @Test
    void applySkipsStaleEditWhenLineNoLongerMatches(@TempDir Path dir) throws IOException {
        File build = write(
                dir,
                "build.xml",
                "<project><property name=\"version.id\" value=\"3.8-beta1\"/></project>");
        List<Edit> edits = VersionRewriter.plan(dir.toFile(), map("3.8-beta1", "3.8-beta1-redhat-00001"));
        // Mutate the file after planning so the recorded `before` line no longer matches.
        write(dir, "build.xml", "<project><property name=\"version.id\" value=\"9.9.9\"/></project>");

        List<File> changed = VersionRewriter.apply(edits);

        assertThat(changed).isEmpty();
        assertThat(read(build)).contains("value=\"9.9.9\""); // untouched
        assertThat(new File(dir.toFile(), "build.xml.bak")).doesNotExist();
    }
}
