package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Emulated media (plan 02af3a3d) — forcing {@code prefers-color-scheme}, the emulated media
 * TYPE ({@code print}/{@code screen}), and {@code prefers-reduced-motion} before capture, via
 * CDP {@code Emulation.setEmulatedMedia}. End-to-end against a real Chrome (loud-skip without
 * one), pixel-verified against fixtures whose CSS visibly reacts to each media feature.
 */
class BrewShotEmulatedMediaTest {

    @Test
    void colorSchemeForcesDarkOrLightAndSurvivesReNavigationWithoutRecalling() throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotEmulatedMediaTest");
        String fixture = """
            <style>*{margin:0;padding:0}body{background:#fff}
            @media (prefers-color-scheme: dark) { body { background:#000 } }
            </style>
            """;
        Path out = Files.createTempDirectory("brewshot-color-scheme");
        try (BrewShot shot = BrewShot.launch(200, 200)) {
            BrewShot chained = shot.colorScheme("dark");
            assertSame(shot, chained, "colorScheme() should be chainable (returns this)");
            shot.html(fixture);
            assertBodyPixel(shot, out.resolve("dark1.png"), 0x000000,
                "first navigation under a dark colorScheme");

            // Second navigation WITHOUT recalling colorScheme(): freshNavigation() must
            // re-send Emulation.setEmulatedMedia on its own, or this would silently revert
            // to the browser's real preference.
            shot.html(fixture);
            assertBodyPixel(shot, out.resolve("dark2.png"), 0x000000,
                "second navigation must still be dark — freshNavigation() re-applies the override");
        }
        try (BrewShot shot = BrewShot.launch(200, 200)) {
            shot.colorScheme("light");
            shot.html(fixture);
            assertBodyPixel(shot, out.resolve("light.png"), 0xFFFFFF,
                "colorScheme(\"light\") forces the default (light) background");
        }
    }

    @Test
    void colorSchemeRejectsAnUnknownValue() throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotEmulatedMediaTest");
        try (BrewShot shot = BrewShot.launch(100, 100)) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> shot.colorScheme("bogus"));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> shot.media("bogus"));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> shot.reducedMotion("bogus"));
        }
    }

    @Test
    void mediaForcesPrintStylesToApplyImmediatelyWithoutReNavigating() throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotEmulatedMediaTest");
        // #p is hidden on screen and only shown under @media print — a pixel that is either
        // plain white (hidden) or magenta (shown), depending purely on the emulated media type.
        String fixture = """
            <style>*{margin:0;padding:0}body{background:#fff}
            #p{position:absolute;left:10px;top:10px;width:50px;height:50px;
               background:#ff00ff;display:none}
            @media print { #p{display:block} }
            </style>
            <div id="p"></div>
            """;
        Path out = Files.createTempDirectory("brewshot-media-print");
        try (BrewShot shot = BrewShot.launch(200, 200)) {
            shot.html(fixture);
            // No override yet: default/screen semantics — #p stays hidden.
            shot.screenshot(out.resolve("screen.png"));
            assertEquals(0xFFFFFF, pixel(out.resolve("screen.png"), 30, 30),
                "no media override: #p must stay hidden (screen semantics)");

            // Force print on the ALREADY-LOADED page — no re-navigation — and it must take
            // effect on the very next capture (this is the "before capture/settle" contract).
            BrewShot chained = shot.media("print");
            assertSame(shot, chained, "media() should be chainable (returns this)");
            shot.screenshot(out.resolve("print.png"));
            assertEquals(0xFF00FF, pixel(out.resolve("print.png"), 30, 30),
                "media(\"print\"): #p must now be visible");
        }
    }

    @Test
    void reducedMotionStillsTheAnimationDeterministicallyWhileTheUnforcedRunRunsVisibly()
            throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotEmulatedMediaTest");
        // A high-contrast dot sliding across a white background — guarded off entirely under
        // prefers-reduced-motion: reduce, so the two behaviors are trivially distinguishable.
        String fixture = """
            <style>*{margin:0;padding:0}body{background:#fff}
            #dot{position:absolute;top:60px;left:0;width:24px;height:24px;background:#000;
                 animation:slide 0.5s linear infinite}
            @keyframes slide{from{left:0}to{left:150px}}
            @media (prefers-reduced-motion: reduce){*{animation:none !important}}
            </style>
            <div id="dot"></div>
            """;
        Path out = Files.createTempDirectory("brewshot-reduced-motion");

        // reduce: two captures 150ms apart must be BYTE-IDENTICAL — the animation never starts.
        try (BrewShot shot = BrewShot.launch(200, 200)) {
            BrewShot chained = shot.reducedMotion("reduce");
            assertSame(shot, chained, "reducedMotion() should be chainable (returns this)");
            shot.html(fixture);
            shot.settle(150);
            shot.screenshot(out.resolve("reduce-a.png"));
            shot.settle(150);
            shot.screenshot(out.resolve("reduce-b.png"));
            assertArrayEquals(
                Files.readAllBytes(out.resolve("reduce-a.png")),
                Files.readAllBytes(out.resolve("reduce-b.png")),
                "prefers-reduced-motion: reduce must deterministically still the animation");
        }

        // Judgment call (flagged per plan 02af3a3d): the negative — that the SAME fixture
        // visibly moves without the override — is included because it is checked as a
        // FULL-IMAGE byte comparison (not a single sampled pixel), which sidesteps the obvious
        // flaky case of a sample point landing on background at both instants. The 0.5s loop
        // period against a 150ms real-time capture gap leaves a comfortable margin. If this
        // ever proves flaky in CI, drop this half rather than papering over it with a longer
        // sleep — the reduce-side assertion above is the one that matters.
        try (BrewShot shot = BrewShot.launch(200, 200)) {
            shot.html(fixture);
            shot.settle(150);
            shot.screenshot(out.resolve("normal-a.png"));
            shot.settle(150);
            shot.screenshot(out.resolve("normal-b.png"));
            assertFalse(Arrays.equals(
                Files.readAllBytes(out.resolve("normal-a.png")),
                Files.readAllBytes(out.resolve("normal-b.png"))),
                "without reducedMotion the animation should visibly move between captures");
        }
    }

    @Test
    void cliColorSchemeMediaAndReducedMotionFlagsWireThroughToCapture() throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotEmulatedMediaTest");
        Path dir = Files.createTempDirectory("brewshot-cli-emulated");
        Path page = dir.resolve("p.html");
        Files.writeString(page, """
            <style>*{margin:0;padding:0}body{background:#fff}
            @media (prefers-color-scheme: dark) { body { background:#000 } }
            </style>
            """);
        Path png = dir.resolve("dark.png");
        int code = Main.run(new String[] {
            page.toString(), "-o", png.toString(), "--color-scheme", "dark", "--settle", "50",
        });
        assertEquals(0, code);
        assertEquals(0x000000, pixel(png, 50, 50), "CLI --color-scheme dark");

        Path printPage = dir.resolve("print.html");
        Files.writeString(printPage, """
            <style>*{margin:0;padding:0}body{background:#fff}
            #p{position:absolute;left:5px;top:5px;width:40px;height:40px;
               background:#ff00ff;display:none}
            @media print { #p{display:block} }
            </style><div id="p"></div>
            """);
        Path printPng = dir.resolve("print.png");
        assertEquals(0, Main.run(new String[] {
            printPage.toString(), "-o", printPng.toString(), "--media", "print", "--settle", "50",
        }));
        assertEquals(0xFF00FF, pixel(printPng, 20, 20), "CLI --media print");
    }

    // ---- pixel-sampling helpers --------------------------------------------

    private static void assertBodyPixel(BrewShot shot, Path out, int expectedRgb, String msg)
            throws Exception {
        shot.screenshot(out);
        assertEquals(expectedRgb, pixel(out, 100, 100), msg);
    }

    private static int pixel(Path png, int x, int y) throws Exception {
        BufferedImage img = ImageIO.read(png.toFile());
        return img.getRGB(x, y) & 0xFFFFFF;
    }
}
