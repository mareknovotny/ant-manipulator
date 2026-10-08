package org.jboss.pnc.antmanipulator.gav;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link AntPropertyResolver}, which delegates {@code ${...}} expansion to Ant's own
 * property engine. Fixtures are minimal {@code build.xml} files under a {@link TempDir}; the key
 * behaviours are that defined properties expand (including chained ones), unknown tokens and plain
 * strings pass through untouched, and an unparseable build degrades gracefully rather than throwing.
 */
class AntPropertyResolverTest {

    private static File build(Path dir, String content) throws IOException {
        File f = dir.resolve("build.xml").toFile();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    @Test
    void expandsDefinedProperty(@TempDir Path dir) throws IOException {
        File b = build(dir, "<project name=\"p\"><property name=\"impl.version\" value=\"1.9.1\"/></project>");

        AntPropertyResolver r = AntPropertyResolver.forRoot(b);

        assertThat(r.isFullyLoaded()).isTrue();
        assertThat(r.getProperty("impl.version")).isEqualTo("1.9.1");
        assertThat(r.expand("${impl.version}")).isEqualTo("1.9.1");
        assertThat(r.expand("v${impl.version}-final")).isEqualTo("v1.9.1-final");
    }

    @Test
    void expandsChainedProperties(@TempDir Path dir) throws IOException {
        File b = build(
                dir,
                "<project name=\"p\">\n"
                        + "  <property name=\"major\" value=\"1\"/>\n"
                        + "  <property name=\"full\" value=\"${major}.2.3\"/>\n"
                        + "</project>");

        AntPropertyResolver r = AntPropertyResolver.forRoot(b);

        assertThat(r.expand("${full}")).isEqualTo("1.2.3");
    }

    @Test
    void leavesUnknownTokenUntouched(@TempDir Path dir) throws IOException {
        File b = build(dir, "<project name=\"p\"/>");

        AntPropertyResolver r = AntPropertyResolver.forRoot(b);

        assertThat(r.expand("${nowhere.defined}")).isEqualTo("${nowhere.defined}");
        assertThat(r.getProperty("nowhere.defined")).isNull();
    }

    @Test
    void nullAndPlainStringsPassThrough(@TempDir Path dir) throws IOException {
        File b = build(dir, "<project name=\"p\"/>");

        AntPropertyResolver r = AntPropertyResolver.forRoot(b);

        assertThat(r.expand(null)).isNull();
        assertThat(r.expand("1.2.3")).isEqualTo("1.2.3");
    }

    @Test
    void degradesGracefullyOnUnparseableBuild(@TempDir Path dir) throws IOException {
        // Missing closing tag — configureProject fails, so we fall back to an empty project.
        File b = build(dir, "<project name=\"p\"><property name=\"x\" value=\"1\"/>");

        AntPropertyResolver r = AntPropertyResolver.forRoot(b);

        assertThat(r.isFullyLoaded()).isFalse();
        assertThat(r.expand("${x}")).isEqualTo("${x}"); // unknown token returned unchanged
    }
}
