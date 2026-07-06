package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
    void dottedGetReturnsNullOnMissingHops() {
        Object m = MiniJson.parse("{\"a\":{\"b\":1}}");
        assertEquals(1.0, MiniJson.get(m, "a.b"));
        assertNull(MiniJson.get(m, "a.zzz.deep"));
        assertNull(MiniJson.get(Map.of(), "anything"));
    }
}
