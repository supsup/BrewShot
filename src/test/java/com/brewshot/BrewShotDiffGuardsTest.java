package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The diff GUARD fixes (audit F-03/F-04/F-05 + PNG size caps). Pure JDK — no Chrome, no
 * skips. Each test pins the BEFORE/AFTER of a specific correctness or safety hole:
 *  F-03  tolerance is range-checked (0..255) — an out-of-range value can't neuter the gate.
 *  F-04  the CLI is STRICT by default; AA-forgiveness is opt-in (--ignore-antialiasing).
 *  F-05  an output sidecar aliasing an input (or the other sidecar) is refused pre-decode.
 *  caps  an over-dimension / over-area input is refused BEFORE the pixel decode (exit 2).
 */
class BrewShotDiffGuardsTest {

    private static BufferedImage solid(int w, int h, Color c) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private static void fill(BufferedImage img, int x, int y, int w, int h, Color c) {
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(x, y, w, h);
        g.dispose();
    }

    private static Path write(Path dir, String name, BufferedImage img) throws IOException {
        Path p = dir.resolve(name);
        ImageIO.write(img, "png", p.toFile());
        return p;
    }

    // ---- F-03 / brewshot/153: tolerance range validation (boundaries -1, 0, 254, 255, 256) --

    @Test
    void toleranceBoundariesValidateInTheCanonicalConstructor() {
        // The valid range is 0..254 INCLUSIVE. 255 is rejected too (Fix, review brewshot/153):
        // the compare is max > tolerance and an 8-bit channel delta maxes at 255, so tolerance
        // 255 makes EVERY same-size diff report zero change — a gate that can never fail.
        assertThrows(IllegalArgumentException.class,
            () -> new BrewShotDiff.Options(-1, false, List.of()), "tolerance -1 must be rejected");
        assertThrows(IllegalArgumentException.class,
            () -> new BrewShotDiff.Options(255, false, List.of()),
            "tolerance 255 must be rejected — it disables the gate");
        assertThrows(IllegalArgumentException.class,
            () -> new BrewShotDiff.Options(256, false, List.of()), "tolerance 256 must be rejected");
        assertDoesNotThrow(() -> new BrewShotDiff.Options(0, false, List.of()));
        assertDoesNotThrow(() -> new BrewShotDiff.Options(254, false, List.of()),
            "254 is the top valid floor (max delta 255 > 254 still counts)");
    }

    @Test
    void cliSurfacesToleranceRangeErrorsAsExitTwo(@TempDir Path tmp) throws Exception {
        Path a = write(tmp, "a.png", solid(20, 20, Color.WHITE));
        Path b = write(tmp, "b.png", solid(20, 20, Color.WHITE));
        // out-of-range -> exit 2 (usage error), never a stack trace / exit 1
        assertEquals(2, Main.run(new String[] {"diff", a.toString(), b.toString(), "--tolerance", "-1"}));
        // 255 is now out-of-range too — it would neuter the gate, so the CLI refuses it (exit 2)
        assertEquals(2, Main.run(new String[] {"diff", a.toString(), b.toString(), "--tolerance", "255"}));
        assertEquals(2, Main.run(new String[] {"diff", a.toString(), b.toString(), "--tolerance", "256"}));
        // in-range boundaries parse and run clean (identical pair -> exit 0)
        assertEquals(0, Main.run(new String[] {"diff", a.toString(), b.toString(), "--tolerance", "0"}));
        assertEquals(0, Main.run(new String[] {"diff", a.toString(), b.toString(), "--tolerance", "254"}));
    }

    @Test
    void maximalDeltaIsCountedNotSuppressedAtTheTopValidTolerance() {
        // The defect's teeth (repro at ba27f7c): 1x1 black vs 1x1 white — a maximal per-channel
        // delta of 255 — must be COUNTED, never suppressed. At the top valid tolerance 254 the
        // compare 255 > 254 holds, so the change is reported (engine level, no Chrome).
        BufferedImage black = solid(1, 1, Color.BLACK);
        BufferedImage white = solid(1, 1, Color.WHITE);
        BrewShotDiff.Verdict v = BrewShotDiff.diff(black, white,
            new BrewShotDiff.Options(254, false, List.of()));
        assertEquals(1, v.changedPixels(), "a maximal black/white delta must count at tolerance 254");
        assertTrue(v.anyChange(), "the maximal-delta verdict must report a change");
    }

    @Test
    void cliCountsMaximalDeltaAtTopValidTolerance(@TempDir Path tmp) throws Exception {
        // The CLI companion: the same maximal black/white pair trips a zero-pixel gate at
        // tolerance 254 (exit 4) instead of being silently green. Before the fix, tolerance 255
        // (now refused) would have reported zero change and passed.
        Path black = write(tmp, "black.png", solid(4, 4, Color.BLACK));
        Path white = write(tmp, "white.png", solid(4, 4, Color.WHITE));
        assertEquals(4, Main.run(new String[] {"diff", black.toString(), white.toString(),
            "--tolerance", "254", "--fail-pixels", "0"}),
            "a maximal-delta pair must be counted (gate trips) at the top valid tolerance 254");
    }

    // ---- F-04: strict-by-default; AA-forgiveness is opt-in --------------------------------

    @Test
    void oneDefaultStrictThenAaOptInEngineLevel() {
        // A zero-AA hard-edged 20x20 black rectangle translated EXACTLY 1px right. Only the two
        // edge columns differ (2 cols x 20 rows = 40 px). Strict counts them; AA-on forgives all.
        BufferedImage a = solid(60, 40, Color.WHITE);
        fill(a, 10, 10, 20, 20, Color.BLACK);
        BufferedImage b = solid(60, 40, Color.WHITE);
        fill(b, 11, 10, 20, 20, Color.BLACK);

        BrewShotDiff.Verdict strict = BrewShotDiff.diff(a, b,
            new BrewShotDiff.Options(BrewShotDiff.DEFAULT_TOLERANCE, false, List.of()));
        assertEquals(40, strict.changedPixels(), "strict default COUNTS the 1px translate");
        assertEquals(0, strict.antialiasedIgnored());

        BrewShotDiff.Verdict forgiving = BrewShotDiff.diff(a, b,
            new BrewShotDiff.Options(BrewShotDiff.DEFAULT_TOLERANCE, true, List.of()));
        assertEquals(0, forgiving.changedPixels(), "AA-on forgives the shifted hard edge");
        assertEquals(40, forgiving.antialiasedIgnored(), "forgiven pixels are disclosed, not eaten");
    }

    @Test
    void cliDefaultIsStrictAndFlipsWithTheOptInFlag(@TempDir Path tmp) throws Exception {
        // The F-04 regression: the SAME 1px translate must FAIL the gate under the new default
        // (a genuine layout move is not silently swallowed), and pass only with the opt-in flag.
        BufferedImage a = solid(60, 40, Color.WHITE);
        fill(a, 10, 10, 20, 20, Color.BLACK);
        BufferedImage b = solid(60, 40, Color.WHITE);
        fill(b, 11, 10, 20, 20, Color.BLACK);
        Path pa = write(tmp, "a.png", a);
        Path pb = write(tmp, "b.png", b);

        // DEFAULT (strict): 40 px changed -> --fail-pixels 0 trips -> exit 4
        assertEquals(4, Main.run(new String[] {"diff", pa.toString(), pb.toString(), "--fail-pixels", "0"}),
            "strict default must COUNT the 1px move and fail the gate");
        // OPT-IN forgiveness: the shifted edge is forgiven -> 0 changed -> gate passes -> exit 0
        assertEquals(0, Main.run(new String[] {"diff", pa.toString(), pb.toString(),
            "--ignore-antialiasing", "--fail-pixels", "0"}),
            "--ignore-antialiasing forgives the shifted edge and passes the gate");
    }

    @Test
    void pixelExactIsByteExactAndReconcilesWithTheOptInFlag(@TempDir Path tmp) throws Exception {
        Path a = write(tmp, "a.png", solid(20, 20, Color.WHITE));
        Path b = write(tmp, "b.png", solid(20, 20, Color.WHITE));
        // --pixel-exact alone is fine (byte-exact); identical pair -> exit 0
        assertEquals(0, Main.run(new String[] {"diff", a.toString(), b.toString(), "--pixel-exact"}));
        // contradictory combos are refused LOUD (exit 2), never silently reconciled
        assertEquals(2, Main.run(new String[] {"diff", a.toString(), b.toString(),
            "--pixel-exact", "--ignore-antialiasing"}));
        assertEquals(2, Main.run(new String[] {"diff", a.toString(), b.toString(),
            "--pixel-exact", "--tolerance", "8"}));
        // --pixel-exact with an explicit tolerance 0 is consistent -> allowed
        assertEquals(0, Main.run(new String[] {"diff", a.toString(), b.toString(),
            "--pixel-exact", "--tolerance", "0"}));
    }

    // ---- F-05: artifact-alias guard ------------------------------------------------------

    @Test
    void sidecarAliasingAnInputIsRefusedExitTwoAndDoesNotDestroyIt(@TempDir Path tmp) throws Exception {
        Path a = write(tmp, "a.png", solid(30, 20, Color.WHITE));
        Path b = write(tmp, "b.png", solid(30, 20, Color.WHITE));
        long aBytesBefore = Files.size(a);

        // --json pointed at an INPUT: refused before any decode/write; input intact
        assertEquals(2, Main.run(new String[] {"diff", a.toString(), b.toString(),
            "--json", a.toString()}));
        // --diff-out pointed at an INPUT: same refusal
        assertEquals(2, Main.run(new String[] {"diff", a.toString(), b.toString(),
            "--diff-out", b.toString()}));
        // --json and --diff-out colliding on the same path: refused
        Path both = tmp.resolve("both.out");
        assertEquals(2, Main.run(new String[] {"diff", a.toString(), b.toString(),
            "--json", both.toString(), "--diff-out", both.toString()}));

        // the input was never touched (byte size unchanged, still a decodable 30x20 image)
        assertEquals(aBytesBefore, Files.size(a), "an aliased input must not be overwritten");
        BufferedImage reread = ImageIO.read(a.toFile());
        assertEquals(30, reread.getWidth());
        assertEquals(20, reread.getHeight());
    }

    @Test
    void distinctSidecarsAreWrittenAtomicallyWithNoTempLeftovers(@TempDir Path tmp) throws Exception {
        BufferedImage changed = solid(40, 40, Color.WHITE);
        fill(changed, 0, 0, 10, 10, Color.RED);
        Path a = write(tmp, "a.png", solid(40, 40, Color.WHITE));
        Path b = write(tmp, "b.png", changed);
        Path json = tmp.resolve("verdict.json");
        Path heat = tmp.resolve("heat.png");
        int code = Main.run(new String[] {"diff", a.toString(), b.toString(),
            "--json", json.toString(), "--diff-out", heat.toString(), "--fail-pixels", "0"});
        assertEquals(4, code);
        assertTrue(Files.exists(json) && Files.exists(heat));
        // the heatmap is a valid PNG (proves the temp->atomic-move landed a complete file)
        assertEquals(40, ImageIO.read(heat.toFile()).getWidth());
        // no ".brewshot-*" temp files left behind in the output directory
        try (Stream<Path> s = Files.list(tmp)) {
            assertTrue(s.noneMatch(p -> p.getFileName().toString().startsWith(".brewshot-")),
                "atomic write must leave no temp files");
        }
    }

    // ---- PNG size-limit properties (pre-decode enforcement, -D overridable) ---------------

    @Test
    void overDimensionInputIsRefusedExitTwoBeforeDecode(@TempDir Path tmp) throws Exception {
        String dimKey = "brewshot.maxImageDimension";
        String prev = System.getProperty(dimKey);
        try {
            System.setProperty(dimKey, "100");
            Path big = write(tmp, "big.png", solid(101, 50, Color.WHITE));   // 101 > 100 per axis
            Path ok = write(tmp, "ok.png", solid(50, 50, Color.WHITE));
            // exit 2 (usage-class refusal), not exit 1 (decode failure) -> the pre-decode guard fired
            assertEquals(2, Main.run(new String[] {"diff", big.toString(), ok.toString()}));
        } finally {
            restore(dimKey, prev);
        }
    }

    @Test
    void overAreaInputIsRefusedExitTwoBeforeDecode(@TempDir Path tmp) throws Exception {
        String dimKey = "brewshot.maxImageDimension";
        String pixKey = "brewshot.maxImagePixels";
        String prevDim = System.getProperty(dimKey);
        String prevPix = System.getProperty(pixKey);
        try {
            System.setProperty(dimKey, "100");     // 40x40 is within the per-axis cap
            System.setProperty(pixKey, "1000");    // but 40*40 = 1600 > 1000 area
            Path big = write(tmp, "area.png", solid(40, 40, Color.WHITE));
            Path ok = write(tmp, "ok.png", solid(20, 20, Color.WHITE));
            assertEquals(2, Main.run(new String[] {"diff", big.toString(), ok.toString()}));
        } finally {
            restore(dimKey, prevDim);
            restore(pixKey, prevPix);
        }
    }

    @Test
    void atLimitInputIsAccepted(@TempDir Path tmp) throws Exception {
        String dimKey = "brewshot.maxImageDimension";
        String pixKey = "brewshot.maxImagePixels";
        String prevDim = System.getProperty(dimKey);
        String prevPix = System.getProperty(pixKey);
        try {
            System.setProperty(dimKey, "100");      // exactly-100 axis is NOT over (strict >)
            System.setProperty(pixKey, "10000");    // exactly 100*100 = 10000 is NOT over
            Path a = write(tmp, "a.png", solid(100, 100, Color.WHITE));
            Path b = write(tmp, "b.png", solid(100, 100, Color.WHITE));
            assertEquals(0, Main.run(new String[] {"diff", a.toString(), b.toString()}),
                "an at-limit input must be accepted (boundary is inclusive)");
        } finally {
            restore(dimKey, prevDim);
            restore(pixKey, prevPix);
        }
    }

    @Test
    void aDMinusOverrideRaisesTheLimit(@TempDir Path tmp) throws Exception {
        String dimKey = "brewshot.maxImageDimension";
        String prev = System.getProperty(dimKey);
        try {
            Path a = write(tmp, "a.png", solid(200, 200, Color.WHITE));
            Path b = write(tmp, "b.png", solid(200, 200, Color.WHITE));
            // a tight cap rejects the 200px image...
            System.setProperty(dimKey, "100");
            assertEquals(2, Main.run(new String[] {"diff", a.toString(), b.toString()}));
            // ...and RAISING the cap (read fresh each run) accepts it
            System.setProperty(dimKey, "16384");
            assertEquals(0, Main.run(new String[] {"diff", a.toString(), b.toString()}));
        } finally {
            restore(dimKey, prev);
        }
    }

    private static void restore(String key, String prev) {
        if (prev == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, prev);
        }
    }
}
