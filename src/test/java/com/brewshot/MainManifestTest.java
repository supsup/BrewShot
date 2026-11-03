package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

/** The CLI manifest preserves eval's JSON types instead of stringifying them. */
class MainManifestTest {

    /** Case 1: equal byte counts are not byte identity; both old receipts really are equal. */
    @Test
    void equalLengthProducerSnapshotsKeepLegacyBytesButHaveDifferentOptInDigests(
            @TempDir Path directory) throws Exception {
        Path out = directory.resolve("capture.png");
        Path manifest = directory.resolve("capture.json");
        byte[] first = "equal-length-A".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] second = "equal-length-B".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals(first.length, second.length, "INSTRUMENT: equal-length discriminator");
        var firstReceipt = ArtifactWriter.writeBytesWithReceipt(out, first, true);
        writeLegacy(manifest, out);
        byte[] firstLegacy = Files.readAllBytes(manifest);
        var secondReceipt = ArtifactWriter.writeBytesWithReceipt(out, second, true);
        writeLegacy(manifest, out);
        org.junit.jupiter.api.Assertions.assertArrayEquals(firstLegacy, Files.readAllBytes(manifest));
        var oldKeys = manifestObject(manifest).keySet();

        // The first receipt is deliberately published AFTER the second bytes replace the path.
        // Reopening the file would give the wrong digest even though its byte count is equal.
        writeProducerManifest(manifest, out, firstReceipt);
        Map<String, Object> a = manifestObject(manifest);
        writeProducerManifest(manifest, out, secondReceipt);
        Map<String, Object> b = manifestObject(manifest);
        assertEquals(ArtifactWriterTest.receiptHash(first), a.get("outSha256"));
        assertEquals(ArtifactWriterTest.receiptHash(second), b.get("outSha256"));
        assertNotEquals(a.get("outSha256"), b.get("outSha256"), "kills a constant/reused digest");
        assertTrue(String.valueOf(a.get("outSha256")).matches("[0-9a-f]{64}"));
        java.util.Set<String> expectedKeys = new java.util.HashSet<>(oldKeys);
        expectedKeys.addAll(java.util.Set.of("outSha256", "outSha256Provenance"));
        assertEquals(expectedKeys, a.keySet(), "exactly two added fields; outBytes already existed");
        assertEquals(expectedKeys, b.keySet());
        assertEquals(first.length, ((Number) a.get("outBytes")).longValue());
    }

    /** Case 5: an independent byte golden pins every existing overload, including field order. */
    @Test
    void noReceiptPreservesTheExactLegacySidecarThroughEveryOverload(@TempDir Path directory)
            throws Exception {
        Path out = directory.resolve("capture.png");
        Files.write(out, new byte[] {1, 2, 3});
        String golden = """
            {
              "input": "page.html",
              "mode": "file",
              "viewport": "640x480",
              "settleMs": 25,
              "waitJs": null,
              "out": $OUT,
              "outBytes": 3,
              "eval": null,
              "failJs": null,
              "failJsPassed": true,
              "elapsedMs": 12,
              "brewshot": "0.9.0"
            }
            """.replace("$OUT", MiniJson.stringify(out.toString()));
        List<LegacyManifestCall> calls = List.of(
            m -> Main.writeManifest(m, "page.html", "file", 640, 480, 25,
                null, out, null, null, true, 12, null, null),
            m -> Main.writeManifest(m, "page.html", "file", 640, 480, 25,
                null, out, null, null, true, 12, null, null, null),
            m -> Main.writeManifest(m, "page.html", "file", 640, 480, 25,
                null, out, null, null, true, 12, null, null, null, null, null),
            m -> Main.writeManifest(m, "page.html", "file", 640, 480, 25,
                null, out, null, null, true, 12, null, null, null, null, null, null, null),
            m -> Main.writeManifest(m, "page.html", "file", 640, 480, 25,
                null, out, null, null, true, 12, null, null,
                null, null, null, null, null, null, null),
            m -> Main.writeManifest(m, "page.html", "file", 640, 480, 25,
                null, out, null, null, true, 12, null, null,
                null, null, null, null, null, null, null, null),
            m -> Main.writeManifest(m, "page.html", "file", 640, 480, 25,
                null, out, null, null, true, 12, null, null,
                null, null, null, null, null, null, null, null, null),
            m -> Main.writeManifest(m, "page.html", "file", 640, 480, 25,
                null, out, null, null, true, 12, null, null,
                null, null, null, null, null, null, null, null, null, null),
            m -> writeProducerManifest(m, out, null)
        );
        for (int i = 0; i < calls.size(); i++) {
            Path manifest = directory.resolve("overload-" + i + ".json");
            calls.get(i).write(manifest);
            assertEquals(golden, Files.readString(manifest), "legacy overload " + i);
            assertFalse(manifestObject(manifest).containsKey("outSha256"));
            assertFalse(manifestObject(manifest).containsKey("outSha256Provenance"));
        }
    }

    /** Case 7: a producer receipt still speaks truth when the current path no longer exists. */
    @Test
    void receiptAndDocsKeepTheExactCaptureTimeClaimAndConsumerRecomputeBoundary(
            @TempDir Path directory) throws Exception {
        Path out = directory.resolve("historical.png");
        byte[] produced = new byte[] {7, 3, 9, 1};
        var receipt = ArtifactWriter.writeBytesWithReceipt(out, produced, true);
        Files.delete(out);
        Path manifest = directory.resolve("historical.json");
        writeProducerManifest(manifest, out, receipt);
        Map<String, Object> root = manifestObject(manifest);
        assertEquals(ArtifactWriterTest.receiptHash(produced), root.get("outSha256"));
        assertEquals("capture-time-producer", root.get("outSha256Provenance"));
        assertFalse(Files.exists(out), "the receipt is not a current-filesystem-state promise");
        String exactClaim = "BrewShot generated exactly these bytes (sha256 X).";
        String recompute = "Consumers must recompute SHA-256 after transport or immediately before use.";
        for (String path : List.of("README.md", "RELEASE_NOTES.md")) {
            String documentation = Files.readString(Path.of(path));
            assertTrue(documentation.contains(exactClaim), path + ": keep the exact narrow claim");
            assertTrue(documentation.contains(recompute), path + ": preserve the end-to-end boundary");
        }
    }

    @FunctionalInterface
    private interface LegacyManifestCall { void write(Path manifest) throws Exception; }

    private static void writeLegacy(Path manifest, Path out) throws Exception {
        Main.writeManifest(manifest, "page.html", "file", 640, 480, 25,
            null, out, null, null, true, 12, null, null);
    }

    /** Proposed named entry avoids ambiguous null overloads on all the existing APIs. */
    private static void writeProducerManifest(Path manifest, Path out,
            ArtifactWriter.ProducerReceipt receipt) throws Exception {
        Main.writeManifestWithProducerReceipt(manifest, "page.html", "file", 640, 480, 25,
            null, out, null, null, true, 12, null, null,
            null, // page diagnostics
            null, null, // timezone
            null, null, // DPR
            null, null, // vision preview
            null, // viewport source
            null, // cleanup
            null, // file auth
            receipt);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> manifestObject(Path manifest) throws Exception {
        return (Map<String, Object>) MiniJson.parse(Files.readString(manifest));
    }

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
            assertFalse(root.containsKey("pageDiagnostics"),
                "legacy manifests must keep their exact schema when no new flag is present");
            assertFalse(root.containsKey("dpr"),
                "omitted DPR must preserve the legacy one-shot receipt shape");
        }
    }

    @Test
    void captureManifestIsOwnerOnlyEvenWhenReplacingAPermissiveFile(
            @TempDir Path directory) throws Exception {
        Path out = directory.resolve("shot.png");
        Files.write(out, new byte[] {1, 2, 3});
        Path manifest = directory.resolve("private.json");
        Files.writeString(manifest, "old-public-content");
        Assumptions.assumeTrue(
            Files.getFileAttributeView(manifest, PosixFileAttributeView.class) != null,
            "filesystem does not expose POSIX permissions");
        Files.setPosixFilePermissions(manifest, java.util.Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));

        Main.writeManifest(manifest, "page.html", "file", 640, 480, 25,
            null, out, null, null, true, 12, null, null);

        assertEquals(java.util.Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(manifest),
            "raw page diagnostics may be added later, so every capture sidecar starts private");
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
    @SuppressWarnings("unchecked")
    void timezoneManifestRecordsRequestedAndPageVerifiedApplied(@TempDir Path directory)
            throws Exception {
        Path out = directory.resolve("shot.png");
        Files.write(out, new byte[] {1, 2, 3});
        Path manifest = directory.resolve("shot.json");

        Main.writeManifest(manifest, "page.html", "file", 640, 480, 25,
            null, out, null, null, true, 12, null, null, null,
            "Asia/Tokyo", "Asia/Tokyo");

        Map<String, Object> root =
            (Map<String, Object>) MiniJson.parse(Files.readString(manifest));
        assertEquals(Map.of("requested", "Asia/Tokyo", "applied", "Asia/Tokyo"),
            root.get("timezone"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void explicitDprManifestRecordsRequestedAndPageVerifiedApplied(@TempDir Path directory)
            throws Exception {
        Path out = directory.resolve("shot.png");
        Files.write(out, new byte[] {1, 2, 3});
        Path manifest = directory.resolve("shot.json");

        Main.writeManifest(manifest, "page.html", "file", 640, 480, 25,
            null, out, null, null, true, 12, null, null, null,
            null, null, 2, 2);

        Map<String, Object> root =
            (Map<String, Object>) MiniJson.parse(Files.readString(manifest));
        assertEquals(Map.of("requested", 2.0, "applied", 2.0), root.get("dpr"));
        assertFalse(root.containsKey("timezone"));
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

    @Test
    @SuppressWarnings("unchecked")
    void viewportSourceIsRecordedOnlyWhenTheViewportWasChosenForAnSvg(@TempDir Path directory)
            throws Exception {
        Path out = directory.resolve("shot.png");
        Files.write(out, new byte[] {1, 2, 3});

        Path chosen = directory.resolve("chosen.json");
        Main.writeManifest(chosen, "diagram.svg", "file", 1000, 780, 25,
            null, out, null, null, true, 12, null, null,
            null, null, null, null, null, null, null, "svg-width-height");
        Map<String, Object> chosenRoot =
            (Map<String, Object>) MiniJson.parse(Files.readString(chosen));
        assertEquals("1000x780", chosenRoot.get("viewport"));
        assertEquals("svg-width-height", chosenRoot.get("viewportSource"));

        Path legacy = directory.resolve("legacy.json");
        Main.writeManifest(legacy, "page.html", "file", 640, 480, 25,
            null, out, null, null, true, 12, null, null,
            null, null, null, null, null, null, null, null);
        Map<String, Object> legacyRoot =
            (Map<String, Object>) MiniJson.parse(Files.readString(legacy));
        assertFalse(legacyRoot.containsKey("viewportSource"),
            "an explicit --size or a page input must keep the legacy receipt shape");
    }
}
