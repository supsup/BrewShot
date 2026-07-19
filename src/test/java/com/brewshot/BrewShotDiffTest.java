package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The diff engine + CLI gate (plan 84f468d0). Pure JDK — no Chrome, no skips: every
 * test always runs, in CI and everywhere else. Assertions follow the acceptance list
 * in the plan: identical -> 0.0%/exit 0; a real 1-px edit is COUNTED (never eaten by
 * the AA heuristic); a shifted-edge pair is AA-forgiven by default but counted under
 * --pixel-exact; masking a dynamic region converges an otherwise-different pair;
 * size mismatch is an explicit verdict; the gate exits 4 with the verdict still
 * written.
 */
class BrewShotDiffTest {

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

    @Test
    void identicalImagesAreACleanNoChangeVerdict() {
        BufferedImage a = solid(100, 80, Color.WHITE);
        BufferedImage b = solid(100, 80, Color.WHITE);
        BrewShotDiff.Verdict v = BrewShotDiff.diff(a, b, BrewShotDiff.Options.defaults());
        assertFalse(v.sizeMismatch());
        assertEquals(0, v.changedPixels());
        assertEquals(0.0, v.pctChanged());
        assertNull(v.largestCluster());
        assertTrue(v.prose().contains("no pixel changes"));
    }

    @Test
    void realBlockChangeIsCountedLocatedAndBandLabeled() {
        // A 20x10 red block on white, near the TOP of a 200-tall image -> "header".
        BufferedImage a = solid(100, 200, Color.WHITE);
        BufferedImage b = solid(100, 200, Color.WHITE);
        fill(b, 40, 5, 20, 10, Color.RED);
        BrewShotDiff.Verdict v = BrewShotDiff.diff(a, b, BrewShotDiff.Options.defaults());

        // Interior pixels of a solid block are never AA-forgiven (red exists in no
        // neighborhood of image A) — only the 1px border ring could be. The count must
        // land within the block's size, and the cluster box must be exactly the block.
        assertTrue(v.changedPixels() > 0 && v.changedPixels() <= 200, "got " + v.changedPixels());
        assertNotNull(v.largestCluster());
        assertEquals("header", v.largestCluster().label());
        assertEquals(1.0, v.largestCluster().shareOfChange(), 1e-9);
        assertTrue(v.largestCluster().x() >= 40 && v.largestCluster().y() >= 5);
        assertTrue(v.prose().contains("in the header"));
    }

    @Test
    void footerBandLabelsBottomChange() {
        BufferedImage a = solid(100, 200, Color.WHITE);
        BufferedImage b = solid(100, 200, Color.WHITE);
        fill(b, 10, 185, 30, 10, Color.BLUE);
        BrewShotDiff.Verdict v = BrewShotDiff.diff(a, b, BrewShotDiff.Options.defaults());
        assertEquals("footer", v.largestCluster().label());
    }

    @Test
    void singlePixelEditSurvivesTheAntialiasingHeuristic() {
        // The plan's acceptance pin: a genuine 1-pixel change must stay counted under
        // the DEFAULT AA-ignore (the new color exists in neither 3x3 neighborhood).
        BufferedImage a = solid(50, 50, Color.WHITE);
        BufferedImage b = solid(50, 50, Color.WHITE);
        b.setRGB(25, 25, Color.RED.getRGB());
        BrewShotDiff.Verdict v = BrewShotDiff.diff(a, b, BrewShotDiff.Options.defaults());
        assertEquals(1, v.changedPixels());
        assertEquals(0, v.antialiasedIgnored());
    }

    @Test
    void shiftedEdgeIsForgivenByDefaultButCountedPixelExact() {
        // A vertical black line shifted right by 1px — the glyph-hinting/AA class.
        // Both edge columns see their counterpart color in the 3x3 neighborhood, so
        // the default mode forgives (and COUNTS) them; --pixel-exact counts as change.
        BufferedImage a = solid(60, 40, Color.WHITE);
        fill(a, 10, 0, 1, 40, Color.BLACK);
        BufferedImage b = solid(60, 40, Color.WHITE);
        fill(b, 11, 0, 1, 40, Color.BLACK);

        BrewShotDiff.Verdict forgiving = BrewShotDiff.diff(a, b, BrewShotDiff.Options.defaults());
        assertEquals(0, forgiving.changedPixels(),
            "a 1px edge shift is anti-aliasing noise under the default heuristic");
        assertEquals(80, forgiving.antialiasedIgnored(),
            "both 40px columns are disclosed as ignored, never silently eaten");
        assertTrue(forgiving.prose().contains("80 anti-aliasing px ignored"));

        BrewShotDiff.Verdict exact = BrewShotDiff.diff(a, b,
            new BrewShotDiff.Options(BrewShotDiff.DEFAULT_TOLERANCE, false, List.of()));
        assertEquals(80, exact.changedPixels(), "--pixel-exact counts the shifted edge");
        assertEquals(0, exact.antialiasedIgnored());
    }

    @Test
    void maskingADynamicRegionConvergesAnOtherwiseDifferentPair() {
        // The timestamp-region acceptance pin: masked pixels never count, and the
        // mask is disclosed in prose + verdict fields.
        BufferedImage a = solid(100, 100, Color.WHITE);
        BufferedImage b = solid(100, 100, Color.WHITE);
        fill(b, 70, 70, 20, 20, Color.GREEN);
        BrewShotDiff.Options masked = new BrewShotDiff.Options(
            BrewShotDiff.DEFAULT_TOLERANCE, true, List.of(new int[] {70, 70, 20, 20}));

        BrewShotDiff.Verdict v = BrewShotDiff.diff(a, b, masked);
        assertEquals(0, v.changedPixels());
        assertEquals(400, v.maskedPixels());
        assertTrue(v.prose().contains("400 px masked"));

        BrewShotDiff.Verdict unmasked = BrewShotDiff.diff(a, b, BrewShotDiff.Options.defaults());
        assertTrue(unmasked.changedPixels() > 0, "the same pair differs without the mask");
    }

    @Test
    void toleranceFloorSuppressesSubThresholdDrift() {
        BufferedImage a = solid(50, 50, new Color(100, 100, 100));
        BufferedImage b = solid(50, 50, new Color(110, 110, 110));  // delta 10 <= default 16
        BrewShotDiff.Verdict v = BrewShotDiff.diff(a, b, BrewShotDiff.Options.defaults());
        assertEquals(0, v.changedPixels());

        BrewShotDiff.Verdict strict = BrewShotDiff.diff(a, b,
            new BrewShotDiff.Options(5, true, List.of()));
        assertTrue(strict.changedPixels() > 0, "a tighter tolerance counts the drift");
    }

    @Test
    void sizeMismatchIsAnExplicitVerdictNotACrash() {
        BrewShotDiff.Verdict v = BrewShotDiff.diff(
            solid(100, 80, Color.WHITE), solid(120, 80, Color.WHITE),
            BrewShotDiff.Options.defaults());
        assertTrue(v.sizeMismatch());
        assertTrue(v.prose().contains("size mismatch: 100x80 vs 120x80"));
        assertTrue(v.anyChange());
    }

    @Test
    void heatmapMarksChangesMagentaAndDimsTheRest() {
        BufferedImage a = solid(50, 50, Color.BLACK);
        BufferedImage b = solid(50, 50, Color.BLACK);
        fill(b, 10, 10, 5, 5, Color.WHITE);
        BufferedImage heat = BrewShotDiff.heatmap(a, b, BrewShotDiff.Options.defaults());
        // interior of the changed block is magenta; far corner is dimmed base (not black)
        assertEquals(0xFF00FF, heat.getRGB(12, 12) & 0xFFFFFF);
        int corner = heat.getRGB(40, 40) & 0xFFFFFF;
        assertTrue(corner != 0x000000 && corner != 0xFF00FF, "base is dimmed, not raw or magenta");
    }

    @Test
    void maskedPixelsLeaveTheDenominatorSoMasksNeverDiluteTheGate() {
        // F2 (consumer review brewshot #45): the SAME 100-px change must read the SAME pct
        // whether or not an unrelated region is masked — pct is changed/COMPARABLE.
        BufferedImage a = solid(100, 100, Color.WHITE);
        BufferedImage b = solid(100, 100, Color.WHITE);
        fill(b, 0, 0, 10, 10, Color.RED);
        BrewShotDiff.Verdict unmasked = BrewShotDiff.diff(a, b, BrewShotDiff.Options.defaults());
        BrewShotDiff.Verdict masked = BrewShotDiff.diff(a, b,
            new BrewShotDiff.Options(BrewShotDiff.DEFAULT_TOLERANCE, true,
                List.of(new int[] {50, 50, 50, 50})));  // 2500 px masked, disjoint from the change
        assertTrue(masked.pctChanged() > unmasked.pctChanged(),
            "removing 2500 comparable px must RAISE the pct of the same change, not dilute it");
        assertEquals(masked.changedPixels(), unmasked.changedPixels());
        assertEquals(100.0 * masked.changedPixels() / (10_000 - 2_500), masked.pctChanged(), 1e-9);
        assertTrue(masked.prose().contains("of 7500"), "prose N-of-M uses comparable pixels: " + masked.prose());
    }

    // ---- CLI: `brewshot diff` (Main.run dispatch, gate contract, sidecars) ----

    private static Path write(Path dir, String name, BufferedImage img) throws IOException {
        Path p = dir.resolve(name);
        ImageIO.write(img, "png", p.toFile());
        return p;
    }

    @Test
    void cliCleanPairExitsZeroWithVerdictArtifacts(@TempDir Path tmp) throws Exception {
        Path a = write(tmp, "a.png", solid(80, 60, Color.WHITE));
        Path b = write(tmp, "b.png", solid(80, 60, Color.WHITE));
        Path json = tmp.resolve("verdict.json");
        int code = Main.run(new String[] {"diff", a.toString(), b.toString(),
            "--json", json.toString(), "--fail-pixels", "0"});
        assertEquals(0, code);
        String sidecar = Files.readString(json);
        assertTrue(sidecar.contains("\"changedPixels\": 0"));
        assertTrue(sidecar.contains("\"exceeded\": false"));
    }

    @Test
    void cliGateTripsExitFourAndStillWritesEverything(@TempDir Path tmp) throws Exception {
        BufferedImage changed = solid(80, 60, Color.WHITE);
        fill(changed, 5, 5, 10, 10, Color.RED);
        Path a = write(tmp, "a.png", solid(80, 60, Color.WHITE));
        Path b = write(tmp, "b.png", changed);
        Path json = tmp.resolve("verdict.json");
        Path heat = tmp.resolve("diff.png");
        int code = Main.run(new String[] {"diff", a.toString(), b.toString(),
            "--fail-pixels", "0", "--json", json.toString(), "--diff-out", heat.toString()});
        assertEquals(4, code);
        // the --fail-js contract: evidence first, exit second — both artifacts exist
        assertTrue(Files.exists(json));
        assertTrue(Files.exists(heat));
        assertTrue(Files.readString(json).contains("\"exceeded\": true"));
    }

    @Test
    void cliFailOverPercentGate(@TempDir Path tmp) throws Exception {
        BufferedImage changed = solid(100, 100, Color.WHITE);
        fill(changed, 0, 0, 50, 50, Color.BLUE);  // ~25% changed
        Path a = write(tmp, "a.png", solid(100, 100, Color.WHITE));
        Path b = write(tmp, "b.png", changed);
        assertEquals(4, Main.run(new String[] {"diff", a.toString(), b.toString(), "--fail-over", "10"}));
        assertEquals(0, Main.run(new String[] {"diff", a.toString(), b.toString(), "--fail-over", "50"}));
    }

    @Test
    void cliSizeMismatchIsInformationalUngatedAndExitFourGated(@TempDir Path tmp) throws Exception {
        Path a = write(tmp, "a.png", solid(80, 60, Color.WHITE));
        Path b = write(tmp, "b.png", solid(90, 60, Color.WHITE));
        Path json = tmp.resolve("verdict.json");
        assertEquals(0, Main.run(new String[] {"diff", a.toString(), b.toString(), "--json", json.toString()}));
        assertTrue(Files.readString(json).contains("\"sizeMismatch\": true"));
        assertEquals(4, Main.run(new String[] {"diff", a.toString(), b.toString(), "--fail-pixels", "0"}));
    }

    @Test
    void cliMaskFlagConvergesAndUnreadableImageIsExitOne(@TempDir Path tmp) throws Exception {
        BufferedImage changed = solid(80, 60, Color.WHITE);
        fill(changed, 60, 40, 10, 10, Color.GREEN);
        Path a = write(tmp, "a.png", solid(80, 60, Color.WHITE));
        Path b = write(tmp, "b.png", changed);
        assertEquals(0, Main.run(new String[] {"diff", a.toString(), b.toString(),
            "--mask", "60,40,10,10", "--fail-pixels", "0"}));

        Path notPng = tmp.resolve("nope.png");
        Files.writeString(notPng, "not an image");
        assertEquals(1, Main.run(new String[] {"diff", a.toString(), notPng.toString()}));
    }

    @Test
    void cliUsageErrorsAreExitTwo(@TempDir Path tmp) throws Exception {
        Path a = write(tmp, "a.png", solid(10, 10, Color.WHITE));
        assertEquals(2, Main.run(new String[] {"diff", a.toString()}));                    // one image
        assertEquals(2, Main.run(new String[] {"diff", a.toString(), a.toString(), "--mask", "1,2,3"}));
        assertEquals(2, Main.run(new String[] {"diff", a.toString(), a.toString(), "--bogus"}));
        assertEquals(0, Main.run(new String[] {"diff", "--help"}));
    }

    @Test
    void heatmapIoFailureCannotSuppressTheJsonSidecar(@TempDir Path tmp) throws Exception {
        // F1 (consumer review brewshot #45): --diff-out pointing into a nonexistent directory
        // must not eat the --json sidecar — the machine artifact writes first, independently.
        BufferedImage changed = solid(40, 40, Color.WHITE);
        fill(changed, 0, 0, 8, 8, Color.RED);
        Path a = write(tmp, "a.png", solid(40, 40, Color.WHITE));
        Path b = write(tmp, "b.png", changed);
        Path json = tmp.resolve("verdict.json");
        Path badHeat = tmp.resolve("no-such-dir/heat.png");
        int code = Main.run(new String[] {"diff", a.toString(), b.toString(),
            "--json", json.toString(), "--diff-out", badHeat.toString(), "--fail-pixels", "0"});
        assertTrue(Files.exists(json), "json sidecar must survive the heatmap IO failure");
        assertTrue(Files.readString(json).contains("\"exceeded\": true"));
        assertEquals(4, code, "gate exit outranks the artifact-IO exit");
    }

    @Test
    void listOfJobsSeamRunsEveryJobAndKeepsTheWorstExit(@TempDir Path tmp) throws Exception {
        // The manifest seam (Fix's shaping, brewshot #25): many jobs, one call, worst
        // exit wins, every job's artifacts written — the future JSON manifest slots in
        // here without reshaping the CLI.
        BufferedImage changed = solid(40, 40, Color.WHITE);
        fill(changed, 0, 0, 10, 10, Color.RED);
        Path a = write(tmp, "a.png", solid(40, 40, Color.WHITE));
        Path b = write(tmp, "b.png", changed);
        Path json1 = tmp.resolve("clean.json");
        Path json2 = tmp.resolve("tripped.json");
        int code = Main.runDiffJobs(List.of(
            new Main.DiffJob(a, a, BrewShotDiff.Options.defaults(), null, 0L, null, json1),
            new Main.DiffJob(a, b, BrewShotDiff.Options.defaults(), null, 0L, null, json2)));
        assertEquals(4, code);
        assertTrue(Files.readString(json1).contains("\"exceeded\": false"));
        assertTrue(Files.readString(json2).contains("\"exceeded\": true"));
    }
}
