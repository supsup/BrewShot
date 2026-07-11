package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Input dispatch (mouse/click/hover) + the per-frame recording hook, against a
 * real Chrome (assume-skips without one). The recurring theme: these must be
 * TRUSTED browser events and REAL captured effects — every test asserts
 * something page-side JS could not fake ({@code event.isTrusted}, engaged
 * {@code :hover} computed style, pixel deltas between recorded frames).
 */
class BrewShotInputDispatchTest {

    @Test
    void clickDispatchesTrustedPressReleaseClickToHandlers() throws Exception {
        assumeTrue(BrewShot.available(), "no local Chrome; skipping");
        try (BrewShot shot = BrewShot.launch(640, 480)) {
            shot.html("""
                <style>*{margin:0;padding:0}
                  #btn{position:absolute;left:50px;top:40px;width:100px;height:30px}</style>
                <button id="btn">go</button>
                <script>
                  window.seen = [];
                  ['mousedown','mouseup','click'].forEach(function (t) {
                    document.getElementById('btn').addEventListener(t, function (e) {
                      window.seen.push(t + ':' + e.isTrusted);
                    });
                  });
                </script>
                """);
            shot.click("#btn");
            // the real-user sequence, every event trusted — a page-side
            // dispatchEvent() would report isTrusted:false
            assertEquals("mousedown:true,mouseup:true,click:true",
                shot.eval("window.seen.join(',')"));
        }
    }

    @Test
    void hoverEngagesRealHoverStateNotJustHandlers() throws Exception {
        assumeTrue(BrewShot.available(), "no local Chrome; skipping");
        try (BrewShot shot = BrewShot.launch(640, 480)) {
            shot.html("""
                <style>*{margin:0;padding:0}
                  #t{position:absolute;left:30px;top:20px;width:80px;height:40px;
                     background:rgb(10, 10, 10)}
                  #t:hover{background:rgb(200, 50, 50)}</style>
                <div id="t"></div>
                <script>
                  window.entered = false;
                  document.getElementById('t').addEventListener('mouseenter',
                    function (e) { window.entered = e.isTrusted; });
                </script>
                """);
            shot.hover("#t");
            // :hover is the unfakeable part — JS cannot force the CSS hover
            // pseudo-class; only real browser input engages it
            assertEquals("rgb(200, 50, 50)",
                shot.eval("getComputedStyle(document.getElementById('t')).backgroundColor"));
            assertEquals(Boolean.TRUE, shot.eval("window.entered"));
        }
    }

    @Test
    void clickTakesDocumentCoordinatesEvenAfterScroll() throws Exception {
        assumeTrue(BrewShot.available(), "no local Chrome; skipping");
        // The coordinate-space pin: elementBox speaks document coordinates,
        // CDP input wants viewport coordinates. Scroll the page, then click at
        // the element's DOCUMENT center — if the scroll offset weren't
        // subtracted, this would click 500px below the target.
        try (BrewShot shot = BrewShot.launch(640, 480)) {
            shot.html("""
                <style>*{margin:0;padding:0}body{height:2000px}
                  #deep{position:absolute;left:100px;top:700px;width:120px;height:40px}</style>
                <button id="deep">deep</button>
                <script>
                  window.hit = false;
                  document.getElementById('deep').addEventListener('click',
                    function (e) { window.hit = e.isTrusted; });
                </script>
                """);
            shot.eval("window.scrollTo(0, 500)");
            double[] b = shot.elementBox("#deep");
            shot.click(b[0] + b[2] / 2, b[1] + b[3] / 2);
            assertEquals(Boolean.TRUE, shot.eval("window.hit"),
                "document-coordinate click must land on the scrolled-away element");
        }
    }

    @Test
    void selectorMissAndNonFinitePointFailLoud() throws Exception {
        assumeTrue(BrewShot.available(), "no local Chrome; skipping");
        try (BrewShot shot = BrewShot.launch(320, 240)) {
            shot.html("<p>empty</p>");
            assertThrows(IllegalArgumentException.class, () -> shot.click("#nope"));
            assertThrows(IllegalArgumentException.class, () -> shot.hover("#nope"));
            assertThrows(IllegalArgumentException.class, () -> shot.mouse(Double.NaN, 5));
            assertThrows(IllegalArgumentException.class,
                () -> shot.click(3, Double.POSITIVE_INFINITY));
        }
    }

    @Test
    void perFrameHookDrivesTheRecordingDeterministically() throws Exception {
        assumeTrue(BrewShot.available(), "no local Chrome; skipping");
        Path out = Files.createTempDirectory("brewshot-hook");
        Path gif = out.resolve("stepped.gif");
        List<Integer> hookCalls = new ArrayList<>();
        try (BrewShot shot = BrewShot.launch(320, 240)) {
            shot.html("""
                <style>*{margin:0;padding:0}
                  #cell{position:absolute;left:0;top:0;width:60px;height:60px;
                        background:rgb(0, 0, 0)}</style>
                <div id="cell"></div>
                <script>
                  // deterministic stepper: frame i paints gray level i*80
                  window.step = function (i) {
                    var v = i * 80;
                    document.getElementById('cell').style.background =
                      'rgb(' + v + ',' + v + ',' + v + ')';
                  };
                </script>
                """);
            shot.recordGifElement("#cell", 4, 10, 40, 1.0, i -> {
                hookCalls.add(i);
                shot.eval("window.step(" + i + ")");
            }, gif);
        }
        // the hook ran once per frame, in order, before each capture
        assertEquals(List.of(0, 1, 2, 3), hookCalls);

        // and its page mutations LANDED in the recorded frames: frame 0 is the
        // i=0 paint (black), frame 3 the i=3 paint (240-gray) — pixel-verified
        try (var in = javax.imageio.ImageIO.createImageInputStream(gif.toFile())) {
            var reader = javax.imageio.ImageIO.getImageReaders(in).next();
            reader.setInput(in);
            assertEquals(4, reader.getNumImages(true), "frame count");
            BufferedImage first = reader.read(0);
            BufferedImage last = reader.read(3);
            int firstLum = first.getRGB(30, 30) & 0xFF;
            int lastLum = last.getRGB(30, 30) & 0xFF;
            reader.dispose();
            assertTrue(firstLum < 60, "frame 0 should be the i=0 (dark) paint, got " + firstLum);
            assertTrue(lastLum > 180, "frame 3 should be the i=3 (light) paint, got " + lastLum);
            assertNotEquals(firstLum, lastLum, "hook-driven frames must differ");
        }
    }

    @Test
    void rectHookOverloadRecordsAndHooklessPathStillWorks() throws Exception {
        assumeTrue(BrewShot.available(), "no local Chrome; skipping");
        Path out = Files.createTempDirectory("brewshot-rect-hook");
        List<Integer> calls = new ArrayList<>();
        try (BrewShot shot = BrewShot.launch(320, 240)) {
            shot.html("<div id=d style='width:50px;height:50px;background:#4e79a7'></div>");
            shot.recordGif(0, 0, 60, 60, 3, 10, 40, calls::add, out.resolve("r.gif"));
            assertEquals(List.of(0, 1, 2), calls);
            // the hookless overloads now ride the same loop (NO_HOOK) — pin one
            shot.recordGif(0, 0, 60, 60, 2, 10, 40, out.resolve("plain.gif"));
            assertTrue(Files.size(out.resolve("plain.gif")) > 100);
        }
    }

    @Test
    void hookPlusClickFilmsAnInputTriggeredAnimation() throws Exception {
        assumeTrue(BrewShot.available(), "no local Chrome; skipping");
        // The consumer story this slice exists for (LatteX L3): an fx that only
        // runs when REAL input pokes it, filmed by nudging it mid-recording.
        Path out = Files.createTempDirectory("brewshot-nudge");
        Path gif = out.resolve("nudged.gif");
        try (BrewShot shot = BrewShot.launch(320, 240)) {
            shot.html("""
                <style>*{margin:0;padding:0}
                  #fx{position:absolute;left:0;top:0;width:60px;height:60px;
                      background:rgb(0, 0, 0)}</style>
                <div id="fx"></div>
                <script>
                  document.getElementById('fx').addEventListener('click', function (e) {
                    if (e.isTrusted) { this.style.background = 'rgb(250, 250, 250)'; }
                  });
                </script>
                """);
            shot.recordGifElement("#fx", 3, 10, 40, 1.0,
                i -> { if (i == 1) { shot.click("#fx"); } }, gif);
        }
        try (var in = javax.imageio.ImageIO.createImageInputStream(gif.toFile())) {
            var reader = javax.imageio.ImageIO.getImageReaders(in).next();
            reader.setInput(in);
            int before = reader.read(0).getRGB(30, 30) & 0xFF;
            int after = reader.read(2).getRGB(30, 30) & 0xFF;
            reader.dispose();
            assertTrue(before < 60, "pre-nudge frame is the resting state, got " + before);
            assertTrue(after > 180, "post-nudge frame shows the click-triggered fx, got " + after);
        }
    }
}
