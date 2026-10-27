package org.jboss.pnc.antmanipulator.gav;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

import org.jboss.pnc.antmanipulator.gav.VersionReconciler.Resolution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link VersionReconciler}, driven by small synthetic build trees written to a
 * {@link TempDir}. These reproduce — in miniature — the token chains seen in the real corpora: Ant
 * {@code ${property}} expansion, {@code @TOKEN@} filter substitution, the two chained together, the
 * per-target {@code <param>} fallback (Tomcat), multi-valued ambiguity (Jackson) and the {@code -D}
 * override for a value only produced at runtime.
 */
class VersionReconcilerTest {

    private static File write(Path dir, String name, String content) throws IOException {
        File f = dir.resolve(name).toFile();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private static VersionReconciler forBuild(Path dir, File buildFile) {
        return VersionReconciler.forTree(dir.toFile(), buildFile);
    }

    @Test
    void nonTokenisedVersionResolvesToItself(@TempDir Path dir) throws IOException {
        File build = write(dir, "build.xml", "<project name=\"p\"></project>");

        Resolution r = forBuild(dir, build).reconcile("1.2.3");

        assertThat(r.isResolved()).isTrue();
        assertThat(r.getResolved()).containsExactly("1.2.3");
    }

    @Test
    void expandsAntProperty(@TempDir Path dir) throws IOException {
        File build = write(
                dir,
                "build.xml",
                "<project name=\"p\">\n"
                        + "  <property name=\"impl.version\" value=\"1.9.1\"/>\n"
                        + "</project>");

        Resolution r = forBuild(dir, build).reconcile("${impl.version}");

        assertThat(r.isResolved()).isTrue();
        assertThat(r.getResolved()).containsExactly("1.9.1");
    }

    @Test
    void chainsFilterTokenThroughProperty(@TempDir Path dir) throws IOException {
        // @VERSION@ -> <filter value="${impl.version}"> -> 1.9.1
        File build = write(
                dir,
                "build.xml",
                "<project name=\"p\">\n"
                        + "  <property name=\"impl.version\" value=\"1.9.1\"/>\n"
                        + "  <filter token=\"VERSION\" value=\"${impl.version}\"/>\n"
                        + "</project>");

        Resolution r = forBuild(dir, build).reconcile("@VERSION@");

        assertThat(r.isResolved()).isTrue();
        assertThat(r.getResolved()).containsExactly("1.9.1");
    }

    @Test
    void reportsAmbiguityWhenAtokenHasMultipleValues(@TempDir Path dir) throws IOException {
        // Jackson-like: VERSION is filtered to the release in one place and the snapshot in another.
        File build = write(
                dir,
                "build.xml",
                "<project name=\"p\">\n"
                        + "  <property name=\"impl.version\" value=\"1.9.1\"/>\n"
                        + "  <filter token=\"VERSION\" value=\"${impl.version}\"/>\n"
                        + "  <filter token=\"VERSION\" value=\"${impl.version}-SNAPSHOT\"/>\n"
                        + "</project>");

        Resolution r = forBuild(dir, build).reconcile("@VERSION@");

        assertThat(r.getResolved()).containsExactlyInAnyOrder("1.9.1", "1.9.1-SNAPSHOT");
        assertThat(r.isAmbiguous()).isTrue();
        assertThat(r.isResolved()).isTrue();
    }

    @Test
    void fallsBackToTargetScopedParamDefinition(@TempDir Path dir) throws IOException {
        // Tomcat-like: @MAVEN.DEPLOY.VERSION@ -> ${maven.deploy.version}, which is not a global Ant
        // property but a per-target <param>; the reconciler harvests those and branches over them.
        File build = write(
                dir,
                "build.xml",
                "<project name=\"p\">\n"
                        + "  <filter token=\"MAVEN.DEPLOY.VERSION\" value=\"${maven.deploy.version}\"/>\n"
                        + "  <target name=\"deploy\">\n"
                        + "    <param name=\"maven.deploy.version\" value=\"12.0.0-M1\"/>\n"
                        + "  </target>\n"
                        + "</project>");

        Resolution r = forBuild(dir, build).reconcile("@MAVEN.DEPLOY.VERSION@");

        assertThat(r.isResolved()).isTrue();
        assertThat(r.getResolved()).containsExactly("12.0.0-M1");
    }

    @Test
    void overrideTakesPrecedenceForRuntimeOnlyProperty(@TempDir Path dir) throws IOException {
        // The base property is produced only at runtime (no static definition); the -D override supplies it.
        File build = write(dir, "build.xml", "<project name=\"p\"></project>");
        Properties overrides = new Properties();
        overrides.setProperty("maven.asf.release.deploy.version", "12.0.0-M1");

        Resolution r = VersionReconciler.forTree(dir.toFile(), build, overrides)
                .reconcile("${maven.asf.release.deploy.version}");

        assertThat(r.isResolved()).isTrue();
        assertThat(r.getResolved()).containsExactly("12.0.0-M1");
    }

    @Test
    void unknownPropertyStaysUnresolved(@TempDir Path dir) throws IOException {
        File build = write(dir, "build.xml", "<project name=\"p\"></project>");

        Resolution r = forBuild(dir, build).reconcile("${nowhere.defined}");

        assertThat(r.isUnresolved()).isTrue();
        assertThat(r.getResolved()).isEmpty();
        assertThat(r.getRemaining()).containsExactly("${nowhere.defined}");
    }

    @Test
    void nullVersionYieldsEmptyResolution(@TempDir Path dir) throws IOException {
        File build = write(dir, "build.xml", "<project name=\"p\"></project>");

        Resolution r = forBuild(dir, build).reconcile(null);

        assertThat(r.getResolved()).isEmpty();
        assertThat(r.getRemaining()).isEmpty();
        assertThat(r.isUnresolved()).isTrue();
    }

    @Test
    void collectsDefinitionsFromOtherXmlFilesInTree(@TempDir Path dir) throws IOException {
        // The filter lives in a separate publish file reached only via subant, not in the root build.
        File build = write(
                dir,
                "build.xml",
                "<project name=\"p\">\n"
                        + "  <property name=\"impl.version\" value=\"2.0.0\"/>\n"
                        + "</project>");
        write(
                dir,
                "publish.xml",
                "<project name=\"pub\">\n"
                        + "  <filter token=\"VERSION\" value=\"${impl.version}\"/>\n"
                        + "</project>");

        Resolution r = forBuild(dir, build).reconcile("@VERSION@");

        assertThat(r.getResolved()).containsExactly("2.0.0");
    }

    // ---- unreferenced nested build.xml exclusion (GitHub issue #44) ----

    @Test
    void definitionInUnreferencedNestedBuildDirIsExcludedWhenHierarchyKnown(@TempDir Path dir) throws IOException {
        // Mirrors Apache Commons Net's abandoned proposal/ftp2: a self-contained Ant project (its own
        // build.xml) bundled in the tree but never executed from the root. Its filter definitions must
        // not leak into reconciling some other, real coordinate's token.
        File build = write(dir, "build.xml", "<project name=\"p\"/>");
        Files.createDirectories(dir.resolve("proposal/ftp2"));
        write(dir.resolve("proposal/ftp2"), "build.xml", "<project name=\"ftp2\"/>");
        write(
                dir.resolve("proposal/ftp2"),
                "project.xml",
                "<project><filter token=\"VERSION\" value=\"9.9.9\"/></project>");
        Set<File> reachable = Collections.singleton(build.getCanonicalFile()); // only the root was crawled

        Resolution r = VersionReconciler.forTree(dir.toFile(), build, new Properties(), reachable)
                .reconcile("@VERSION@");

        assertThat(r.isUnresolved()).isTrue();
    }

    @Test
    void definitionInReferencedNestedBuildDirIsIncluded(@TempDir Path dir) throws IOException {
        File build = write(dir, "build.xml", "<project name=\"p\"/>");
        Files.createDirectories(dir.resolve("sub"));
        File subBuild = write(dir.resolve("sub"), "build.xml", "<project name=\"sub\"/>");
        write(dir.resolve("sub"), "publish.xml", "<project><filter token=\"VERSION\" value=\"9.9.9\"/></project>");
        Set<File> reachable = new LinkedHashSet<>();
        reachable.add(build.getCanonicalFile());
        reachable.add(subBuild.getCanonicalFile());

        Resolution r = VersionReconciler.forTree(dir.toFile(), build, new Properties(), reachable)
                .reconcile("@VERSION@");

        assertThat(r.getResolved()).containsExactly("9.9.9");
    }

    @Test
    void nullReachableSetStillCollectsFromUnreferencedNestedBuildDir(@TempDir Path dir) throws IOException {
        // The 2-/3-arg overloads (hierarchy not crawled) must keep today's behaviour: scan everything.
        File build = write(dir, "build.xml", "<project name=\"p\"/>");
        Files.createDirectories(dir.resolve("proposal/ftp2"));
        write(dir.resolve("proposal/ftp2"), "build.xml", "<project name=\"ftp2\"/>");
        write(
                dir.resolve("proposal/ftp2"),
                "project.xml",
                "<project><filter token=\"VERSION\" value=\"9.9.9\"/></project>");

        Resolution r = forBuild(dir, build).reconcile("@VERSION@");

        assertThat(r.getResolved()).containsExactly("9.9.9");
    }
}
