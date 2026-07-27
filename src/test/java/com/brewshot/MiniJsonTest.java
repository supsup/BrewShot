package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MiniJsonTest {

    @Test
    void parsesTheCdpMessageShapes() {
        Object m = MiniJson.parse(
            "{\"id\":3,\"result\":{\"result\":{\"type\":\"string\",\"value\":\"ok\"}}}");
        assertEquals(3.0, MiniJson.get(m, "id"));
        assertEquals("ok", MiniJson.get(m, "result.result.value"));
    }

    @Test
    void parsesArraysBooleansNullsAndEscapes() {
        Object v = MiniJson.parse(
            "[true, false, null, 1.5e2, \"a\\\"b\\\\c\\n\\u0041\"]");
        assertEquals(List.of(true, false), ((List<?>) v).subList(0, 2));
        assertNull(((List<?>) v).get(2));
        assertEquals(150.0, ((List<?>) v).get(3));
        assertEquals("a\"b\\c\nA", ((List<?>) v).get(4));
    }

    @Test
    void escapeRoundTripsThroughParse() {
        String hostile = "line1\nline2\t\"quoted\" \\ backslash <html attr=\"x\">";
        Object back = MiniJson.parse("{\"v\":\"" + MiniJson.esc(hostile) + "\"}");
        assertEquals(hostile, MiniJson.get(back, "v"));
    }

    @Test
    void everySurrogateCodeUnitEscapesAndRoundTripsThroughUtf8File(
            @TempDir Path directory) throws Exception {
        String value = "pair:\uD83D\uDE03|lone-high:\uD800|lone-low:\uDC00";
        String json = MiniJson.stringify(Map.of("value", value));

        assertTrue(json.contains("\\ud83d\\ude03"), json);
        assertTrue(json.contains("\\ud800"), json);
        assertTrue(json.contains("\\udc00"), json);
        assertTrue(json.chars().noneMatch(codeUnit ->
                Character.isSurrogate((char) codeUnit)),
            "serialized JSON must contain no raw UTF-16 surrogate code units");

        Path file = directory.resolve("surrogates.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        Object parsed = MiniJson.parse(Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(value, MiniJson.get(parsed, "value"));
    }

    @Test
    void malformedInputsFailTheDocumentedWay() {
        for (String bad : new String[] {
            "{\"a\":",              // truncated object
            "[1,2",                 // unclosed array
            "\"\\u00\"",            // truncated unicode escape
            "\"\\uZZZZ\"",          // non-hex unicode escape
            "{\"a\":1} trailing",   // trailing garbage
            "",                     // empty
        }) {
            org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> MiniJson.parse(bad),
                "should throw IllegalArgumentException: " + bad);
        }
    }

    @Test
    void deeplyNestedInputFailsCleanlyNotWithStackOverflow() {
        StringBuilder deep = new StringBuilder();
        for (int k = 0; k < 5000; k++) { deep.append('['); }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> MiniJson.parse(deep.toString()),
            "5000-deep array must fail with a clear error, not StackOverflowError");
    }

    @Test
    void dottedGetReturnsNullOnMissingHops() {
        Object m = MiniJson.parse("{\"a\":{\"b\":1}}");
        assertEquals(1.0, MiniJson.get(m, "a.b"));
        assertNull(MiniJson.get(m, "a.zzz.deep"));
        assertNull(MiniJson.get(Map.of(), "anything"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void serializerRoundTripsJsonValuesIncludingJavaArrays() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("nil", null);
        value.put("bool", true);
        value.put("number", 12.5);
        value.put("text", "line\n\"quoted\"\u2028");
        value.put("list", java.util.Arrays.asList(null, false, 3, "x"));
        value.put("array", new int[] {1, 2, 3});
        value.put("map", Map.of("nested", "yes"));

        Map<String, Object> back =
            (Map<String, Object>) MiniJson.parse(MiniJson.stringify(value));
        assertNull(back.get("nil"));
        assertEquals(Boolean.TRUE, back.get("bool"));
        assertEquals(12.5, back.get("number"));
        assertEquals("line\n\"quoted\"\u2028", back.get("text"));
        assertEquals(List.of(1.0, 2.0, 3.0), back.get("array"));
        assertEquals("yes", MiniJson.get(back, "map.nested"));
    }

    @Test
    void serializerRejectsUnsupportedNonFiniteAndCyclicValues() {
        assertThrows(IllegalArgumentException.class, () -> MiniJson.stringify(Double.NaN));
        assertThrows(IllegalArgumentException.class,
            () -> MiniJson.stringify(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.stringify(new Object()));
        assertThrows(IllegalArgumentException.class,
            () -> MiniJson.stringify(Map.of(1, "non-string key")));

        List<Object> cycle = new ArrayList<>();
        cycle.add(cycle);
        assertThrows(IllegalArgumentException.class, () -> MiniJson.stringify(cycle));
    }

    @Test
    void parserRejectsNumbersThatOverflowToInfinity() {
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("1e309"));
    }
}
