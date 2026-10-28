package org.jboss.pnc.antmanipulator.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.jboss.pnc.antmanipulator.cli.RawBuildFilePeek.Kind;
import org.jboss.pnc.antmanipulator.cli.RawBuildFilePeek.Reference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RawBuildFilePeekTest {

    private static File write(Path dir, String name, String content) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        return p.toFile();
    }

    @Test
    void peeksProjectAttributes(@TempDir Path dir) throws Exception {
        File build = write(dir, "build.xml", "<project name=\"my-proj\" default=\"compile\" basedir=\".\"/>");

        RawBuildFilePeek peek = RawBuildFilePeek.peek(build);

        assertThat(peek.getProjectName()).isEqualTo("my-proj");
        assertThat(peek.getDefaultTarget()).isEqualTo("compile");
        assertThat(peek.getDeclaredBasedir()).isEqualTo(".");
        assertThat(peek.getBuildFile()).isEqualTo(build.getCanonicalFile());
    }

    @Test
    void peeksImportAndInclude(@TempDir Path dir) throws Exception {
        File imp = write(dir, "imported.xml", "<project/>");
        File inc = write(dir, "included.xml", "<project/>");
        File build = write(
                dir,
                "build.xml",
                "<project><import file=\"imported.xml\"/><include file=\"included.xml\"/></project>");

        RawBuildFilePeek peek = RawBuildFilePeek.peek(build);

        assertThat(peek.getReferences()).hasSize(2);
        Reference r1 = peek.getReferences().get(0);
        assertThat(r1.getKind()).isEqualTo(Kind.IMPORT);
        assertThat(r1.getResolved()).isEqualTo(imp.getCanonicalFile());

        Reference r2 = peek.getReferences().get(1);
        assertThat(r2.getKind()).isEqualTo(Kind.INCLUDE);
        assertThat(r2.getResolved()).isEqualTo(inc.getCanonicalFile());
    }

    @Test
    void peeksAntTasks(@TempDir Path dir) throws Exception {
        Path sub = dir.resolve("sub");
        File subBuild = write(sub, "build.xml", "<project/>");
        File customBuild = write(sub, "custom.xml", "<project/>");

        File build = write(
                dir,
                "build.xml",
                "<project><target name=\"t\">"
                        + "<ant dir=\"sub\"/>"
                        + "<ant dir=\"sub\" antfile=\"custom.xml\"/>"
                        + "<ant antfile=\"sub/build.xml\"/>"
                        + "</target></project>");

        RawBuildFilePeek peek = RawBuildFilePeek.peek(build);

        assertThat(peek.getReferences()).hasSize(3);
        assertThat(peek.getReferences().get(0).getKind()).isEqualTo(Kind.ANT);
        assertThat(peek.getReferences().get(0).getResolved()).isEqualTo(subBuild.getCanonicalFile());

        assertThat(peek.getReferences().get(1).getKind()).isEqualTo(Kind.ANT);
        assertThat(peek.getReferences().get(1).getResolved()).isEqualTo(customBuild.getCanonicalFile());

        assertThat(peek.getReferences().get(2).getKind()).isEqualTo(Kind.ANT);
        assertThat(peek.getReferences().get(2).getResolved()).isEqualTo(subBuild.getCanonicalFile());
    }

    @Test
    void peeksSubantWithFilesetAndDirset(@TempDir Path dir) throws Exception {
        Path modA = dir.resolve("modA");
        File buildA = write(modA, "build.xml", "<project/>");
        Path modB = dir.resolve("modB");
        File buildB = write(modB, "build.xml", "<project/>");

        File build = write(
                dir,
                "build.xml",
                "<project><target name=\"t\">"
                        + "<subant><fileset dir=\".\" includes=\"modA/build.xml\"/></subant>"
                        + "<subant antfile=\"build.xml\"><dirset dir=\".\" includes=\"modB\"/></subant>"
                        + "</target></project>");

        RawBuildFilePeek peek = RawBuildFilePeek.peek(build);

        assertThat(peek.getReferences()).hasSize(2);
        assertThat(peek.getReferences().get(0).getKind()).isEqualTo(Kind.SUBANT);
        assertThat(peek.getReferences().get(0).getResolved()).isEqualTo(buildA.getCanonicalFile());

        assertThat(peek.getReferences().get(1).getKind()).isEqualTo(Kind.SUBANT);
        assertThat(peek.getReferences().get(1).getResolved()).isEqualTo(buildB.getCanonicalFile());
    }

    @Test
    void peeksPropertiesAndExpandsInPaths(@TempDir Path dir) throws Exception {
        Path core = dir.resolve("core");
        File coreBuild = write(core, "build.xml", "<project/>");

        File build = write(
                dir,
                "build.xml",
                "<project><property name=\"sub\" value=\"core\"/>"
                        + "<target name=\"t\"><ant antfile=\"${sub}/build.xml\"/></target></project>");

        RawBuildFilePeek peek = RawBuildFilePeek.peek(build);

        assertThat(peek.getReferences()).hasSize(1);
        Reference ref = peek.getReferences().get(0);
        assertThat(ref.getKind()).isEqualTo(Kind.ANT);
        assertThat(ref.getResolved()).isEqualTo(coreBuild.getCanonicalFile());
    }

    @Test
    void peeksWithInitialPropertiesPassedIn(@TempDir Path dir) throws Exception {
        Path core = dir.resolve("core");
        File coreBuild = write(core, "build.xml", "<project/>");

        File build = write(
                dir,
                "build.xml",
                "<project><target name=\"t\"><ant antfile=\"${passed.dir}/build.xml\"/></target></project>");

        RawBuildFilePeek peek = RawBuildFilePeek.peek(build, Map.of("passed.dir", "core"));

        assertThat(peek.getReferences()).hasSize(1);
        Reference ref = peek.getReferences().get(0);
        assertThat(ref.getResolved()).isEqualTo(coreBuild.getCanonicalFile());
    }

    @Test
    void recordsUnresolvedPropertiesAsNullTarget(@TempDir Path dir) throws Exception {
        File build = write(
                dir,
                "build.xml",
                "<project><target name=\"t\"><ant antfile=\"${unknown}/build.xml\"/></target></project>");

        RawBuildFilePeek peek = RawBuildFilePeek.peek(build);

        assertThat(peek.getReferences()).hasSize(1);
        Reference ref = peek.getReferences().get(0);
        assertThat(ref.getResolved()).isNull();
        assertThat(ref.getRawPath()).isEqualTo("${unknown}/build.xml");
    }
}
