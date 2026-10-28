package org.jboss.pnc.antmanipulator.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildFileScannerTest {

    private static File write(Path dir, String name, String content) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        return p.toFile();
    }

    @Test
    void crawlsHierarchyTransitively(@TempDir Path dir) throws Exception {
        File root = write(
                dir,
                "build.xml",
                "<project name=\"root\"><target name=\"t\"><ant antfile=\"sub/build.xml\"/></target></project>");
        Path sub = dir.resolve("sub");
        File subBuild = write(
                sub,
                "build.xml",
                "<project name=\"sub\"><target name=\"t\"><ant antfile=\"../leaf/build.xml\"/></target></project>");
        Path leaf = dir.resolve("leaf");
        File leafBuild = write(leaf, "build.xml", "<project name=\"leaf\"/>");

        BuildFileScanner.ScanResult scan = new BuildFileScanner().crawl(root);

        assertThat(scan.getPeeks()).hasSize(3);
        assertThat(scan.getPeeks().get(0).getBuildFile()).isEqualTo(root.getCanonicalFile());
        assertThat(scan.getPeeks().get(1).getBuildFile()).isEqualTo(subBuild.getCanonicalFile());
        assertThat(scan.getPeeks().get(2).getBuildFile()).isEqualTo(leafBuild.getCanonicalFile());
        assertThat(scan.getMissing()).isEmpty();
        assertThat(scan.getUnresolved()).isEmpty();
        assertThat(scan.getOutsideRootTree()).isEmpty();
    }

    @Test
    void handlesCyclesWithoutInfiniteLoop(@TempDir Path dir) throws Exception {
        Path aDir = dir.resolve("a");
        Path bDir = dir.resolve("b");
        File aBuild = write(
                aDir,
                "build.xml",
                "<project name=\"a\"><target name=\"t\"><ant antfile=\"../b/build.xml\"/></target></project>");
        File bBuild = write(
                bDir,
                "build.xml",
                "<project name=\"b\"><target name=\"t\"><ant antfile=\"../a/build.xml\"/></target></project>");

        BuildFileScanner.ScanResult scan = new BuildFileScanner().crawl(aBuild);

        assertThat(scan.getPeeks()).hasSize(2);
        assertThat(scan.getPeeks().get(0).getBuildFile()).isEqualTo(aBuild.getCanonicalFile());
        assertThat(scan.getPeeks().get(1).getBuildFile()).isEqualTo(bBuild.getCanonicalFile());
    }

    @Test
    void recordsMissingAndUnresolvedReferences(@TempDir Path dir) throws Exception {
        File root = write(
                dir,
                "build.xml",
                "<project name=\"root\"><target name=\"t\">"
                        + "<ant antfile=\"nonexistent/build.xml\"/>"
                        + "<ant antfile=\"${unknown.prop}/build.xml\"/>"
                        + "</target></project>");

        BuildFileScanner.ScanResult scan = new BuildFileScanner().crawl(root);

        assertThat(scan.getPeeks()).hasSize(1);
        assertThat(scan.getMissing()).hasSize(1);
        assertThat(scan.getMissing().get(0).getRawPath()).isEqualTo("nonexistent/build.xml");
        assertThat(scan.getUnresolved()).hasSize(1);
        assertThat(scan.getUnresolved().get(0).getRawPath()).isEqualTo("${unknown.prop}/build.xml");
    }

    @Test
    void flagsBuildFilesOutsideRootTree(@TempDir Path tempRoot) throws Exception {
        Path projectRoot = tempRoot.resolve("proj");
        Path externalDir = tempRoot.resolve("external");

        File externalBuild = write(externalDir, "build.xml", "<project name=\"ext\"/>");
        File root = write(
                projectRoot,
                "build.xml",
                "<project name=\"root\"><target name=\"t\"><ant antfile=\"../external/build.xml\"/></target></project>");

        BuildFileScanner.ScanResult scan = new BuildFileScanner().crawl(root);

        assertThat(scan.getOutsideRootTree()).containsExactly(externalBuild.getCanonicalFile());
    }

    @Test
    void crawlsWithPassedProperties(@TempDir Path dir) throws Exception {
        File root = write(
                dir,
                "build.xml",
                "<project name=\"root\"><target name=\"t\"><ant antfile=\"${target.mod}/build.xml\"/></target></project>");
        Path targetMod = dir.resolve("mod1");
        File modBuild = write(targetMod, "build.xml", "<project name=\"mod1\"/>");

        Properties props = new Properties();
        props.setProperty("target.mod", "mod1");

        BuildFileScanner.ScanResult scan = new BuildFileScanner().crawl(root, props);

        assertThat(scan.getPeeks()).hasSize(2);
        assertThat(scan.getPeeks().get(1).getBuildFile()).isEqualTo(modBuild.getCanonicalFile());
    }
}
