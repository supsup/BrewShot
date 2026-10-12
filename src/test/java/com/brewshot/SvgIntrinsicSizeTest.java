package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The browser-free SVG size reader behind {@code --size auto} (plan 982dcab9). Every
 * expectation is written from the SVG/CSS definitions (96 CSS px per inch, the root
 * element of a standalone document filling the viewport when it states no size), not
 * read back from the implementation.
 */
class SvgIntrinsicSizeTest {

    private static final String NS = "xmlns=\"http://www.w3.org/2000/svg\"";

    private static SvgIntrinsicSize.Size size(String svg) throws Exception {
        return SvgIntrinsicSize.parse(svg);
    }

    private static String refusal(String svg) {
        return assertThrows(SvgIntrinsicSize.Undetermined.class,
            () -> SvgIntrinsicSize.parse(svg)).getMessage();
    }

    @Test
    void widthAndHeightWinOverTheViewBox() throws Exception {
        SvgIntrinsicSize.Size plain = size("<svg " + NS + " width=\"1000\" height=\"780\"/>");
        assertEquals(1000, plain.width());
        assertEquals(780, plain.height());
        assertEquals(SvgIntrinsicSize.Source.WIDTH_HEIGHT, plain.source());
        assertEquals("svg-width-height", plain.source().label());

        SvgIntrinsicSize.Size both = size(
            "<svg " + NS + " viewBox=\"0 0 10 10\" width='320px' height='200px'></svg>");
        assertEquals(320, both.width());
        assertEquals(200, both.height());
        assertEquals(SvgIntrinsicSize.Source.WIDTH_HEIGHT, both.source());
    }

    @Test
    void absoluteUnitsConvertAtNinetySixPixelsPerInchAndRoundUp() throws Exception {
        // 2in = 192px, 72pt = 96px.
        SvgIntrinsicSize.Size inches = size("<svg width=\"2in\" height=\"72pt\"/>");
        assertEquals(192, inches.width());
        assertEquals(96, inches.height());
        // 6pc = 96px, 25.4mm = 96px (the epsilon must not turn 96.0000000001 into 97).
        SvgIntrinsicSize.Size picas = size("<svg width=\"6pc\" height=\"25.4mm\"/>");
        assertEquals(96, picas.width());
        assertEquals(96, picas.height());
        // A4: 210mm = 793.70px -> 794, 297mm = 1122.52px -> 1123. 2.54cm = 96px.
        SvgIntrinsicSize.Size a4 = size("<svg width=\"210mm\" height=\"297mm\"/>");
        assertEquals(794, a4.width());
        assertEquals(1123, a4.height());
        assertEquals(96, size("<svg width=\"2.54cm\" height=\"1in\"/>").width());
        // Fractions round UP so the drawing is never clipped; units are case-insensitive.
        SvgIntrinsicSize.Size fraction = size("<svg width=\"100.2\" height=\"50.5PX\"/>");
        assertEquals(101, fraction.width());
        assertEquals(51, fraction.height());
        // Exponent spelling is a number, not the unit "e2".
        assertEquals(150, size("<svg width=\"1.5e2\" height=\"10\"/>").width());
    }

    @Test
    void viewBoxAloneGivesItsWidthAndHeight() throws Exception {
        SvgIntrinsicSize.Size spaced = size("<svg " + NS + " viewBox=\"0 0 640 360\"/>");
        assertEquals(640, spaced.width());
        assertEquals(360, spaced.height());
        assertEquals(SvgIntrinsicSize.Source.VIEWBOX, spaced.source());
        assertEquals("svg-viewbox", spaced.source().label());

        // Commas, a non-zero origin, and width/height that mean "fill the viewport".
        SvgIntrinsicSize.Size filled = size(
            "<svg viewBox=\"-10,-20, 300 , 150\" width=\"100%\" height=\"auto\"/>");
        assertEquals(300, filled.width());
        assertEquals(150, filled.height());
        assertEquals(SvgIntrinsicSize.Source.VIEWBOX, filled.source());
    }

    @Test
    void oneDimensionPlusViewBoxDerivesTheOtherFromTheAspectRatio() throws Exception {
        SvgIntrinsicSize.Size fromWidth = size("<svg width=\"1000\" viewBox=\"0 0 100 78\"/>");
        assertEquals(1000, fromWidth.width());
        assertEquals(780, fromWidth.height());
        assertEquals(SvgIntrinsicSize.Source.WIDTH_VIEWBOX_ASPECT, fromWidth.source());

        SvgIntrinsicSize.Size fromHeight = size("<svg height=\"50\" viewBox=\"0 0 400 100\"/>");
        assertEquals(200, fromHeight.width());
        assertEquals(50, fromHeight.height());
        assertEquals(SvgIntrinsicSize.Source.HEIGHT_VIEWBOX_ASPECT, fromHeight.source());
    }

    @Test
    void prologCommentsDoctypeAndNamespacePrefixAreSkipped() throws Exception {
        String document = """
            <?xml version="1.0" encoding="UTF-8" standalone="no"?>
            <!-- width="1" height="1" <svg> in a comment must not be read -->
            <?xml-stylesheet href="style.css" type="text/css"?>
            <!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN"
              "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd" [
              <!-- it's a subset comment with an apostrophe and a > -->
              <!ENTITY ns_svg "http://www.w3.org/2000/svg">
            ]>
            <svg:svg xmlns:svg="http://www.w3.org/2000/svg"
                 data-note = 'a > inside a "quoted" value'
                 width = "480"
                 height='270' >
              <svg:rect width="9999" height="9999"/>
            </svg:svg>
            """;
        SvgIntrinsicSize.Size parsed = size(document);
        assertEquals(480, parsed.width());
        assertEquals(270, parsed.height());
    }

    @Test
    void aNestedSvgNeverStandsInForTheRoot() {
        String message = refusal(
            "<svg " + NS + "><svg width=\"300\" height=\"200\" viewBox=\"0 0 3 2\"/></svg>");
        assertTrue(message.contains("no absolute width or height")
            && message.contains("no viewBox"), message);
    }

    @Test
    void everyUndeterminedCaseNamesItsReason() {
        assertTrue(refusal("<svg " + NS + "/>").contains("no absolute width or height"));
        assertTrue(refusal("<svg width=\"100\"/>").contains("no absolute height"));
        assertTrue(refusal("<svg height=\"100\"/>").contains("no absolute width"));
        // Font-relative and viewport-relative lengths need a browser: refuse, even with a
        // viewBox present, rather than report a size the render would not have.
        String em = refusal("<svg width=\"50em\" height=\"30em\" viewBox=\"0 0 5 3\"/>");
        assertTrue(em.contains("unit \"em\"") && em.contains("50em"), em);
        assertTrue(refusal("<svg width=\"10vw\" height=\"10\"/>").contains("unit \"vw\""));
        String percent = refusal("<svg width=\"50%\" height=\"100\" viewBox=\"0 0 1 1\"/>");
        assertTrue(percent.contains("percentage") && percent.contains("50%"), percent);
        assertTrue(refusal("<svg width=\"0\" height=\"10\"/>").contains("not a positive length"));
        assertTrue(refusal("<svg width=\"-5\" height=\"10\"/>").contains("not a positive length"));
        assertTrue(refusal("<svg width=\"wide\" height=\"10\"/>").contains("not a length"));
        assertTrue(refusal("<svg width=\"&w;\" height=\"10\"/>").contains("not a length"));
        // Java-only number spellings are not SVG lengths.
        assertTrue(refusal("<svg width=\"NaN\" height=\"10\"/>").contains("not a length"));
        assertTrue(refusal("<svg width=\"0x10\" height=\"10\"/>").contains("not a length"));
    }

    @Test
    void anUnusableViewBoxIsNamedNotGuessed() {
        for (String viewBox : new String[] {
            "0 0 100", "0 0 0 100", "0 0 100 -1", "0 0 100px 50px", "a b c d",
            "0 0 1d 1f", "0 0 NaN 5", ""}) {
            String message = refusal("<svg viewBox=\"" + viewBox + "\"/>");
            assertTrue(message.contains("viewBox is not four numbers"),
                "viewBox '" + viewBox + "': " + message);
        }
    }

    @Test
    void nonSvgAndBrokenDocumentsAreNamed() {
        assertTrue(refusal("").contains("empty"));
        assertTrue(refusal("   \n").contains("no root element"));
        assertTrue(refusal("<html><svg width=\"1\" height=\"1\"/></html>")
            .contains("root element is <html>, not <svg>"));
        assertTrue(refusal("plain text").contains("does not start with an XML element"));
        assertTrue(refusal("<svg width=\"100\" height=\"100\"").contains("does not end"));
        assertTrue(refusal("<svg width=\"100").contains("does not end"));
        assertTrue(refusal("<!-- never closed <svg width=\"1\" height=\"1\"/>")
            .contains("comment"));
        assertTrue(refusal("<!DOCTYPE svg [ <svg width=\"1\" height=\"1\"/>")
            .contains("DOCTYPE"));
        assertTrue(refusal("<svg width=100 height=100/>").contains("not quoted"));
        assertTrue(refusal("<svg width height=\"100\"/>").contains("has no value"));
    }

    @Test
    void sizesBeyondTheViewportCeilingsAreRefused() throws Exception {
        assertEquals(16_384, size("<svg width=\"16384\" height=\"10\"/>").width());
        assertTrue(refusal("<svg width=\"16385\" height=\"10\"/>")
            .contains("exceeds the viewport limit"));
        // 16384 x 4097 = 67,125,248 pixels, over the 67,108,864 ceiling.
        assertTrue(refusal("<svg width=\"16384\" height=\"4097\"/>")
            .contains("exceeds the viewport limit"));
        assertTrue(refusal("<svg width=\"1e300\" height=\"10\"/>").contains("too large"));
    }

    @Test
    void filesAreReadFromABoundedPrefixInUtf8OrBomMarkedUtf16(@TempDir Path directory)
            throws Exception {
        String document = "<?xml version=\"1.0\"?>\n<svg width=\"300\" height=\"120\"/>\n";

        Path utf8Bom = directory.resolve("bom.svg");
        byte[] body = document.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        Files.write(utf8Bom, withBom);
        assertEquals(300, SvgIntrinsicSize.read(utf8Bom).width());

        Path utf16 = directory.resolve("utf16.svg");
        byte[] wide = document.getBytes(StandardCharsets.UTF_16LE);
        byte[] wideWithBom = new byte[wide.length + 2];
        wideWithBom[0] = (byte) 0xFF;
        wideWithBom[1] = (byte) 0xFE;
        System.arraycopy(wide, 0, wideWithBom, 2, wide.length);
        Files.write(utf16, wideWithBom);
        assertEquals(120, SvgIntrinsicSize.read(utf16).height());

        // A root tag that starts after the inspected prefix is a named refusal, and a
        // large body AFTER the root tag costs nothing.
        Path lateRoot = directory.resolve("late.svg");
        Files.writeString(lateRoot, "<!--" + "x".repeat(SvgIntrinsicSize.MAX_PREFIX_BYTES)
            + "--><svg width=\"300\" height=\"120\"/>");
        assertTrue(assertThrows(SvgIntrinsicSize.Undetermined.class,
            () -> SvgIntrinsicSize.read(lateRoot)).getMessage().contains("comment"));

        Path bigBody = directory.resolve("big.svg");
        Files.writeString(bigBody, "<svg width=\"300\" height=\"120\"><!--"
            + "x".repeat(SvgIntrinsicSize.MAX_PREFIX_BYTES) + "--></svg>");
        assertEquals(300, SvgIntrinsicSize.read(bigBody).width());
    }
}
