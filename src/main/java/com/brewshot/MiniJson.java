package com.brewshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private final String s;
    private int i;

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
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
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
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> lit("true", Boolean.TRUE);
            case 'f' -> lit("false", Boolean.FALSE);
            case 'n' -> lit("null", null);
            default -> number();
        };
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
        return Double.parseDouble(s.substring(start, i));
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
