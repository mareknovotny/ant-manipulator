package org.jboss.pnc.antmanipulator.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.jboss.pnc.antmanipulator.gav.GavResolver;
import org.jboss.pnc.antmanipulator.gav.ResolvedGav;
import org.jboss.pnc.antmanipulator.gav.ResolvedGav.SourceKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Wires {@link BuildFileScanner} to {@link GavResolver} exactly as {@link Cli} does (GitHub issue #44), to
 * check that a module the root build really does invoke is not mistaken for an unreferenced, abandoned
 * project and excluded from coordinate discovery. Each fixture has a sub-module with its own
 * {@code build.xml} and Maven-1 {@code project.xml} that the root build executes through a different Ant
 * idiom.
 */
class UnreferencedBuildDirExclusionTest {

    private static File write(Path dir, String name, String content) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        return p.toFile();
    }

    private static void addModule(Path root, String module, String version) throws IOException {
        Path dir = root.resolve(module);
        write(dir, "build.xml", "<project name=\"" + module + "\"/>");
        write(
                dir,
                "project.xml",
                "<project><id>" + module + "</id><currentVersion>" + version + "</currentVersion></project>");
    }

    /** Same steps as {@code Cli.call()}: crawl, collect reachable build files, resolve. */
    private static List<String> discoveredMaven1Coordinates(File rootBuild) throws Exception {
        BuildFileScanner.ScanResult scan = new BuildFileScanner().crawl(rootBuild);
        Set<File> reachable = new LinkedHashSet<>();
        for (RawBuildFilePeek peek : scan.getPeeks()) {
            reachable.add(peek.getBuildFile());
        }
        List<ResolvedGav> gavs = new GavResolver().resolve(rootBuild, new Properties(), reachable);
        return gavs.stream()
                .filter(g -> g.getKind() == SourceKind.MAVEN1_POM)
                .map(ResolvedGav::coordinate)
                .collect(java.util.stream.Collectors.toList());
    }

    @Test
    void controlAntWithAntfileIsDiscovered(@TempDir Path dir) throws Exception {
        File root = write(
                dir,
                "build.xml",
                "<project name=\"root\"><target name=\"t\">"
                        + "<ant antfile=\"core/build.xml\"/></target></project>");
        addModule(dir, "core", "1.0");

        assertThat(discoveredMaven1Coordinates(root)).containsExactly("core:core:1.0");
    }

    @Test
    void antWithDirAndDefaultAntfileIsDiscovered(@TempDir Path dir) throws Exception {
        // <ant dir="core"/>: antfile defaults to build.xml inside dir.
        File root = write(
                dir,
                "build.xml",
                "<project name=\"root\"><target name=\"t\">"
                        + "<ant dir=\"core\"/></target></project>");
        addModule(dir, "core", "1.0");

        assertThat(discoveredMaven1Coordinates(root)).containsExactly("core:core:1.0");
    }

    @Test
    void subantWithFilesetIsDiscovered(@TempDir Path dir) throws Exception {
        File root = write(
                dir,
                "build.xml",
                "<project name=\"root\"><target name=\"t\">"
                        + "<subant><fileset dir=\".\" includes=\"*/build.xml\"/></subant></target></project>");
        addModule(dir, "core", "1.0");
        addModule(dir, "web", "2.0");

        assertThat(discoveredMaven1Coordinates(root)).containsExactlyInAnyOrder("core:core:1.0", "web:web:2.0");
    }

    @Test
    void subantWithDirsetAndAntfileIsDiscovered(@TempDir Path dir) throws Exception {
        File root = write(
                dir,
                "build.xml",
                "<project name=\"root\"><target name=\"t\">"
                        + "<subant antfile=\"build.xml\"><dirset dir=\".\" includes=\"core,web\"/></subant>"
                        + "</target></project>");
        addModule(dir, "core", "1.0");
        addModule(dir, "web", "2.0");

        assertThat(discoveredMaven1Coordinates(root)).containsExactlyInAnyOrder("core:core:1.0", "web:web:2.0");
    }

    @Test
    void antWithPropertyInPathIsDiscovered(@TempDir Path dir) throws Exception {
        // The path can't be resolved statically, so the scanner records it as "unresolved" and does not follow
        // it. The module must not be excluded just because we couldn't prove Ant reaches it.
        File root = write(
                dir,
                "build.xml",
                "<project name=\"root\"><property name=\"m\" value=\"core\"/>"
                        + "<target name=\"t\"><ant antfile=\"${m}/build.xml\"/></target></project>");
        addModule(dir, "core", "1.0");

        assertThat(discoveredMaven1Coordinates(root)).containsExactly("core:core:1.0");
    }

    @Test
    void antWithDirAndCustomAntfileIsDiscovered(@TempDir Path dir) throws Exception {
        File root = write(
                dir,
                "build.xml",
                "<project name=\"root\"><target name=\"t\">"
                        + "<ant dir=\"core\" antfile=\"custom-build.xml\"/></target></project>");
        Path coreDir = dir.resolve("core");
        write(coreDir, "custom-build.xml", "<project name=\"core\"/>");
        write(
                coreDir,
                "project.xml",
                "<project><id>core</id><currentVersion>1.0</currentVersion></project>");

        assertThat(discoveredMaven1Coordinates(root)).containsExactly("core:core:1.0");
    }

    @Test
    void antWithDirPropertyIsDiscovered(@TempDir Path dir) throws Exception {
        File root = write(
                dir,
                "build.xml",
                "<project name=\"root\"><property name=\"c\" value=\"core\"/>"
                        + "<target name=\"t\"><ant dir=\"${c}\"/></target></project>");
        addModule(dir, "core", "1.0");

        assertThat(discoveredMaven1Coordinates(root)).containsExactly("core:core:1.0");
    }

    @Test
    void antWithPropertyFileIsDiscovered(@TempDir Path dir) throws Exception {
        write(dir, "build.properties", "module.dir=core\n");
        File root = write(
                dir,
                "build.xml",
                "<project name=\"root\"><property file=\"build.properties\"/>"
                        + "<target name=\"t\"><ant antfile=\"${module.dir}/build.xml\"/></target></project>");
        addModule(dir, "core", "1.0");

        assertThat(discoveredMaven1Coordinates(root)).containsExactly("core:core:1.0");
    }

    @Test
    void subantWithBuildpathAttributeIsDiscovered(@TempDir Path dir) throws Exception {
        File root = write(
                dir,
                "build.xml",
                "<project name=\"root\"><target name=\"t\">"
                        + "<subant buildpath=\"core:web\"/></target></project>");
        addModule(dir, "core", "1.0");
        addModule(dir, "web", "2.0");

        assertThat(discoveredMaven1Coordinates(root)).containsExactlyInAnyOrder("core:core:1.0", "web:web:2.0");
    }

    @Test
    void subantWithBuildpathElementIsDiscovered(@TempDir Path dir) throws Exception {
        File root = write(
                dir,
                "build.xml",
                "<project name=\"root\"><target name=\"t\">"
                        + "<subant><buildpath><pathelement location=\"core\"/><pathelement location=\"web\"/></buildpath></subant>"
                        + "</target></project>");
        addModule(dir, "core", "1.0");
        addModule(dir, "web", "2.0");

        assertThat(discoveredMaven1Coordinates(root)).containsExactlyInAnyOrder("core:core:1.0", "web:web:2.0");
    }

    @Test
    void subantWithFileListIsDiscovered(@TempDir Path dir) throws Exception {
        File root = write(
                dir,
                "build.xml",
                "<project name=\"root\"><target name=\"t\">"
                        + "<subant><filelist dir=\".\" files=\"core/build.xml, web/build.xml\"/></subant>"
                        + "</target></project>");
        addModule(dir, "core", "1.0");
        addModule(dir, "web", "2.0");

        assertThat(discoveredMaven1Coordinates(root)).containsExactlyInAnyOrder("core:core:1.0", "web:web:2.0");
    }

    @Test
    void subantWithNestedIncludeExcludeIsDiscovered(@TempDir Path dir) throws Exception {
        File root = write(
                dir,
                "build.xml",
                "<project name=\"root\"><target name=\"t\">"
                        + "<subant><fileset dir=\".\"><include name=\"*/build.xml\"/><exclude name=\"proposal/**\"/></fileset></subant>"
                        + "</target></project>");
        addModule(dir, "core", "1.0");
        addModule(dir, "web", "2.0");
        addModule(dir, "proposal", "SNAPSHOT");

        assertThat(discoveredMaven1Coordinates(root)).containsExactlyInAnyOrder("core:core:1.0", "web:web:2.0");
    }

    @Test
    void trulyUnreferencedModuleIsStillExcluded(@TempDir Path dir) throws Exception {
        // Guards against "fixing" the cases above by never excluding anything.
        File root = write(
                dir,
                "build.xml",
                "<project name=\"root\"><target name=\"t\">"
                        + "<ant antfile=\"core/build.xml\"/></target></project>");
        addModule(dir, "core", "1.0");
        addModule(dir, "proposal", "SNAPSHOT");

        assertThat(discoveredMaven1Coordinates(root)).containsExactly("core:core:1.0");
    }
}
