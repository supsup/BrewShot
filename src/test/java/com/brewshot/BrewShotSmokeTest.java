package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
        assumeTrue(BrewShot.available(), "no local Chrome; skipping");
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
        assumeTrue(BrewShot.available(), "no local Chrome; skipping");
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
        }
    }

    @Test
    void staleLoadEventCannotSatisfyALaterNavigation() throws Exception {
        assumeTrue(BrewShot.available(), "no local Chrome; skipping");
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
        assumeTrue(BrewShot.available(), "no local Chrome; skipping");
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
        assumeTrue(BrewShot.available(), "no local Chrome; skipping");
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
        assumeTrue(BrewShot.available(), "no local Chrome; skipping");
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
        assumeTrue(BrewShot.available(), "no local Chrome; skipping");
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
        assumeTrue(BrewShot.available(), "no local Chrome; skipping");
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

    @Test
    void opensAFileUrlAddress() throws Exception {
        assumeTrue(BrewShot.available(), "no local Chrome; skipping");
        Path page = Files.createTempFile("brewshot-open", ".html");
        Files.writeString(page, "<title>opened-by-address</title><p>hi</p>");
        try (BrewShot shot = BrewShot.launch(640, 480)) {
            shot.open(page.toUri().toString());
            assertEquals("opened-by-address", shot.eval("document.title"));
        }
    }
}
