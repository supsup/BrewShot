package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * B6 — JPEG screenshot option. The quality-range validation is Chrome-free
 * (unit test of {@code captureFormatParams}); the end-to-end magic-byte check is
 * Chrome-gated via the loud-skip helper.
 */
class BrewShotJpegTest {

    // ---- Chrome-free: format+quality param building & validation -----------

    @Test
    void pngFormatParamsIgnoreQuality() {
        assertEquals("\"format\":\"png\"",
            BrewShot.captureFormatParams(BrewShot.ImageFormat.PNG, 0));
        assertEquals("\"format\":\"png\"",
            BrewShot.captureFormatParams(BrewShot.ImageFormat.PNG, 999));
    }

    @Test
    void jpegFormatParamsThreadQualityIntoCdp() {
        assertEquals("\"format\":\"jpeg\",\"quality\":1",
            BrewShot.captureFormatParams(BrewShot.ImageFormat.JPEG, 1));
        assertEquals("\"format\":\"jpeg\",\"quality\":80",
            BrewShot.captureFormatParams(BrewShot.ImageFormat.JPEG, 80));
        assertEquals("\"format\":\"jpeg\",\"quality\":100",
            BrewShot.captureFormatParams(BrewShot.ImageFormat.JPEG, 100));
    }

    @Test
    void jpegQualityOutOfRangeFailsLoud() {
        assertThrows(IllegalArgumentException.class,
            () -> BrewShot.captureFormatParams(BrewShot.ImageFormat.JPEG, 0));
        assertThrows(IllegalArgumentException.class,
            () -> BrewShot.captureFormatParams(BrewShot.ImageFormat.JPEG, 101));
        assertThrows(IllegalArgumentException.class,
            () -> BrewShot.captureFormatParams(BrewShot.ImageFormat.JPEG, -5));
    }

    // ---- Chrome-gated: a real JPEG comes out of Chrome ---------------------

    @Test
    void jpegScreenshotStartsWithJpegMagicBytes() throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotJpegTest");
        Path out = Files.createTempDirectory("brewshot-jpeg");
        try (BrewShot shot = BrewShot.launch(640, 480)) {
            shot.html("""
                <style>*{margin:0;padding:0}</style>
                <div style="width:400px;height:300px;
                  background:linear-gradient(135deg,#4e79a7,#f28e2b)"></div>
                """);

            // full-page JPEG to a file
            Path jpg = out.resolve("page.jpg");
            shot.screenshot(jpg, BrewShot.ImageFormat.JPEG, 80);
            byte[] bytes = Files.readAllBytes(jpg);
            assertTrue(bytes.length > 500, "jpeg too small: " + bytes.length);
            assertEquals((byte) 0xFF, bytes[0], "JPEG magic byte 0");
            assertEquals((byte) 0xD8, bytes[1], "JPEG magic byte 1");

            // clipped JPEG as bytes
            byte[] clip = shot.screenshotClip(0, 0, 200, 150, 1.0,
                BrewShot.ImageFormat.JPEG, 60);
            assertTrue(clip.length > 200, "clip jpeg too small: " + clip.length);
            assertEquals((byte) 0xFF, clip[0], "clip JPEG magic byte 0");
            assertEquals((byte) 0xD8, clip[1], "clip JPEG magic byte 1");

            // the PNG default path is unchanged: PNG signature (0x89 'P' 'N' 'G')
            Path png = out.resolve("page.png");
            shot.screenshot(png);
            byte[] pngBytes = Files.readAllBytes(png);
            assertEquals((byte) 0x89, pngBytes[0], "PNG default still PNG");
            assertEquals((byte) 'P', pngBytes[1], "PNG default still PNG");
        }
    }
}
