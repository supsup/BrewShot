package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * PDF capture via {@code Page.printToPDF}. The parameter building and its numeric
 * envelope validation are Chrome-free (unit test of {@code printPdfParams}); the
 * end-to-end {@code %PDF} header check is Chrome-gated via the loud-skip helper.
 * Mirrors {@link BrewShotJpegTest}.
 */
class BrewShotPdfTest {

    // ---- Chrome-free: CLI dispatch decisions (Fix review, brewshot 99) ------

    @Test
    void isPdfOutputIsCaseInsensitive() {
        // F2: the .pdf dispatch is case-insensitive, so `-o out.PDF` cannot fall through to the
        // raster path and write PNG bytes into a .PDF file while reporting success.
        assertTrue(Main.isPdfOutput(Path.of("page.pdf")));
        assertTrue(Main.isPdfOutput(Path.of("page.PDF")), "uppercase .PDF must route to pdf");
        assertTrue(Main.isPdfOutput(Path.of("page.Pdf")));
        assertTrue(Main.isPdfOutput(Path.of("/tmp/deep/REPORT.Pdf")));
        assertFalse(Main.isPdfOutput(Path.of("page.png")));
        assertFalse(Main.isPdfOutput(Path.of("page.jpg")));
        assertFalse(Main.isPdfOutput(Path.of("pdf.png")), "'.pdf' must be the extension, not a prefix");
    }

    @Test
    void pdfWithRasterFlagRefusesLoudlyOnDispatch() throws Exception {
        // F1: a raster-only flag on a .pdf output must FAIL LOUD (exit 2) rather than silently
        // emit a full-page PDF — BrewShot output is review evidence. Chrome-free: the refusal is a
        // pre-launch arg validation, so no browser is touched.
        String[][] rasterFlags = {
            {"--clip-selector", "#nope"}, {"--clip-js", "({x:0,y:0,w:1,h:1})"},
            {"--scale", "2"}, {"--clip-padding", "5"},
        };
        for (String[] flag : rasterFlags) {
            java.io.ByteArrayOutputStream errBuf = new java.io.ByteArrayOutputStream();
            java.io.PrintStream origErr = System.err;
            int rc;
            try {
                System.setErr(new java.io.PrintStream(errBuf));
                rc = Main.run(new String[] {"x.html", "-o", "out.pdf", flag[0], flag[1]});
            } finally {
                System.setErr(origErr);
            }
            assertEquals(2, rc, flag[0] + " on a .pdf output must exit 2");
            assertTrue(errBuf.toString().contains("raster-only"),
                flag[0] + " refusal must name the raster-only reason, got: " + errBuf);
        }
    }

    // ---- Chrome-free: printToPDF param building & validation ---------------

    @Test
    void defaultsBuildUsLetterPortraitParams() {
        assertEquals(
            "\"landscape\":false,\"printBackground\":true,\"scale\":1.0,"
                + "\"paperWidth\":8.5,\"paperHeight\":11.0,"
                + "\"marginTop\":0.0,\"marginRight\":0.0,"
                + "\"marginBottom\":0.0,\"marginLeft\":0.0",
            BrewShot.printPdfParams(BrewShot.PdfOptions.defaults()));
    }

    @Test
    void withersThreadThroughToCdpParams() {
        BrewShot.PdfOptions o = BrewShot.PdfOptions.a4()
            .landscape(true)
            .printBackground(false)
            .scale(0.75)
            .margin(0.5);
        assertEquals(
            "\"landscape\":true,\"printBackground\":false,\"scale\":0.75,"
                + "\"paperWidth\":8.27,\"paperHeight\":11.69,"
                + "\"marginTop\":0.5,\"marginRight\":0.5,"
                + "\"marginBottom\":0.5,\"marginLeft\":0.5",
            BrewShot.printPdfParams(o));
    }

    @Test
    void nonPositivePaperFailsLoud() {
        assertThrows(IllegalArgumentException.class,
            () -> BrewShot.printPdfParams(BrewShot.PdfOptions.defaults().paper(0, 11)));
        assertThrows(IllegalArgumentException.class,
            () -> BrewShot.printPdfParams(BrewShot.PdfOptions.defaults().paper(8.5, -1)));
    }

    @Test
    void negativeMarginFailsLoud() {
        assertThrows(IllegalArgumentException.class,
            () -> BrewShot.printPdfParams(BrewShot.PdfOptions.defaults().margin(-0.25)));
    }

    @Test
    void scaleOutOfCdpRangeFailsLoud() {
        assertThrows(IllegalArgumentException.class,
            () -> BrewShot.printPdfParams(BrewShot.PdfOptions.defaults().scale(0.05)));
        assertThrows(IllegalArgumentException.class,
            () -> BrewShot.printPdfParams(BrewShot.PdfOptions.defaults().scale(2.5)));
    }

    // ---- Chrome-gated: a real PDF comes out of Chrome ----------------------

    @Test
    void pdfStartsWithPdfHeader() throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotPdfTest");
        Path out = Files.createTempDirectory("brewshot-pdf");
        try (BrewShot shot = BrewShot.launch(640, 480)) {
            shot.html("""
                <style>*{margin:0;padding:0}</style>
                <h1>PDF capture</h1>
                <div style="width:400px;height:300px;
                  background:linear-gradient(135deg,#4e79a7,#f28e2b)"></div>
                """);

            // default (US Letter) PDF to a file
            Path pdf = out.resolve("page.pdf");
            shot.pdf(pdf);
            byte[] bytes = Files.readAllBytes(pdf);
            assertTrue(bytes.length > 1000, "pdf too small: " + bytes.length);
            assertEquals((byte) '%', bytes[0], "PDF magic byte 0");
            assertEquals((byte) 'P', bytes[1], "PDF magic byte 1");
            assertEquals((byte) 'D', bytes[2], "PDF magic byte 2");
            assertEquals((byte) 'F', bytes[3], "PDF magic byte 3");

            // an A4 landscape variant also renders a valid PDF
            Path a4 = out.resolve("a4.pdf");
            shot.pdf(a4, BrewShot.PdfOptions.a4().landscape(true).margin(0.25));
            byte[] a4bytes = Files.readAllBytes(a4);
            assertTrue(a4bytes.length > 1000, "a4 pdf too small: " + a4bytes.length);
            assertEquals((byte) '%', a4bytes[0], "A4 PDF magic byte 0");
            assertEquals((byte) 'P', a4bytes[1], "A4 PDF magic byte 1");
        }
    }
}
