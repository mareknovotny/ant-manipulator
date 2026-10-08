package org.jboss.pnc.antmanipulator.report;

import java.util.List;
import java.util.Map;

/**
 * A minimal, dependency-free JSON serializer for the fixed report schema, which contains only maps,
 * lists, strings, numbers, booleans and nulls. Emitting a machine-readable report shouldn't drag a
 * JSON library into the shaded CLI, and the schema is small and fully under our control — so we write
 * it directly, taking care over string escaping (the one part that is easy to get wrong).
 *
 * <p>
 * Not a general-purpose library: {@link #write(Object)} handles exactly the value types the report
 * builder produces and rejects anything else, so an accidental unsupported value fails loudly rather
 * than silently emitting invalid JSON.
 */
public final class Json {

    private Json() {
    }

    /** Serialize a value tree to pretty-printed JSON. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 0);
        sb.append('\n');
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object value, int indent) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value);
        } else if (value instanceof Map) {
            writeObject(sb, (Map<String, Object>) value, indent);
        } else if (value instanceof List) {
            writeArray(sb, (List<Object>) value, indent);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported JSON value type: " + value.getClass().getName());
        }
    }

    private static void writeObject(StringBuilder sb, Map<String, Object> map, int indent) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            indent(sb, indent + 1);
            writeString(sb, e.getKey());
            sb.append(": ");
            writeValue(sb, e.getValue(), indent + 1);
            if (++i < map.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<Object> list, int indent) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            indent(sb, indent + 1);
            writeValue(sb, list.get(i), indent + 1);
            if (i < list.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static void indent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) {
            sb.append("  ");
        }
    }
}
