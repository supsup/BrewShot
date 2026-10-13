package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code --size auto} end to end against a real local Chrome (plan 982dcab9 acceptance).
 * The browser-free decision is pinned in {@link SvgIntrinsicSizeTest} and
 * {@link MainCliTest}; these prove the chosen viewport is what Chrome actually renders:
 * the PNG has the SVG's size and the drawing reaches every edge (no margin, no clip).
 */
class MainCliSvgAutoSizeTest {


    /** A drawing whose own coordinate space is filled edge to edge with one colour. */
    private static String filledSvg(String rootAttributes, int userWidth, int userHeight) {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" " + rootAttributes + ">"
            + "<rect x=\"0\" y=\"0\" width=\"" + userWidth + "\" height=\"" + userHeight
            + "\" fill=\"#4e79a7\"/></svg>";
    }

    private static Object[] runCapturingStderr(String... args) throws Exception {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        PrintStream original = System.err;
        int code;
        try {
            System.setErr(new PrintStream(errors));
            code = Main.run(args);
        } finally {
            System.setErr(original);
        }
        return new Object[] {code, errors.toString()};
    }

    /**
     * The drawing's #4e79a7 fill, by channel shape rather than exact bytes: a host colour
     * profile may shift the values, but never into the white of an empty canvas.
     */
    private static boolean isFill(BufferedImage image, int x, int y) {
        int rgb = image.getRGB(x, y);
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        return red < 150 && blue > 110 && blue > red + 40 && green < 200;
    }

    private static boolean isEmptyCanvas(BufferedImage image, int x, int y) {
        int rgb = image.getRGB(x, y);
        return ((rgb >> 16) & 0xFF) > 240 && ((rgb >> 8) & 0xFF) > 240 && (rgb & 0xFF) > 240;
    }

    private static void assertFilledEdgeToEdge(BufferedImage image, String what) {
        int right = image.getWidth() - 1;
        int bottom = image.getHeight() - 1;
        // One pixel in from each edge: an exact-fit edge may legitimately anti-alias.
        assertTrue(isFill(image, 1, 1), what + " top-left is drawing, not margin");
        assertTrue(isFill(image, right - 1, 1), what + " top-right is drawing, not margin");
        assertTrue(isFill(image, 1, bottom - 1), what + " bottom-left is drawing, not margin");
        assertTrue(isFill(image, right - 1, bottom - 1),
            what + " bottom-right is drawing, not margin");
        assertTrue(isFill(image, right / 2, bottom / 2), what + " centre is drawing");
    }

    @Test
    @SuppressWarnings("unchecked")
    void svgWithWidthAndHeightRendersAtExactlyThatSize(@TempDir Path directory)
            throws Exception {
        TestChrome.requireChromeOrLoudSkip("MainCliSvgAutoSizeTest");
        Path svg = directory.resolve("diagram.svg");
        Files.writeString(svg, filledSvg("width=\"1000\" height=\"780\"", 1000, 780));

        // No --size at all: the acceptance case.
        Path out = directory.resolve("diagram.png");
        Path receipt = directory.resolve("diagram.json");
        Object[] result = runCapturingStderr(
            svg.toString(), "-o", out.toString(), "--json", receipt.toString());
        assertEquals(0, result[0], (String) result[1]);
        assertTrue(((String) result[1]).contains("viewport 1000x780"), (String) result[1]);
        BufferedImage image = ImageIO.read(out.toFile());
        assertEquals(1000, image.getWidth());
        assertEquals(780, image.getHeight());
        assertFilledEdgeToEdge(image, "1000x780 svg");
        Map<String, Object> root =
            (Map<String, Object>) MiniJson.parse(Files.readString(receipt));
        assertEquals("1000x780", root.get("viewport"));
        assertEquals("svg-width-height", root.get("viewportSource"));

        // --size auto spells the same thing; --dpr multiplies the pixels, not the layout.
        Path retina = directory.resolve("diagram-2x.png");
        assertEquals(0, Main.run(new String[] {
            svg.toString(), "--size", "auto", "--dpr", "2", "-o", retina.toString()}));
        BufferedImage doubled = ImageIO.read(retina.toFile());
        assertEquals(2000, doubled.getWidth());
        assertEquals(1560, doubled.getHeight());
        assertFilledEdgeToEdge(doubled, "1000x780 svg at dpr 2");
    }

    @Test
    void svgWithOnlyAViewBoxRendersAtTheViewBoxSize(@TempDir Path directory)
            throws Exception {
        TestChrome.requireChromeOrLoudSkip("MainCliSvgAutoSizeTest");
        Path svg = directory.resolve("boxed.svg");
        Files.writeString(svg, filledSvg("viewBox=\"0 0 640 360\"", 640, 360));
        Path out = directory.resolve("boxed.png");
        Object[] result = runCapturingStderr(svg.toString(), "-o", out.toString());
        assertEquals(0, result[0], (String) result[1]);
        assertTrue(((String) result[1]).contains("viewport 640x360 from the root viewBox"),
            (String) result[1]);
        BufferedImage image = ImageIO.read(out.toFile());
        assertEquals(640, image.getWidth());
        assertEquals(360, image.getHeight());
        assertFilledEdgeToEdge(image, "viewBox-only svg");
    }

    @Test
    void svgWithNoSizeFallsBackToTheDefaultAndSaysSoOnStderr(@TempDir Path directory)
            throws Exception {
        TestChrome.requireChromeOrLoudSkip("MainCliSvgAutoSizeTest");
        Path svg = directory.resolve("bare.svg");
        Files.writeString(svg, filledSvg("", 100, 100));
        Path out = directory.resolve("bare.png");
        Object[] result = runCapturingStderr(svg.toString(), "-o", out.toString());
        assertEquals(0, result[0], (String) result[1]);
        assertTrue(((String) result[1]).contains("SVG size not determined")
            && ((String) result[1]).contains("using the default 1280x900 viewport"),
            (String) result[1]);
        BufferedImage image = ImageIO.read(out.toFile());
        assertEquals(1280, image.getWidth());
        assertEquals(900, image.getHeight());
    }

    @Test
    void explicitSizeOnAnSvgIsHonouredUnchanged(@TempDir Path directory) throws Exception {
        TestChrome.requireChromeOrLoudSkip("MainCliSvgAutoSizeTest");
        Path svg = directory.resolve("diagram.svg");
        Files.writeString(svg, filledSvg("width=\"200\" height=\"100\"", 200, 100));
        Path out = directory.resolve("explicit.png");
        Object[] result = runCapturingStderr(
            svg.toString(), "--size", "800x600", "-o", out.toString());
        assertEquals(0, result[0], (String) result[1]);
        org.junit.jupiter.api.Assertions.assertFalse(
            ((String) result[1]).contains("from the root"),
            "an explicit --size must not print an auto-size line: " + result[1]);
        BufferedImage image = ImageIO.read(out.toFile());
        assertEquals(800, image.getWidth());
        assertEquals(600, image.getHeight());
        // The 200x100 drawing sits at the origin of the larger canvas the caller asked for.
        assertTrue(isFill(image, 10, 10), "drawing at the origin");
        assertTrue(isEmptyCanvas(image, 700, 500), "canvas beyond the drawing");
    }
}
