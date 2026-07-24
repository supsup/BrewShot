package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The CLI manifest preserves eval's JSON types instead of stringifying them. */
class MainManifestTest {

    @Test
    @SuppressWarnings("unchecked")
    void topLevelEvalScalarsListsAndNullKeepTheirJsonTypes(@TempDir Path directory)
            throws Exception {
        Path out = directory.resolve("shot.png");
        Files.write(out, new byte[] {1, 2, 3});
        List<Object> values = java.util.Arrays.asList(
            null, true, 42.5, "line\n\"quoted\"\u2028", List.of(false, 7.0, "x"));

        for (int i = 0; i < values.size(); i++) {
            Path manifest = directory.resolve("typed-" + i + ".json");
            Main.writeManifest(manifest, "page.html", "file", 640, 480, 25,
                null, out, values.get(i), null, true, 12, null, null);
            Map<String, Object> root =
                (Map<String, Object>) MiniJson.parse(Files.readString(manifest));
            assertEquals(values.get(i), root.get("eval"), "top-level eval value " + i);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void evalValueRoundTripsAcrossTheWholeSupportedJsonDomain(@TempDir Path directory)
            throws Exception {
        Path out = directory.resolve("shot.png");
        Files.write(out, new byte[] {1, 2, 3});
        Path manifest = directory.resolve("shot.json");

        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("nil", null);
        nested.put("bool", true);
        nested.put("number", 42.5);
        nested.put("string", "quoted \" value");
        nested.put("array", new ArrayList<>(java.util.Arrays.asList(
            null, false, 7.0, "line\nbreak", List.of("nested"))));
        nested.put("map", Map.of("answer", 42.0));

        Main.writeManifest(manifest, "page.html", "file", 640, 480, 25,
            null, out, nested, null, true, 12, null, null);

        Map<String, Object> root =
            (Map<String, Object>) MiniJson.parse(Files.readString(manifest));
        Map<String, Object> eval = (Map<String, Object>) root.get("eval");
        assertNull(eval.get("nil"));
        assertEquals(Boolean.TRUE, eval.get("bool"));
        assertEquals(42.5, eval.get("number"));
        assertEquals("quoted \" value", eval.get("string"));
        List<Object> array = (List<Object>) eval.get("array");
        assertNull(array.get(0));
        assertEquals(Boolean.FALSE, array.get(1));
        assertEquals(7.0, array.get(2));
        assertEquals("line\nbreak", array.get(3));
        assertTrue(eval.get("map") instanceof Map);
        assertFalse(root.get("eval") instanceof String,
            "eval must remain a JSON object, never String.valueOf(object)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void gifManifestDisclosesRequestedAndEffectiveDelay(@TempDir Path directory)
            throws Exception {
        Path out = directory.resolve("shot.gif");
        Files.write(out, new byte[] {'G', 'I', 'F'});
        Path manifest = directory.resolve("shot.json");

        Main.writeManifest(manifest, "page.html", "file", 640, 480, 25,
            null, out, null, null, true, 12, 75, null);

        Map<String, Object> root =
            (Map<String, Object>) MiniJson.parse(Files.readString(manifest));
        assertEquals(75.0, root.get("gifDelayMsRequested"));
        assertEquals(80.0, root.get("gifDelayMsEncoded"));
    }

    @Test
    void unsupportedEvalValueFailsBeforeReplacingAnExistingManifest(
            @TempDir Path directory) throws Exception {
        Path out = directory.resolve("shot.png");
        Files.write(out, new byte[] {1, 2, 3});
        Path manifest = directory.resolve("shot.json");
        Files.writeString(manifest, "known-good-old-manifest");

        assertThrows(IllegalArgumentException.class,
            () -> Main.writeManifest(manifest, "page.html", "file", 640, 480, 25,
                null, out, new Object(), null, true, 12, null, null));
        assertEquals("known-good-old-manifest", Files.readString(manifest));
    }
}
