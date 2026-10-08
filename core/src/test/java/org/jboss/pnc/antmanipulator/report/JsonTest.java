package org.jboss.pnc.antmanipulator.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the hand-rolled {@link Json} serializer. String escaping is the part most easily got
 * wrong, so it gets the most attention; the structural tests pin the exact pretty-printed layout so a
 * later refactor can't silently change the report format.
 */
class JsonTest {

    @Nested
    class Scalars {

        @Test
        void writesNull() {
            assertThat(Json.write(null)).isEqualTo("null\n");
        }

        @Test
        void writesString() {
            assertThat(Json.write("hello")).isEqualTo("\"hello\"\n");
        }

        @Test
        void writesBoolean() {
            assertThat(Json.write(true)).isEqualTo("true\n");
            assertThat(Json.write(false)).isEqualTo("false\n");
        }

        @Test
        void writesIntegerAndDouble() {
            assertThat(Json.write(42)).isEqualTo("42\n");
            assertThat(Json.write(3.5)).isEqualTo("3.5\n");
        }
    }

    @Nested
    class StringEscaping {

        @Test
        void escapesQuotesAndBackslash() {
            assertThat(Json.write("a\"b\\c")).isEqualTo("\"a\\\"b\\\\c\"\n");
        }

        @Test
        void escapesWhitespaceControlChars() {
            assertThat(Json.write("line1\nline2\tend\r")).isEqualTo("\"line1\\nline2\\tend\\r\"\n");
        }

        @Test
        void escapesBackspaceAndFormFeed() {
            assertThat(Json.write("\b\f")).isEqualTo("\"\\b\\f\"\n");
        }

        @Test
        void escapesLowControlCharsAsUnicode() {
            // 0x01 has no short escape, so it must become \u0001.
            assertThat(Json.write("\u0001")).isEqualTo("\"\\u0001\"\n");
        }

        @Test
        void leavesPrintableUnicodeUntouched() {
            assertThat(Json.write("caf\u00e9")).isEqualTo("\"caf\u00e9\"\n");
        }
    }

    @Nested
    class Structures {

        @Test
        void writesEmptyObject() {
            assertThat(Json.write(new LinkedHashMap<String, Object>())).isEqualTo("{}\n");
        }

        @Test
        void writesEmptyArray() {
            assertThat(Json.write(new ArrayList<>())).isEqualTo("[]\n");
        }

        @Test
        void writesFlatObjectWithIndentationAndCommas() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("a", "x");
            m.put("b", 1);

            assertThat(Json.write(m)).isEqualTo("{\n  \"a\": \"x\",\n  \"b\": 1\n}\n");
        }

        @Test
        void writesArrayOfScalars() {
            List<Object> list = new ArrayList<>();
            list.add("x");
            list.add(2);

            assertThat(Json.write(list)).isEqualTo("[\n  \"x\",\n  2\n]\n");
        }

        @Test
        void nestsObjectsAndArraysWithGrowingIndent() {
            Map<String, Object> inner = new LinkedHashMap<>();
            inner.put("k", "v");
            List<Object> arr = new ArrayList<>();
            arr.add(inner);
            Map<String, Object> outer = new LinkedHashMap<>();
            outer.put("items", arr);

            assertThat(Json.write(outer))
                    .isEqualTo("{\n  \"items\": [\n    {\n      \"k\": \"v\"\n    }\n  ]\n}\n");
        }
    }

    @Nested
    class Rejection {

        @Test
        void rejectsUnsupportedValueType() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bad", new Object());

            assertThatThrownBy(() -> Json.write(m))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported JSON value type");
        }
    }
}
