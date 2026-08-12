package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Raw-CDP gate kept independent of the production preview API so it cannot prove itself. */
class BrewShotVisionDeficiencySpikeTest {

    private static final String SWATCH_PAGE = """
        <!doctype html>
        <style>
          html, body { margin: 0; width: 320px; background: #fff; }
          body { height: 1200px; }
          .swatch { position: absolute; width: 80px; height: 80px; }
          #top-red { left: 20px; top: 20px; background: #f00; }
          #top-green { left: 120px; top: 20px; background: #0f0; }
          #top-blue { left: 220px; top: 20px; background: #00f; }
          #below-red { left: 20px; top: 820px; background: #f00; }
          #below-green { left: 120px; top: 820px; background: #0f0; }
          #below-blue { left: 220px; top: 820px; background: #00f; }
        </style>
        <div id="top-red" class="swatch"></div>
        <div id="top-green" class="swatch"></div>
        <div id="top-blue" class="swatch"></div>
        <div id="below-red" class="swatch"></div>
        <div id="below-green" class="swatch"></div>
        <div id="below-blue" class="swatch"></div>
        """;

    @Test
    void rawCdpTransformSurvivesViewportFullPageAndBelowFoldClip() throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotVisionDeficiencySpikeTest");

        try (BrewShot shot = BrewShot.launch(320, 200)) {
            shot.html(SWATCH_PAGE);

            setVisionDeficiency(shot, "none");
            BufferedImage controlViewport = capture(shot,
                "{\"format\":\"png\",\"captureBeyondViewport\":false}");

            setVisionDeficiency(shot, "achromatopsia");
            BufferedImage viewport = capture(shot,
                "{\"format\":\"png\",\"captureBeyondViewport\":false}");
            BufferedImage fullPage = capture(shot,
                "{\"format\":\"png\",\"captureBeyondViewport\":true}");
            BufferedImage belowFold = capture(shot,
                "{\"format\":\"png\",\"captureBeyondViewport\":true,\"clip\":{"
                    + "\"x\":0,\"y\":800,\"width\":320,\"height\":200,\"scale\":1}}");

            assertNotEquals(rgb(controlViewport, 60, 60), rgb(viewport, 60, 60),
                "must-fire control: achromatopsia must alter the top red swatch");
            assertGrayscale(viewport, 60, 60, "viewport top red");
            assertGrayscale(viewport, 160, 60, "viewport top green");
            assertGrayscale(viewport, 260, 60, "viewport top blue");
            assertGrayscale(fullPage, 60, 860, "full-page below-fold red");
            assertGrayscale(fullPage, 160, 860, "full-page below-fold green");
            assertGrayscale(fullPage, 260, 860, "full-page below-fold blue");
            assertGrayscale(belowFold, 60, 60, "direct below-fold clip red");
            assertGrayscale(belowFold, 160, 60, "direct below-fold clip green");
            assertGrayscale(belowFold, 260, 60, "direct below-fold clip blue");

            assertTrue(fullPage.getHeight() >= 1200,
                "captureBeyondViewport must include the deterministic below-fold subject");
            assertEquals(200, belowFold.getHeight(), "clip height must remain explicit");
        }
    }

    private static void setVisionDeficiency(BrewShot shot, String type) throws Exception {
        rawCommand(shot, "Emulation.setEmulatedVisionDeficiency",
            "{\"type\":\"" + type + "\"}");
    }

    private static BufferedImage capture(BrewShot shot, String params) throws Exception {
        Map<String, Object> result = rawCommand(shot, "Page.captureScreenshot", params);
        byte[] png = Base64.getDecoder().decode((String) result.get("data"));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertTrue(image != null, "Chrome capture must decode as PNG");
        return image;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rawCommand(BrewShot shot, String method, String params)
            throws Exception {
        Method command = BrewShot.class.getDeclaredMethod("command", String.class, String.class);
        command.setAccessible(true);
        try {
            return (Map<String, Object>) command.invoke(shot, method, params);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw failure;
        }
    }

    private static int rgb(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) & 0xFFFFFF;
    }

    private static void assertGrayscale(BufferedImage image, int x, int y, String subject) {
        int rgb = rgb(image, x, y);
        int red = (rgb >>> 16) & 0xFF;
        int green = (rgb >>> 8) & 0xFF;
        int blue = rgb & 0xFF;
        assertEquals(red, green, subject + " must have equal red and green channels");
        assertEquals(green, blue, subject + " must have equal green and blue channels");
    }
}
