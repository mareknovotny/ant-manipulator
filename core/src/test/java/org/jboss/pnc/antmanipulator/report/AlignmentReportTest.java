package org.jboss.pnc.antmanipulator.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jboss.pnc.antmanipulator.gav.GavCorrelator;
import org.jboss.pnc.antmanipulator.gav.ResolvedGav;
import org.jboss.pnc.antmanipulator.gav.ResolvedGav.Confidence;
import org.jboss.pnc.antmanipulator.gav.ResolvedGav.SourceKind;
import org.jboss.pnc.antmanipulator.gav.VersionReconciler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link AlignmentReport#toJson()}: now serialised with Jackson rather than the old
 * hand-rolled {@code Json} class, so these pin the exact pretty-printed layout (2-space indent,
 * {@code "key": value}, trailing newline) and string escaping that downstream consumers rely on.
 */
class AlignmentReportTest {

    @TempDir
    File tempDir;

    @Test
    void producesTwoSpaceIndentWithColonSpaceAndTrailingNewline() {
        File buildFile = new File(tempDir, "build.xml");
        GavCorrelator.Result correlated = new GavCorrelator().correlate(List.of());
        VersionReconciler reconciler = VersionReconciler.forTree(tempDir, buildFile);

        String json = AlignmentReport.build(buildFile, List.of(), correlated, reconciler).toJson();

        assertThat(json).startsWith("{\n  \"rootBuildFile\": ");
        assertThat(json).doesNotContain(" : "); // no space before the colon, unlike Jackson's default printer
        assertThat(json).endsWith("}\n");
        assertThat(json).contains("\"artifacts\": [],\n").contains("\"projectVersionCandidates\": []\n");
    }

    @Test
    void escapesSpecialCharactersInFindingDetail() {
        File buildFile = new File(tempDir, "build.xml");
        ResolvedGav finding = new ResolvedGav(
                "org.acme",
                "widget",
                "1.0",
                buildFile,
                "line with \"quotes\", a\\backslash and a\ttab",
                SourceKind.POM_TEMPLATE,
                Confidence.HIGH);
        GavCorrelator.Result correlated = new GavCorrelator().correlate(List.of(finding));
        VersionReconciler reconciler = VersionReconciler.forTree(tempDir, buildFile);

        String json = AlignmentReport.build(buildFile, List.of(finding), correlated, reconciler)
                .toJson();

        assertThat(json).contains("\"detail\": \"line with \\\"quotes\\\", a\\\\backslash and a\\ttab\"");
    }

    @Test
    void acceptsTheSupportedValueTypes() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("str", "x");
        tree.put("num", 1);
        tree.put("bool", true);
        tree.put("nil", null);
        tree.put("list", Arrays.asList("a", 2, false, null));
        tree.put("nested", Map.of("k", List.of("v")));

        assertThatCode(() -> AlignmentReport.assertSupported(tree)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAnUnsupportedValueTypeNestedInTheTree() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("findings", List.of(Map.of("bad", new Object())));

        assertThatThrownBy(() -> AlignmentReport.assertSupported(tree))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported JSON value type");
    }
}
