package com.brewshot;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Minimal JSON reader/escaper — just enough to speak the Chrome DevTools
 * Protocol without adding a dependency, and public because eval() hands back
 * Map/List trees that {@link #get} digs into with dotted paths. Parses a JSON
 * document into plain Java values:
 * {@code Map<String,Object>}, {@code List<Object>}, {@code String},
 * {@code Double}, {@code Boolean}, {@code null}.
 *
 * <p>Not a general-purpose parser: no streaming, whole-string input, doubles
 * only for numbers. CDP messages fit comfortably.
 */
public final class MiniJson {

    /**
     * Recursion cap — defence in depth. CDP messages are shallow; a pathological
     * page returning a deeply-nested eval value cannot StackOverflow the harness
     * (it fails the one eval call with a clear error instead). See SECURITY.md.
     */
    private static final int MAX_DEPTH = 200;
    private static final Pattern JSON_NUMBER = Pattern.compile(
        "-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");

    private final String s;
    private int i;
    private int depth;

    private MiniJson(String s) { this.s = s; }

    public static Object parse(String json) {
        MiniJson p = new MiniJson(json);
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw new IllegalArgumentException("trailing JSON at " + p.i);
        }
        return v;
    }

    /** Escape a string for embedding inside a JSON request we build by hand. */
    public static String esc(String raw) {
        StringBuilder b = new StringBuilder(raw.length() + 8);
        for (int k = 0; k < raw.length(); k++) {
            char c = raw.charAt(k);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    // U+2028/U+2029 are valid JSON but historically terminate
                    // JavaScript source string literals. Escaping them keeps this
                    // one escaper safe for both CDP JSON and embedded JS literals.
                    // Escape every UTF-16 surrogate code unit, including valid
                    // pairs. Raw lone surrogates cannot be represented in UTF-8
                    // and otherwise get rejected or replaced during file IO.
                    if (c < 0x20 || c == '\u2028' || c == '\u2029'
                            || Character.isSurrogate(c)) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }

    /**
     * Serialize the JSON value domain without dependencies. Supported values
     * are null, booleans, finite numbers, strings, maps with string keys, lists,
     * and Java arrays (including primitive arrays). Unsupported values,
     * non-finite numbers, excessive nesting, and cycles fail loud.
     */
    public static String stringify(Object value) {
        return stringify(value, false);
    }

    /** Pretty form used for human-readable sidecars without a second serializer. */
    static String stringifyPretty(Object value) {
        return stringify(value, true);
    }

    private static String stringify(Object value, boolean pretty) {
        StringBuilder out = new StringBuilder(256);
        appendJson(out, value, pretty, 0, new IdentityHashMap<>());
        return out.toString();
    }

    private static void appendJson(StringBuilder out, Object value, boolean pretty,
                                   int depth, IdentityHashMap<Object, Boolean> active) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("JSON nested deeper than " + MAX_DEPTH);
        }
        if (value == null) {
            out.append("null");
        } else if (value instanceof String string) {
            out.append('"').append(esc(string)).append('"');
        } else if (value instanceof Boolean bool) {
            out.append(bool);
        } else if (value instanceof Number number) {
            appendNumber(out, number);
        } else if (value instanceof Map<?, ?> map) {
            enterContainer(value, active);
            try {
                appendMap(out, map, pretty, depth, active);
            } finally {
                active.remove(value);
            }
        } else if (value instanceof List<?> list) {
            enterContainer(value, active);
            try {
                appendList(out, list, pretty, depth, active);
            } finally {
                active.remove(value);
            }
        } else if (value.getClass().isArray()) {
            enterContainer(value, active);
            try {
                appendArray(out, value, pretty, depth, active);
            } finally {
                active.remove(value);
            }
        } else {
            throw new IllegalArgumentException(
                "unsupported JSON value type: " + value.getClass().getName());
        }
    }

    private static void appendNumber(StringBuilder out, Number number) {
        if (number instanceof Double d && !Double.isFinite(d)
                || number instanceof Float f && !Float.isFinite(f)) {
            throw new IllegalArgumentException("JSON numbers must be finite, got: " + number);
        }
        String encoded = number.toString();
        if (!JSON_NUMBER.matcher(encoded).matches()) {
            throw new IllegalArgumentException("unsupported JSON number: " + encoded);
        }
        out.append(encoded);
    }

    private static void appendMap(StringBuilder out, Map<?, ?> map, boolean pretty,
                                  int depth, IdentityHashMap<Object, Boolean> active) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("JSON object keys must be strings, got: "
                    + entry.getKey());
            }
            if (!first) {
                out.append(',');
            }
            if (pretty) {
                out.append('\n');
                indent(out, depth + 1);
            }
            first = false;
            out.append('"').append(esc(key)).append('"').append(pretty ? ": " : ":");
            appendJson(out, entry.getValue(), pretty, depth + 1, active);
        }
        if (pretty && !map.isEmpty()) {
            out.append('\n');
            indent(out, depth);
        }
        out.append('}');
    }

    private static void appendList(StringBuilder out, List<?> list, boolean pretty,
                                   int depth, IdentityHashMap<Object, Boolean> active) {
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            if (pretty) {
                out.append('\n');
                indent(out, depth + 1);
            }
            appendJson(out, list.get(i), pretty, depth + 1, active);
        }
        if (pretty && !list.isEmpty()) {
            out.append('\n');
            indent(out, depth);
        }
        out.append(']');
    }

    private static void appendArray(StringBuilder out, Object array, boolean pretty,
                                    int depth, IdentityHashMap<Object, Boolean> active) {
        out.append('[');
        int length = Array.getLength(array);
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                out.append(',');
            }
            if (pretty) {
                out.append('\n');
                indent(out, depth + 1);
            }
            appendJson(out, Array.get(array, i), pretty, depth + 1, active);
        }
        if (pretty && length > 0) {
            out.append('\n');
            indent(out, depth);
        }
        out.append(']');
    }

    private static void enterContainer(Object container,
                                       IdentityHashMap<Object, Boolean> active) {
        if (active.put(container, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic value is not valid JSON");
        }
    }

    private static void indent(StringBuilder out, int depth) {
        out.append("  ".repeat(depth));
    }

    /** Dotted-path lookup into a parsed tree; null when any hop is missing. */
    @SuppressWarnings("unchecked")
    public static Object get(Object tree, String dottedPath) {
        Object cur = tree;
        for (String hop : dottedPath.split("\\.")) {
            if (!(cur instanceof Map)) { return null; }
            cur = ((Map<String, Object>) cur).get(hop);
        }
        return cur;
    }

    // ---- parsing ----------------------------------------------------------

    private Object value() {
        ws();
        char c = peek();
        if ((c == '{' || c == '[') && ++depth > MAX_DEPTH) {
            throw err("JSON nested deeper than " + MAX_DEPTH);
        }
        Object v = switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> lit("true", Boolean.TRUE);
            case 'f' -> lit("false", Boolean.FALSE);
            case 'n' -> lit("null", null);
            default -> number();
        };
        if (c == '{' || c == '[') { depth--; }
        return v;
    }

    private Map<String, Object> object() {
        expect('{');
        Map<String, Object> m = new LinkedHashMap<>();
        ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            String key = string();
            ws();
            expect(':');
            m.put(key, value());
            ws();
            char c = next();
            if (c == '}') { return m; }
            if (c != ',') { throw err("expected , or }"); }
        }
    }

    private List<Object> array() {
        expect('[');
        List<Object> l = new ArrayList<>();
        ws();
        if (peek() == ']') { i++; return l; }
        while (true) {
            l.add(value());
            ws();
            char c = next();
            if (c == ']') { return l; }
            if (c != ',') { throw err("expected , or ]"); }
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') { return b.toString(); }
            if (c != '\\') { b.append(c); continue; }
            char e = next();
            switch (e) {
                case '"' -> b.append('"');
                case '\\' -> b.append('\\');
                case '/' -> b.append('/');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) { throw err("truncated \\u escape"); }
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException nf) {
                        throw err("bad \\u escape");
                    }
                    i += 4;
                }
                default -> throw err("bad escape \\" + e);
            }
        }
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) { i++; }
        if (start == i) { throw err("expected value"); }
        Double value = Double.parseDouble(s.substring(start, i));
        if (!Double.isFinite(value)) {
            throw err("JSON number is not finite");
        }
        return value;
    }

    private Object lit(String word, Object v) {
        if (!s.startsWith(word, i)) { throw err("expected " + word); }
        i += word.length();
        return v;
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) { i++; }
    }

    private char peek() {
        if (i >= s.length()) { throw err("unexpected end"); }
        return s.charAt(i);
    }

    private char next() {
        char c = peek();
        i++;
        return c;
    }

    private void expect(char c) {
        if (next() != c) { throw err("expected " + c); }
    }

    private IllegalArgumentException err(String why) {
        return new IllegalArgumentException(why + " at offset " + i);
    }
}
