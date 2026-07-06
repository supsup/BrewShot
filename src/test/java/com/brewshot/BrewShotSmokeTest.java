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
