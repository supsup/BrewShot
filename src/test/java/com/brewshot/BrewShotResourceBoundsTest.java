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

    /** Bytes of one PNG frame of the 160x120 clip — sizes the budget deterministically. */
    private static long probeFrameBytes(BrewShot shot) {
        byte[] png = shot.screenshotClip(0, 0, 160, 120);
        assertNotNull(png);
        assertTrue(png.length > 0);
        return png.length;
    }
}
