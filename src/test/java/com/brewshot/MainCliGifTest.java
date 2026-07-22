package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The CLI GIF lane end-to-end against a real local Chrome (assume-skips without
 * one): {@code --gif} / {@code --gif-delay} / {@code --gif-element} must reach the
 * {@code recordGif*} family and write a real GIF — plan 6cc2d9ec closed the gap
 * where the whole recorder family was library-only and {@code java -jar} users had
 * zero CLI access. Runs on the test JVM (jar path), where ImageIO is available.
 */
class MainCliGifTest {

    @Test
    void gifFlagRecordsAFullPageGifWithDefaultGifOutName() throws Exception {
        TestChrome.requireChromeOrLoudSkip("MainCliGifTest");
        Path dir = Files.createTempDirectory("brewshot-cli-gif");
        Path page = dir.resolve("page.html");
        Files.writeString(page, """
            <div style="width:120px;height:40px;background:#4e79a7">brew</div>
            """);
        Path out = dir.resolve("page.gif");
        int code = Main.run(new String[] {
            page.toString(), "--gif", "3", "--gif-delay", "20", "-o", out.toString()});
        assertEquals(0, code, "gif shoot exits 0");
        byte[] bytes = Files.readAllBytes(out);
        assertTrue(bytes.length > 100, "gif has real frames, got " + bytes.length + " bytes");
        assertEquals("GIF89a", new String(bytes, 0, 6, java.nio.charset.StandardCharsets.US_ASCII),
            "output is a real GIF, not misnamed PNG bytes");
    }

    @Test
    void gifElementFilmsJustTheElementAndAMissingSelectorIsExitOne() throws Exception {
        TestChrome.requireChromeOrLoudSkip("MainCliGifTest");
        Path dir = Files.createTempDirectory("brewshot-cli-gif");
        Path page = dir.resolve("page.html");
        Files.writeString(page, """
            <div style="width:900px;height:600px">
              <span id="fx" style="display:inline-block;width:80px;height:30px;background:#e15759"></span>
            </div>
            """);
        // MIXED FIXTURE: the positive control (the selector matches → a real element
        // GIF) beside the negative (no match → exit 1, --clip-selector's posture),
        // so a green run can never mean "the selector path is just dead code".
        Path out = dir.resolve("fx.gif");
        assertEquals(0, Main.run(new String[] {
            page.toString(), "--gif", "3", "--gif-delay", "20", "--gif-element", "#fx",
            "-o", out.toString()}));
        byte[] bytes = Files.readAllBytes(out);
        assertEquals("GIF89a", new String(bytes, 0, 6, java.nio.charset.StandardCharsets.US_ASCII));
        // The element box is tiny vs the 1280x900 default viewport: an element-scoped
        // GIF of a flat-colour span must be far smaller than a full-page recording.
        Path full = dir.resolve("full.gif");
        assertEquals(0, Main.run(new String[] {
            page.toString(), "--gif", "3", "--gif-delay", "20", "-o", full.toString()}));
        assertTrue(Files.size(out) < Files.size(full),
            "element gif (" + Files.size(out) + "b) smaller than full-page gif ("
                + Files.size(full) + "b) — the selector actually clipped");

        assertEquals(1, Main.run(new String[] {
            page.toString(), "--gif", "3", "--gif-element", "#no-such-element",
            "-o", dir.resolve("miss.gif").toString()}),
            "a non-matching --gif-element is a page-content failure: exit 1, loud");
    }
}
