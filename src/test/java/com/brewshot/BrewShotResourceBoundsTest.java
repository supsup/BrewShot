package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Resource-bounds discriminators (plan 35e10de3): the per-CDP-call timeout and the
 * recording heap budget. Each bound has a TRIP case and a paired under-the-bound
 * control in the same fixture, so neither can pass by refusing everything — and
 * the truncation contract is pinned as ANNOUNCED (stderr) + STILL-WRITES-A-GIF,
 * never a silent short file and never an OOM.
 */
class BrewShotResourceBoundsTest {

    // ---- per-CDP-call timeout (commandTimeout / BREWSHOT_COMMAND_TIMEOUT_MS) ----

    @Test
    void commandTimeoutBoundsASingleCdpCallAndDefaultDoesNot() throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotResourceBoundsTest");
        try (BrewShot shot = BrewShot.launch(300, 200)) {
            shot.html("<!doctype html><html><body>x</body></html>");

            // A deterministic ~400ms CDP round-trip: Runtime.evaluate blocks on a JS
            // busy-wait, so the SINGLE call exceeds a 100ms budget while navigation
            // (a different axis) was instant.
            String busy = "(function(){var t=Date.now();while(Date.now()-t<400){}return 7;})()";

            shot.commandTimeout(100);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> shot.eval(busy),
                "a 400ms CDP call must trip a 100ms per-call budget");
            assertTrue(e.getMessage().contains("CDP timeout"), e.getMessage());

            // Control in the same fixture: restore a generous budget and the SAME
            // call succeeds — the bound rejects slow calls, not all calls.
            shot.commandTimeout(10_000);
            assertEquals(7.0, ((Number) shot.eval(busy)).doubleValue(), 0.001,
                "the same call under a generous budget must succeed");
        }
    }

    // ---- recording heap budget (recordingHeapBudget / BREWSHOT_MAX_RECORDING_BYTES) ----

    @Test
    void recordingHeapBudgetStopsTheFrameLoopRecorderAnnouncedNotSilent(@TempDir Path dir)
            throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotResourceBoundsTest");
        try (BrewShot shot = BrewShot.launch(320, 240)) {
            shot.html("<!doctype html><html><body style=\"background:#dfe7ff\">"
                + "<div id=\"box\" style=\"width:80px;height:80px;background:#345\"></div>"
                + "</body></html>");

            Path truncated = dir.resolve("truncated.gif");
            PrintStream realErr = System.err;
            ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            try {
                System.setErr(new PrintStream(errBuf, true, StandardCharsets.UTF_8));
                // A budget of ~1.5 frames: the 12-frame request must stop early.
                long oneFrame = probeFrameBytes(shot);
                shot.recordingHeapBudget(oneFrame + oneFrame / 2);
                shot.recordGif(0, 0, 160, 120, 12, 10, 40, truncated);
            } finally {
                System.setErr(realErr);
            }
            String announced = errBuf.toString(StandardCharsets.UTF_8);
            assertTrue(announced.contains("recording stopped")
                    && announced.contains("heap budget"),
                "truncation must be ANNOUNCED on stderr, got: " + announced);
            assertTrue(Files.exists(truncated) && Files.size(truncated) > 0,
                "a truncated recording still writes the frames it captured");

            // Control in the same fixture: a generous budget records all 12 frames
            // with NO truncation announcement, and yields a strictly larger GIF.
            Path full = dir.resolve("full.gif");
            ByteArrayOutputStream quiet = new ByteArrayOutputStream();
            try {
                System.setErr(new PrintStream(quiet, true, StandardCharsets.UTF_8));
                shot.recordingHeapBudget(BrewShot.DEFAULT_MAX_RECORDING_BYTES);
                shot.recordGif(0, 0, 160, 120, 12, 10, 40, full);
            } finally {
                System.setErr(realErr);
            }
            assertFalse(quiet.toString(StandardCharsets.UTF_8).contains("recording stopped"),
                "an in-budget recording must not claim truncation");
            assertTrue(Files.size(full) > Files.size(truncated),
                "the un-truncated recording holds strictly more frames ("
                    + Files.size(full) + " vs " + Files.size(truncated) + " bytes)");
        }
    }

    // ---- the SCREENCAST recorder's trip (brewshot 105 blocker) --------------------

    /// The screencast family is the higher-runaway-risk one: it films until a stop
    /// condition rather than a caller-bounded frame count, so it is the path a long
    /// capture actually OOMs on. The shared FrameBudget LOGIC is proven above; this
    /// pins the screencast INTEGRATION (the :1449 gate) — a dropped break or a frame
    /// not routed through budget.add passes the rest of the suite, and fails here.
    @Test
    void recordingHeapBudgetStopsTheScreencastRecorderAnnouncedNotSilent(@TempDir Path dir)
            throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotResourceBoundsTest");
        try (BrewShot shot = BrewShot.launch(320, 240)) {
            // A CSS animation so the screencast actually pushes a steady frame stream
            // (Chrome only emits screencastFrame on visual change).
            shot.html("<!doctype html><html><head><style>"
                + "@keyframes spin{from{transform:rotate(0)}to{transform:rotate(360deg)}}"
                + "#w{width:90px;height:90px;background:#c33;margin:20px;animation:spin .4s linear infinite}"
                + "</style></head><body><div id=\"w\"></div></body></html>");

            PrintStream realErr = System.err;
            Path truncated = dir.resolve("cast-truncated.gif");
            ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            int truncatedFrames;
            try {
                System.setErr(new PrintStream(errBuf, true, StandardCharsets.UTF_8));
                shot.recordingHeapBudget(12_000); // ~1-2 screencast frames' worth
                truncatedFrames = shot.recordGifStream(900, 40, truncated);
            } finally {
                System.setErr(realErr);
            }
            String announced = errBuf.toString(StandardCharsets.UTF_8);
            assertTrue(announced.contains("recording stopped") && announced.contains("heap budget"),
                "screencast truncation must be ANNOUNCED on stderr, got: " + announced);
            assertTrue(Files.exists(truncated) && Files.size(truncated) > 0,
                "a truncated screencast recording still writes the frames it captured");

            // Control in the same fixture: the default budget films the full window
            // with NO truncation claim and strictly more frames.
            Path full = dir.resolve("cast-full.gif");
            ByteArrayOutputStream quiet = new ByteArrayOutputStream();
            int fullFrames;
            try {
                System.setErr(new PrintStream(quiet, true, StandardCharsets.UTF_8));
                shot.recordingHeapBudget(BrewShot.DEFAULT_MAX_RECORDING_BYTES);
                fullFrames = shot.recordGifStream(900, 40, full);
            } finally {
                System.setErr(realErr);
            }
            assertFalse(quiet.toString(StandardCharsets.UTF_8).contains("recording stopped"),
                "an in-budget screencast must not claim truncation");
            assertTrue(fullFrames > truncatedFrames,
                "the un-truncated screencast holds strictly more frames ("
                    + fullFrames + " vs " + truncatedFrames + ")");
        }
    }

    /// brewshot 109: EVERY accumulating recorder family rides the one FrameBudget — the reviewer's
    /// 8-frame element probe ignored a ~1.5-frame budget with no announcement, and scroll/fullpage/
    /// region had the same private-list shape. One trip per family, each asserting the announced
    /// stop + a still-written GIF; the earlier tests carry the in-budget controls for the class.
    @Test
    void everyAccumulatingRecorderFamilyHonorsTheBudget(@TempDir Path dir) throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotResourceBoundsTest");
        try (BrewShot shot = BrewShot.launch(320, 240)) {
            shot.html("<!doctype html><html><body style=\"margin:0;background:#f2f5ff\">"
                + "<div id=\"el\" style=\"width:100px;height:70px;background:#274\"></div>"
                + "<div style=\"height:900px\"></div></body></html>");
            long one = shot.screenshotClip(0, 0, 100, 70).length;

            interface RecorderThrow { void go(Path out) throws Exception; }
            java.util.Map<String, RecorderThrow> cases = new java.util.LinkedHashMap<>();
            cases.put("element", out -> shot.recordGifElement("#el", 8, 10, 40, 1.0, out));
            cases.put("fullpage", out -> shot.recordGifFullPage(8, 10, 0.4, out));
            cases.put("region", out -> shot.recordGifRegion(0.0, 0.4, 8, 10, 0.5, out));
            cases.put("scroll", out -> shot.recordGifScroll(6, 2, 40, 0.5, out));

            PrintStream realErr = System.err;
            for (var e : cases.entrySet()) {
                Path out = dir.resolve(e.getKey() + ".gif");
                ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
                try {
                    System.setErr(new PrintStream(errBuf, true, StandardCharsets.UTF_8));
                    shot.recordingHeapBudget(one + one / 2);
                    e.getValue().go(out);
                } finally {
                    System.setErr(realErr);
                }
                String announced = errBuf.toString(StandardCharsets.UTF_8);
                assertTrue(announced.contains("recording stopped") && announced.contains("heap budget"),
                    e.getKey() + ": truncation must be ANNOUNCED, got: " + announced);
                assertTrue(Files.exists(out) && Files.size(out) > 0,
                    e.getKey() + ": a truncated recording still writes its frames");
            }
        }
    }

    /** Bytes of one PNG frame of the 160x120 clip — sizes the budget deterministically. */
    private static long probeFrameBytes(BrewShot shot) {
        byte[] png = shot.screenshotClip(0, 0, 160, 120);
        assertNotNull(png);
        assertTrue(png.length > 0);
        return png.length;
    }
}
