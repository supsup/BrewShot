package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Capture-scoped PNG vision previews and their fail-closed CLI boundary. */
class BrewShotVisionDeficiencyTest {

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
    void libraryPreviewIsPngOnlyCaptureScopedAndClearsAfterSuccess(@TempDir Path dir)
            throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotVisionDeficiencyTest");
        try (BrewShot shot = BrewShot.launch(320, 200)) {
            shot.html(SWATCH_PAGE);
            BufferedImage control = decode(shot.screenshotClip(0, 0, 320, 200));
            BufferedImage explicitNone = decode(shot.screenshotClip(
                0, 0, 320, 200, 1, BrewShot.VisionDeficiency.NONE));
            assertRasterEquals(control, explicitNone,
                "explicit none must be pixel-identical to an ordinary capture");

            BufferedImage achromatopsia = decode(shot.screenshotClip(
                0, 0, 320, 200, 1, BrewShot.VisionDeficiency.ACHROMATOPSIA));
            assertNotEquals(rgb(control, 60, 60), rgb(achromatopsia, 60, 60),
                "must-fire control: transformed red must differ");
            assertGrayscale(achromatopsia, 60, 60, "top red");
            assertGrayscale(achromatopsia, 160, 60, "top green");
            assertGrayscale(achromatopsia, 260, 60, "top blue");

            BufferedImage ordinaryAfter = decode(shot.screenshotClip(0, 0, 320, 200));
            assertRasterEquals(control, ordinaryAfter,
                "the next ordinary capture must prove the preview was cleared");

            Path transformedFullPage = dir.resolve("full-achromatopsia.png");
            shot.screenshot(transformedFullPage, BrewShot.VisionDeficiency.ACHROMATOPSIA);
            BufferedImage fullPage = ImageIO.read(transformedFullPage.toFile());
            assertGrayscale(fullPage, 60, 860, "full-page below-fold red");
            assertGrayscale(fullPage, 160, 860, "full-page below-fold green");
            assertGrayscale(fullPage, 260, 860, "full-page below-fold blue");

            BufferedImage belowFold = decode(shot.screenshotClip(
                0, 800, 320, 200, 1, BrewShot.VisionDeficiency.ACHROMATOPSIA));
            assertGrayscale(belowFold, 60, 60, "direct below-fold clip red");
            assertGrayscale(belowFold, 160, 60, "direct below-fold clip green");
            assertGrayscale(belowFold, 260, 60, "direct below-fold clip blue");

            for (BrewShot.VisionDeficiency deficiency : BrewShot.VisionDeficiency.values()) {
                if (deficiency == BrewShot.VisionDeficiency.NONE) {
                    continue;
                }
                BufferedImage preview = decode(
                    shot.screenshotClip(0, 0, 320, 200, 1, deficiency));
                assertFalse(Arrays.equals(raster(control), raster(preview)),
                    deficiency.cdpValue() + " must visibly change the controlled RGB fixture");
            }
        }
    }

    @Test
    void captureFailureStillClearsBeforeTheNextOrdinaryPng() throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotVisionDeficiencyTest");
        try (BrewShot shot = BrewShot.launch(320, 200)) {
            shot.html(SWATCH_PAGE);
            BufferedImage control = decode(shot.screenshotClip(0, 0, 320, 200));

            IOException failure = assertThrows(IOException.class,
                () -> shot.captureWithVisionDeficiency(
                    BrewShot.VisionDeficiency.ACHROMATOPSIA,
                    () -> { throw new IOException("synthetic capture failure"); }));
            assertEquals("synthetic capture failure", failure.getMessage());

            BufferedImage ordinaryAfter = decode(shot.screenshotClip(0, 0, 320, 200));
            assertRasterEquals(control, ordinaryAfter,
                "failure cleanup must prevent a stale preview leaking into the next capture");
        }
    }

    @Test
    void cliRefusesUnknownValuesAndEveryUnsupportedOutputFamilyBeforeWriting(
            @TempDir Path dir) throws Exception {
        Path page = dir.resolve("swatches.html");
        Files.writeString(page, SWATCH_PAGE);

        Path unknown = dir.resolve("unknown.png");
        assertEquals(2, Main.run(new String[] {
            page.toString(), "-o", unknown.toString(),
            "--vision-deficiency", "Achromatopsia",
        }));
        assertFalse(Files.exists(unknown));

        Path jpeg = dir.resolve("preview.jpg");
        assertEquals(2, Main.run(new String[] {
            page.toString(), "-o", jpeg.toString(),
            "--vision-deficiency", "deuteranopia",
        }));
        assertFalse(Files.exists(jpeg));

        Path pdf = dir.resolve("preview.pdf");
        assertEquals(2, Main.run(new String[] {
            page.toString(), "-o", pdf.toString(),
            "--vision-deficiency", "protanopia",
        }));
        assertFalse(Files.exists(pdf));

        Path gif = dir.resolve("preview.gif");
        assertEquals(2, Main.run(new String[] {
            page.toString(), "-o", gif.toString(), "--gif", "1",
            "--vision-deficiency", "tritanopia",
        }));
        assertFalse(Files.exists(gif));
    }

    @Test
    void cliReceiptSaysRequestedAndCommandAcceptedButNeverApplied(@TempDir Path dir)
            throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotVisionDeficiencyTest");
        Path page = dir.resolve("swatches.html");
        Files.writeString(page, SWATCH_PAGE);
        Path preview = dir.resolve("preview.png");
        Path receipt = dir.resolve("preview.json");

        assertEquals(0, Main.run(new String[] {
            page.toString(), "-o", preview.toString(), "--settle", "1",
            "--vision-deficiency", "achromatopsia", "--json", receipt.toString(),
        }));
        assertGrayscale(ImageIO.read(preview.toFile()), 60, 60, "CLI preview red");
        String json = Files.readString(receipt);
        assertTrue(json.contains("\"kind\": \"emulated-preview\""));
        assertTrue(json.contains("\"requested\": \"achromatopsia\""));
        assertTrue(json.contains("\"commandAccepted\": true"));
        assertFalse(json.contains("\"applied\""),
            "a successful void CDP response is not artifact-level applied proof");

        Path ordinary = dir.resolve("ordinary.png");
        Path ordinaryReceipt = dir.resolve("ordinary.json");
        assertEquals(0, Main.run(new String[] {
            page.toString(), "-o", ordinary.toString(), "--settle", "1",
            "--json", ordinaryReceipt.toString(),
        }));
        assertFalse(Files.readString(ordinaryReceipt).contains("visionDeficiency"),
            "omission must preserve the legacy receipt shape");
    }

    @Test
    void enumParserIsStrictAndExcludesBestEffortCdpModes() {
        assertEquals(BrewShot.VisionDeficiency.DEUTERANOPIA,
            BrewShot.VisionDeficiency.fromCdpValue("deuteranopia"));
        for (String rejected : new String[] {
            "Deuteranopia", "blurredVision", "reducedContrast", "unknown", "",
        }) {
            assertThrows(IllegalArgumentException.class,
                () -> BrewShot.VisionDeficiency.fromCdpValue(rejected));
        }
    }

    private static BufferedImage decode(byte[] png) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertTrue(image != null, "capture must decode as PNG");
        return image;
    }

    private static int rgb(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) & 0xFFFFFF;
    }

    private static int[] raster(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    private static void assertRasterEquals(BufferedImage expected, BufferedImage actual,
                                           String message) {
        assertEquals(expected.getWidth(), actual.getWidth(), message + " (width)");
        assertEquals(expected.getHeight(), actual.getHeight(), message + " (height)");
        assertArrayEquals(raster(expected), raster(actual), message);
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
