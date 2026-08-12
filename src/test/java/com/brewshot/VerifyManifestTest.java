package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifyManifestTest {

    @Test
    void parsesTheClosedSupportedModelAndNormalizesEveryPath(@TempDir Path directory)
            throws Exception {
        Path source = write(directory, manifest(job("home", "fixtures/home.html",
            "baselines/../baselines/home.png", "receipts/home.json", """
                ,"heatmap":"receipts/home.diff.png"
                ,"capture":{
                  "width":1440,"height":900,"settleMs":0,
                  "waitJs":"document.fonts.status === 'loaded'","waitTimeoutMs":5000,
                  "clipSelector":"main","scale":2,"clipPadding":8,
                  "colorScheme":"dark","media":"screen","timezone":"Asia/Tokyo",
                  "reducedMotion":true
                }
                ,"diff":{
                  "tolerance":8,"ignoreAntialiasing":false,
                  "masks":[{"x":1,"y":2,"width":30,"height":40}],
                  "failOverPct":0.25
                }
                """)));

        VerifyManifest parsed = VerifyManifest.load(source);
        assertEquals(source.toAbsolutePath().normalize(), parsed.source());
        assertEquals(directory.toAbsolutePath().normalize(), parsed.workspaceRoot());
        assertEquals(1, parsed.jobs().size());

        VerifyManifest.Job job = parsed.jobs().getFirst();
        assertEquals("home", job.id());
        assertEquals(directory.resolve("fixtures/home.html").toAbsolutePath(), job.input());
        assertEquals(directory.resolve("baselines/home.png").toAbsolutePath(), job.baseline());
        assertEquals(directory.resolve("receipts/home.json").toAbsolutePath(), job.receipt());
        assertEquals(directory.resolve("receipts/home.diff.png").toAbsolutePath(), job.heatmap());
        assertEquals(1440, job.capture().width());
        assertEquals(900, job.capture().height());
        assertEquals(0, job.capture().settleMs());
        assertEquals("document.fonts.status === 'loaded'", job.capture().waitJs());
        assertEquals(5000, job.capture().waitTimeoutMs());
        assertEquals("main", job.capture().clipSelector());
        assertEquals(2.0, job.capture().scale());
        assertEquals(8.0, job.capture().clipPadding());
        assertEquals("dark", job.capture().colorScheme());
        assertEquals("screen", job.capture().media());
        assertEquals("Asia/Tokyo", job.capture().timezone());
        assertTrue(job.capture().reducedMotion());
        assertEquals(8, job.diff().options().tolerance());
        assertFalse(job.diff().options().ignoreAntialiasing());
        assertEquals(1, job.diff().options().masks().size());
        assertTrue(Arrays.equals(new int[] {1, 2, 30, 40},
            job.diff().options().masks().getFirst()));
        assertEquals(Double.valueOf(0.25), job.diff().failOverPct());
        assertNull(job.diff().failPixels(),
            "an explicit percentage gate replaces, rather than composes with, the exact default");
    }

    @Test
    void defaultsAreDeterministicAndAnyMeaningfulPixelChangeGates(@TempDir Path directory)
            throws Exception {
        VerifyManifest.Job job = VerifyManifest.load(write(directory,
            manifest(job("home", "home.html", "baseline.png", "receipt.json", ""))))
            .jobs().getFirst();

        assertEquals(1280, job.capture().width());
        assertEquals(900, job.capture().height());
        assertEquals(800, job.capture().settleMs());
        assertEquals(10_000, job.capture().waitTimeoutMs());
        assertEquals(1.0, job.capture().scale());
        assertEquals(BrewShotDiff.DEFAULT_TOLERANCE, job.diff().options().tolerance());
        assertTrue(job.diff().options().ignoreAntialiasing());
        assertEquals(Long.valueOf(0), job.diff().failPixels());
        assertNull(job.diff().failOverPct());
    }

    @Test
    void duplicateKeysAreRejectedAtEveryObjectDepth(@TempDir Path directory) throws Exception {
        assertInvalid(directory,
            "{\"version\":1,\"version\":1,\"jobs\":[]}", "duplicate object key");
        assertInvalid(directory, manifest("""
            {"id":"one","id":"two","input":"a.html",
             "baseline":"a.png","receipt":"a.json"}
            """), "duplicate object key");
        assertInvalid(directory, manifest(job("one", "a.html", "a.png", "a.json",
            ",\"capture\":{\"width\":100,\"width\":200}")), "duplicate object key");
    }

    @Test
    void everySchemaLevelIsClosedAndExplicitNullIsNotAbsence(@TempDir Path directory)
            throws Exception {
        assertInvalid(directory,
            "{\"version\":1,\"jobs\":[],\"surprise\":true}", "unknown field");
        assertInvalid(directory, manifest(job("one", "a.html", "a.png", "a.json",
            ",\"headers\":[]")), "unknown field");
        assertInvalid(directory, manifest(job("one", "a.html", "a.png", "a.json",
            ",\"capture\":{\"clipJs\":\"({x:0,y:0,w:1,h:1})\"}")),
            "unknown field");
        assertInvalid(directory, manifest(job("one", "a.html", "a.png", "a.json",
            ",\"capture\":{\"timezone\":\" \"}")), "must be a non-blank string");
        assertInvalid(directory, manifest(job("one", "a.html", "a.png", "a.json",
            ",\"diff\":{\"algorithm\":\"ssim\"}")), "unknown field");
        assertInvalid(directory, manifest(job("one", "a.html", "a.png", "a.json",
            ",\"diff\":{\"masks\":[{\"x\":0,\"y\":0,\"width\":1,\"height\":1,\"why\":\"clock\"}]}")),
            "unknown field");
        assertInvalid(directory, manifest(job("one", "a.html", "a.png", "a.json",
            ",\"capture\":null")), "must be an object");
        assertInvalid(directory, manifest(job("one", "a.html", "a.png", "a.json",
            ",\"diff\":{\"masks\":null}")), "must be an array");
    }

    @Test
    void duplicateIdsAndNormalizedLogicalOutputsRefuseTheWholeManifest(
            @TempDir Path directory) throws Exception {
        assertInvalid(directory, manifest(
            job("same", "a.html", "a.png", "a.json", "") + "," +
            job("same", "b.html", "b.png", "b.json", "")), "duplicate job id");

        VerifyManifest.ManifestException duplicateOutput = assertInvalid(directory, manifest(
            job("one", "a.html", "a.png", "receipts/one.json", "") + "," +
            job("two", "b.html", "b.png", "receipts/../receipts/one.json", "")),
            "duplicate logical output");
        assertTrue(duplicateOutput.getMessage().contains("job one receipt"));
        assertTrue(duplicateOutput.getMessage().contains("job two receipt"));
    }

    @Test
    void pathsStayLocalRelativeAndInsideTheManifestDirectory(@TempDir Path directory)
            throws Exception {
        assertInvalid(directory, manifest(job("one", "../a.html", "a.png", "a.json", "")),
            "escapes the manifest directory");
        assertInvalid(directory, manifest(job("one", "/tmp/a.html", "a.png", "a.json", "")),
            "must be relative");
        assertInvalid(directory, manifest(job("one", "https://example.test/a.html",
            "a.png", "a.json", "")), "not a URL");
        assertInvalid(directory, manifest(job("one", "a.txt", "a.png", "a.json", "")),
            "must use");
        assertInvalid(directory, manifest(job("one", "a.html", "a.jpg", "a.json", "")),
            "must use .png");
    }

    @Test
    void numericAndOptionBoundsRejectIgnoredOrUnboundedWork(@TempDir Path directory)
            throws Exception {
        assertInvalid(directory, manifest(job("one", "a.html", "a.png", "a.json",
            ",\"capture\":{\"width\":16384,\"height\":16384}")),
            "viewport exceeds");
        assertInvalid(directory, manifest(job("one", "a.html", "a.png", "a.json",
            ",\"capture\":{\"waitTimeoutMs\":5000}")), "requires waitJs");
        assertInvalid(directory, manifest(job("one", "a.html", "a.png", "a.json",
            ",\"capture\":{\"clipPadding\":2}")), "requires clipSelector");
        assertInvalid(directory, manifest(job("one", "a.html", "a.png", "a.json",
            ",\"capture\":{\"scale\":5}")), "finite number");
        assertInvalid(directory, manifest(job("one", "a.html", "a.png", "a.json",
            ",\"diff\":{\"tolerance\":255}")), "integer in 0..254");
        assertInvalid(directory, manifest(job("one", "a.html", "a.png", "a.json",
            ",\"diff\":{\"failPixels\":0.5}")), "must be an integer");
    }

    @Test
    void jobAndMaskCountsAreBounded(@TempDir Path directory) throws Exception {
        List<String> jobs = new ArrayList<>();
        for (int index = 0; index <= VerifyManifest.MAX_JOBS; index++) {
            jobs.add(job("j" + index, "i" + index + ".html",
                "b" + index + ".png", "r" + index + ".json", ""));
        }
        assertInvalid(directory, manifest(String.join(",", jobs)),
            "exceeds the " + VerifyManifest.MAX_JOBS + "-job limit");

        List<String> masks = new ArrayList<>();
        for (int index = 0; index <= VerifyManifest.MAX_MASKS_PER_JOB; index++) {
            masks.add("{\"x\":" + index + ",\"y\":0,\"width\":1,\"height\":1}");
        }
        assertInvalid(directory, manifest(job("one", "a.html", "a.png", "a.json",
            ",\"diff\":{\"masks\":[" + String.join(",", masks) + "]}")),
            "exceeds the " + VerifyManifest.MAX_MASKS_PER_JOB + "-mask limit");
    }

    @Test
    void malformedUtf8AndOversizedFilesFailBeforeJsonInterpretation(@TempDir Path directory)
            throws Exception {
        Path manifest = directory.resolve("shots.json");
        byte[] prefix = "{\"version\":1,\"jobs\":[\"".getBytes(StandardCharsets.UTF_8);
        byte[] malformed = Arrays.copyOf(prefix, prefix.length + 2);
        malformed[prefix.length] = (byte) 0xC3;
        malformed[prefix.length + 1] = 0x28;
        Files.write(manifest, malformed);
        VerifyManifest.ManifestException utf8 = assertThrows(
            VerifyManifest.ManifestException.class, () -> VerifyManifest.load(manifest));
        assertTrue(utf8.getMessage().contains("not valid UTF-8"), utf8.getMessage());

        byte[] oversized = new byte[VerifyManifest.MAX_MANIFEST_BYTES + 1];
        Arrays.fill(oversized, (byte) ' ');
        Files.write(manifest, oversized);
        VerifyManifest.ManifestException limit = assertThrows(
            VerifyManifest.ManifestException.class, () -> VerifyManifest.load(manifest));
        assertTrue(limit.getMessage().contains("exceeds the "
            + VerifyManifest.MAX_MANIFEST_BYTES + "-byte limit"), limit.getMessage());
    }

    @Test
    void strictJsonRejectsInvalidNumbersRawControlsAndUnpairedSurrogates(
            @TempDir Path directory) throws Exception {
        assertInvalid(directory,
            "{\"version\":+1,\"jobs\":[]}", "bad JSON number");
        assertInvalid(directory,
            "{\"version\":1,\"jobs\":[{\"id\":\"bad\nraw\"}]}",
            "unescaped control character");
        assertInvalid(directory, manifest(job("one", "a.html", "a.png", "a.json",
            ",\"capture\":{\"waitJs\":\"\\uD800\"}")), "unpaired surrogate");
    }

    @Test
    void typedFailureCategoriesPreserveTheExistingWorstExitContract() {
        assertEquals(1, VerifyManifest.worstExit(List.of(
            new VerifyManifest.Failure(
                VerifyManifest.FailureCategory.DIFF, "one", "diff failed"),
            new VerifyManifest.Failure(
                VerifyManifest.FailureCategory.CAPTURE, "two", "capture failed"))));
        assertEquals(2, VerifyManifest.worstExit(List.of(
            new VerifyManifest.Failure(
                VerifyManifest.FailureCategory.COMMIT, "one", "replace failed"),
            new VerifyManifest.Failure(
                VerifyManifest.FailureCategory.PREFLIGHT, null, "alias"))));
        assertEquals(4, VerifyManifest.worstExit(List.of(
            new VerifyManifest.Failure(
                VerifyManifest.FailureCategory.MANIFEST, null, "bad manifest"),
            new VerifyManifest.Failure(
                VerifyManifest.FailureCategory.THRESHOLD, "one", "gate exceeded"))));
        assertEquals(VerifyManifest.FailureCategory.COMMIT,
            VerifyManifest.FailureCategory.worst(
                VerifyManifest.FailureCategory.CAPTURE,
                VerifyManifest.FailureCategory.COMMIT));
        assertEquals(0, VerifyManifest.worstExit(List.of()));
    }

    private static Path write(Path directory, String json) throws Exception {
        Path manifest = directory.resolve("shots.json");
        Files.writeString(manifest, json, StandardCharsets.UTF_8);
        return manifest;
    }

    private static VerifyManifest.ManifestException assertInvalid(
            Path directory, String json, String expected) throws Exception {
        VerifyManifest.ManifestException failure = assertThrows(
            VerifyManifest.ManifestException.class,
            () -> VerifyManifest.load(write(directory, json)));
        assertEquals(VerifyManifest.FailureCategory.MANIFEST,
            failure.failure().category());
        assertEquals(2, failure.failure().category().exitCode());
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
        return failure;
    }

    private static String manifest(String jobs) {
        return "{\"version\":1,\"jobs\":[" + jobs + "]}";
    }

    private static String job(String id, String input, String baseline,
                              String receipt, String extraFields) {
        return "{\"id\":\"" + id + "\",\"input\":\"" + input
            + "\",\"baseline\":\"" + baseline + "\",\"receipt\":\""
            + receipt + "\"" + extraFields + "}";
    }
}
