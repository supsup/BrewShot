package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * End-to-end smoke against a real local Chrome (assume-skips without one):
 * the dual input surface — open(url) and html(source) — plus eval, screenshot,
 * and the GIF recorder. This is the whole public API in one pass.
 */
class BrewShotSmokeTest {

    @Test
    void directHtmlSourceRendersExecutesScriptsAndScreenshots() throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotSmokeTest");
        Path out = Files.createTempDirectory("brewshot-test");
        try (BrewShot shot = BrewShot.launch(640, 480)) {
            shot.html("""
                <h1 id="t">brew</h1>
                <script>document.getElementById('t').textContent += 'shot';</script>
                <div style="width:120px;height:40px;background:#4e79a7"></div>
                """);
            // scripts must EXECUTE (document.write semantics), not just parse
            assertEquals("brewshot", shot.eval("document.getElementById('t').textContent"));

            shot.screenshot(out.resolve("page.png"));
            assertTrue(Files.size(out.resolve("page.png")) > 2_000, "png too small");

            Object rect = shot.eval(
                "(function(){var r=document.querySelector('div').getBoundingClientRect();"
                + "return {x:r.left+pageXOffset,y:r.top+pageYOffset,w:r.width,h:r.height};})()");
            shot.recordGif((Double) MiniJson.get(rect, "x"), (Double) MiniJson.get(rect, "y"),
                (Double) MiniJson.get(rect, "w"), (Double) MiniJson.get(rect, "h"),
                3, 60, out.resolve("clip.gif"));
            assertTrue(Files.size(out.resolve("clip.gif")) > 100, "gif too small");
        }
    }

    @Test
    void elementTargetedCaptureResolvesBoxBySelector() throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotSmokeTest");
        Path out = Files.createTempDirectory("brewshot-element");
        try (BrewShot shot = BrewShot.launch(640, 480)) {
            shot.html("""
                <style>*{margin:0;padding:0}
                  #box{position:absolute;left:40px;top:30px;width:120px;height:80px;background:#333}</style>
                <div id="box"></div>
                """);
            // elementBox folds scroll offset in and matches the CSS-declared geometry
            double[] b = shot.elementBox("#box");
            assertEquals(40.0, b[0], 1.0, "x");
            assertEquals(30.0, b[1], 1.0, "y");
            assertEquals(120.0, b[2], 1.0, "width");
            assertEquals(80.0, b[3], 1.0, "height");
            // selector-based capture delegates to the clip/gif primitives
            assertTrue(shot.screenshotElement("#box", 1.0).length > 100, "element png too small");
            shot.recordGifElement("#box", 3, 20, 1.0, out.resolve("el.gif"));
            assertTrue(Files.size(out.resolve("el.gif")) > 100, "element gif too small");
            // firstFrameDelayMs: hold frame 0, then play the rest — a valid single GIF
            shot.recordGifElement("#box", 4, 20, 60, 400, 1.0, out.resolve("hold.gif"));
            assertTrue(Files.size(out.resolve("hold.gif")) > 100, "first-frame-hold gif too small");
        }
    }

    @Test
    void scrollPanAndDecoupledPlaybackProduceGifs() throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotSmokeTest");
        Path out = Files.createTempDirectory("brewshot-scroll");
        try (BrewShot shot = BrewShot.launch(400, 300)) {
            shot.html("<style>*{margin:0}#tall{height:1600px;"
                + "background:linear-gradient(#fff,#111)}</style><div id=\"tall\"></div>");
            // scroll-pan a tall page into a GIF
            shot.recordGifScroll(6, 2, 80, 0.5, out.resolve("scroll.gif"));
            assertTrue(Files.size(out.resolve("scroll.gif")) > 100, "scroll gif too small");
            // decoupled capture (20ms) vs playback (80ms) speed
            shot.recordGif(0, 0, 200, 200, 4, 20, 80, out.resolve("slow.gif"));
            assertTrue(Files.size(out.resolve("slow.gif")) > 100, "decoupled gif too small");
        }
    }

    @Test
    void screencastStreamCapturesDenserThanThePollRecorder() throws Exception {
        assumeTrue(BrewShot.available(), "no local Chrome; skipping");
        Path out = Files.createTempDirectory("brewshot-stream");
        try (BrewShot shot = BrewShot.launch(480, 360)) {
            // A continuously-compositing page: rAF mutates a transform every frame.
            shot.html("""
                <style>#spin{width:80px;height:80px;background:#e15759;margin:40px}</style>
                <div id="spin"></div>
                <script>
                  let a = 0;
                  (function tick(){
                    a += 7;
                    document.getElementById('spin').style.transform = 'rotate(' + a + 'deg)';
                    requestAnimationFrame(tick);
                  })();
                </script>
                """);
            int durationMs = 1200;
            int streamFrames = shot.recordGifStream(durationMs, 60, out.resolve("stream.gif"));
            assertTrue(Files.size(out.resolve("stream.gif")) > 500, "stream gif too small");

            // Poll equivalent over the SAME duration: 12 shots x 100ms. The stream
            // rides the compositor (~60fps minus ack overhead), so it must sample
            // strictly denser — that is the whole point of the API.
            int pollFrames = durationMs / 100;
            shot.recordGif(0, 0, 200, 200, pollFrames, 100, out.resolve("poll.gif"));
            assertTrue(streamFrames > pollFrames,
                "stream sampled " + streamFrames + " frames vs poll " + pollFrames
                    + " over the same " + durationMs + "ms window");
        }
    }

    @Test
    void recordGifStreamRejectsNonsenseArguments() throws Exception {
        assumeTrue(BrewShot.available(), "no local Chrome; skipping");
        try (BrewShot shot = BrewShot.launch(320, 240)) {
            shot.html("<p>still</p>");
            for (int[] bad : new int[][] {{0, 60, 60, 0}, {500, 0, 60, 0}, {500, 60, 0, 0}, {500, 60, 60, -1}}) {
                try {
                    shot.recordGifStream(bad[0], bad[1], bad[2], bad[3],
                        Files.createTempFile("brewshot-bad", ".gif"));
                    throw new AssertionError("accepted " + java.util.Arrays.toString(bad));
                } catch (IllegalArgumentException expected) {
                    // fail-loud on nonsense knobs, before any protocol traffic
                }
            }
        }
    }

    @Test
    void staleLoadEventCannotSatisfyALaterNavigation() throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotSmokeTest");
        // The review's headline: html() fires a load event; if open() consumed
        // that stale event it would return before ITS page loads and shoot the
        // wrong document. Sequence both on ONE instance and prove open() saw
        // the real page.
        Path page = Files.createTempFile("brewshot-fresh", ".html");
        Files.writeString(page, "<title>the-real-page</title><p>real</p>");
        try (BrewShot shot = BrewShot.launch(640, 480)) {
            shot.html("<title>previous-document</title><p>old</p>");
            shot.open(page.toUri().toString());
            assertEquals("the-real-page", shot.eval("document.title"));
        }
    }

    @Test
    void openFailsFastWithChromesErrorNotATimeout() throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotSmokeTest");
        try (BrewShot shot = BrewShot.launch(640, 480)) {
            long t0 = System.currentTimeMillis();
            IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> shot.open("http://127.0.0.1:1/nothing-listens-here"));
            assertTrue(e.getMessage().contains("net::ERR"),
                "want Chrome's own net error, got: " + e.getMessage());
            assertTrue(System.currentTimeMillis() - t0 < 5_000, "should fail fast, not time out");
        }
    }

    @Test
    void consoleAndErrorsCaptureThePagesVoice() throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotSmokeTest");
        try (BrewShot shot = BrewShot.launch(640, 480)) {
            shot.html("""
                <p>noisy</p>
                <script>
                  console.log('hello', 42);
                  console.error('boom');
                  setTimeout(function () { throw new Error('kaput'); }, 0);
                </script>
                """);
            shot.waitFor("true", 1_000); // one round-trip so the async throw lands
            shot.settle(150);
            assertTrue(shot.console().contains("log: hello 42"),
                "console(): " + shot.console());
            assertTrue(shot.errors().stream().anyMatch(e -> e.contains("boom")),
                "errors(): " + shot.errors());
            assertTrue(shot.errors().stream().anyMatch(e -> e.contains("kaput")),
                "errors(): " + shot.errors());
            // a fresh navigation resets the capture
            shot.html("<p>quiet</p>");
            assertEquals(java.util.List.of(), shot.errors());
        }
    }

    @Test
    void waitForPollsToTruthAndFailsLoud() throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotSmokeTest");
        try (BrewShot shot = BrewShot.launch(640, 480)) {
            shot.html("<div id=d></div><script>setTimeout(function(){"
                + "document.getElementById('d').className='done';}, 300);</script>");
            shot.waitFor("document.querySelector('.done')", 5_000);
            IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> shot.waitFor("document.querySelector('.never')", 400));
            assertTrue(e.getMessage().contains(".never"));
        }
    }

    @Test
    void recordedGifIsAValidAnimationWithTheRightFrameCount() throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotSmokeTest");
        Path out = Files.createTempDirectory("brewshot-gif");
        try (BrewShot shot = BrewShot.launch(640, 480)) {
            shot.html("<div style='width:80px;height:40px;background:#4e79a7'></div>");
            shot.recordGif(0, 0, 100, 60, 4, 50, out.resolve("v.gif"));
        }
        try (var in = javax.imageio.ImageIO.createImageInputStream(
                out.resolve("v.gif").toFile())) {
            var reader = javax.imageio.ImageIO.getImageReaders(in).next();
            reader.setInput(in);
            assertEquals(4, reader.getNumImages(true), "frame count");
            reader.dispose();
        }
    }

    @Test
    void cliEndToEndWaitClipFailJsAndManifest() throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotSmokeTest");
        Path dir = Files.createTempDirectory("brewshot-cli");
        Path page = dir.resolve("p.html");
        Files.writeString(page, """
            <div id=box style='width:120px;height:60px;background:#4e79a7'></div>
            <script>setTimeout(function(){document.body.className='ready';},200);</script>
            """);
        Path png = dir.resolve("shot.png");
        Path json = dir.resolve("shot.json");
        int code = Main.run(new String[] {
            page.toString(), "-o", png.toString(),
            "--wait-js", "document.body.className==='ready'",
            "--clip-js", "(function(){var r=document.getElementById('box')"
                + ".getBoundingClientRect();return {x:r.left+scrollX,y:r.top+scrollY,"
                + "w:r.width,h:r.height};})()",
            "--fail-js", "document.getElementById('box') !== null",
            "--json", json.toString(),
            "--settle", "50",
        });
        assertEquals(0, code);
        assertTrue(Files.size(png) > 200, "clipped png written");
        String manifest = Files.readString(json);
        assertTrue(manifest.contains("\"failJsPassed\": true"), manifest);
        assertTrue(manifest.contains("\"brewshot\": \"" + BrewShot.VERSION), manifest);

        // fail-js false -> exit 4, PNG still written
        Path png2 = dir.resolve("shot2.png");
        int code2 = Main.run(new String[] {
            page.toString(), "-o", png2.toString(),
            "--fail-js", "document.querySelector('.does-not-exist') !== null",
            "--settle", "50",
        });
        assertEquals(4, code2);
        assertTrue(Files.size(png2) > 200, "failure still carries eyes");
    }

    /** IHDR width/height straight from the PNG bytes — no AWT decode needed. */
    private static int[] pngDims(byte[] png) {
        return new int[] {
            ((png[16] & 0xFF) << 24) | ((png[17] & 0xFF) << 16) | ((png[18] & 0xFF) << 8) | (png[19] & 0xFF),
            ((png[20] & 0xFF) << 24) | ((png[21] & 0xFF) << 16) | ((png[22] & 0xFF) << 8) | (png[23] & 0xFF)};
    }

    @Test
    void scaleSemanticsArePinned_outputPixelsEqualRectTimesScale() throws Exception {
        // The durable form of the sirentide #121 spike: the clip rect is CSS px, the output
        // bitmap is EXACTLY rect x scale (Chrome re-renders the region — a true re-raster,
        // not an upscale). If this ever drifts, every vendored consumer's crispness story
        // breaks silently, so pin the arithmetic.
        TestChrome.requireChromeOrLoudSkip("BrewShotSmokeTest");
        try (BrewShot shot = BrewShot.launch(640, 480)) {
            shot.html("""
                <style>*{margin:0;padding:0}</style>
                <svg id=v width="360" height="140" xmlns="http://www.w3.org/2000/svg">
                  <rect width="360" height="140" fill="#fff"/>
                  <text x="20" y="80" font-family="Georgia" font-size="40">S = Σ sₖ</text>
                  <line x1="0" y1="0" x2="360" y2="140" stroke="#333" stroke-width="1"/>
                </svg>
                """);
            int[] at1 = pngDims(shot.screenshotElement("#v", 1.0));
            int[] at3 = pngDims(shot.screenshotElement("#v", 3.0));
            assertEquals(360, at1[0], "1x width == CSS px");
            assertEquals(140, at1[1], "1x height == CSS px");
            assertEquals(1080, at3[0], "3x width == rect x scale, exactly");
            assertEquals(420, at3[1], "3x height == rect x scale, exactly");

            // paddingPx inflates the rect BEFORE scale: (360+2*10) x (140+2*10), then x2.
            int[] padded = pngDims(shot.screenshotElement("#v", 2.0, 10));
            assertEquals(760, padded[0], "padded width = (360+20) x 2");
            assertEquals(320, padded[1], "padded height = (140+20) x 2");
        }
    }

    @Test
    void cliClipSelectorAndScaleShootTheElementCrisp() throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotSmokeTest");
        Path dir = Files.createTempDirectory("brewshot-clip-selector");
        Path page = dir.resolve("p.html");
        Files.writeString(page, """
            <style>*{margin:0;padding:0}
              #card{position:absolute;left:10px;top:20px;width:200px;height:100px;background:#4e79a7}</style>
            <div id=card></div>
            """);
        Path png = dir.resolve("card.png");
        int code = Main.run(new String[] {
            page.toString(), "-o", png.toString(),
            "--clip-selector", "#card", "--scale", "3", "--settle", "50",
        });
        assertEquals(0, code);
        int[] dims = pngDims(Files.readAllBytes(png));
        assertEquals(600, dims[0], "CLI: width = 200 x 3");
        assertEquals(300, dims[1], "CLI: height = 100 x 3");

        // --clip-padding inflates the rect (200+2*5) x (100+2*5) at scale 1
        Path padded = dir.resolve("padded.png");
        assertEquals(0, Main.run(new String[] {
            page.toString(), "-o", padded.toString(),
            "--clip-selector", "#card", "--clip-padding", "5", "--settle", "50",
        }));
        int[] pd = pngDims(Files.readAllBytes(padded));
        assertEquals(210, pd[0], "CLI: padded width");
        assertEquals(110, pd[1], "CLI: padded height");

        // no matching element -> page-content failure: exit 1, loud, no PNG pretending
        assertEquals(1, Main.run(new String[] {
            page.toString(), "-o", dir.resolve("none.png").toString(),
            "--clip-selector", "#does-not-exist", "--settle", "50",
        }));
    }

    @Test
    void opensAFileUrlAddress() throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotSmokeTest");
        Path page = Files.createTempFile("brewshot-open", ".html");
        Files.writeString(page, "<title>opened-by-address</title><p>hi</p>");
        try (BrewShot shot = BrewShot.launch(640, 480)) {
            shot.open(page.toUri().toString());
            assertEquals("opened-by-address", shot.eval("document.title"));
        }
    }
}
