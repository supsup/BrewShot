package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.ImageInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Browser-free resource-cap discriminators (F-02 GIF budget + configurable size limits,
 * PNG capture caps, F-01 CDP ingress + console byte bounds). Every bound has a TRIP case
 * and an under-the-bound / raised-limit control, so none can pass by refusing everything.
 * No Chrome/Docker: synthetic PNG bytes drive the pure size-check helpers directly, and the
 * WS listener is exercised through its package-visible accumulation seam. All limits are read
 * FRESH from system properties, so each test sets its own and clears it in tearDown.
 */
class BrewShotResourceCapsTest {

    private final List<String> touchedProps = new ArrayList<>();

    private void setProp(String key, String value) {
        touchedProps.add(key);
        System.setProperty(key, value);
    }

    @AfterEach
    void clearProps() {
        for (String k : touchedProps) { System.clearProperty(k); }
        touchedProps.clear();
    }

    /** A genuinely-decodable PNG of the requested pixel size, filled solid. */
    private static byte[] png(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, ((x * 37 + y * 13) & 0xFF) << 8 | 0x203040);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }


    // ---- brewshot/639: the decoded cost comes from the header's type, never a constant ----

    /** A PNG whose pixels carry {@code transferType} samples per band, alpha optional. */
    private static byte[] pngOfType(int w, int h, int transferType, boolean alpha,
            ColorSpace space) throws IOException {
        ColorModel model = new ComponentColorModel(space, alpha, false,
            alpha ? Transparency.TRANSLUCENT : Transparency.OPAQUE, transferType);
        WritableRaster raster = model.createCompatibleWritableRaster(w, h);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(new BufferedImage(model, raster, false, null), "png", out)) {
            throw new IOException("no PNG writer accepted this image type");
        }
        return out.toByteArray();
    }

    private static byte[] png16Rgba(int w, int h) throws IOException {
        return pngOfType(w, h, DataBuffer.TYPE_USHORT, true, ColorSpace.getInstance(ColorSpace.CS_sRGB));
    }

    private static byte[] png8(int w, int h, int type) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, type), "png", out);
        return out.toByteArray();
    }

    /**
     * THE DISCRIMINATOR (brewshot/639 item 4). At a budget set exactly AT the 8-bit RGBA
     * cost of one frame, that frame is admitted and a 16-bit-per-channel frame of the SAME
     * width and height is refused, because it decodes to 8 bytes per pixel rather than 4.
     *
     * <p>THIS IS THE ONLY PAIR THAT SEPARATES THE TWO ACCOUNTINGS. Under the flat 4 the
     * 16-bit frame is charged 4800, which is not greater than 4800, so it passes -- which
     * is the defect, and is what the mutant in the evidence restores. The RGB and GRAY
     * frames are CONTROLS: they pass under both accountings, so they prove the budget is
     * not simply refusing everything.</p>
     */
    @Test
    void gifChargesDecodedBytesFromTheHeaderTypeAndRefusesA16BitFrameAFlatFourWouldAdmit()
            throws IOException {
        int w = 40;
        int h = 30;
        setProp("brewshot.gif.maxFrameDimension", "4096");
        setProp("brewshot.gif.maxDecodedBytes", String.valueOf(w * h * 4)); // 4800: the 8-bit RGBA cost

        GifWriter.enforceDecodeBounds(List.of(png8(w, h, BufferedImage.TYPE_INT_ARGB)));
        GifWriter.enforceDecodeBounds(List.of(png8(w, h, BufferedImage.TYPE_INT_RGB)));
        GifWriter.enforceDecodeBounds(List.of(png8(w, h, BufferedImage.TYPE_BYTE_GRAY)));

        IOException refused = assertThrows(IOException.class,
            () -> GifWriter.enforceDecodeBounds(List.of(png16Rgba(w, h))));
        assertTrue(refused.getMessage().contains("8 bytes/pixel"),
            "the refusal names the per-pixel charge it derived from the header: "
                + refused.getMessage());
        assertTrue(refused.getMessage().contains(String.valueOf((long) w * h * 8)),
            "and the total it reached: " + refused.getMessage());
    }

    /**
     * A HEADER THAT WILL NOT SAY COSTS THE WORST CASE, NEVER FOUR (item 2).
     *
     * <p>Driven directly, and that is a disclosure rather than a shortcut: no image format
     * in the JDK's reader set returns a null type. Measured across PNG (8/16-bit, RGB, RGBA,
     * GRAY, INDEXED, 1-bit), JPEG, GIF and BMP, every reader answered getRawImageType. So
     * this arm is UNREACHABLE through a real fixture and a test that only fed it files would
     * certify nothing about it.</p>
     */
    @Test
    void anUnreadableHeaderTypeChargesTheWorstCaseRatherThanTheCommonCase() {
        assertEquals(8, GifWriter.bytesPerPixelOf(null),
            "a type the reader would not give up charges 8, the widest thing BrewShot decodes");
    }

    /**
     * THE ACCOUNTING NEVER CHARGES LESS THAN THE RASTER ACTUALLY COSTS (item 5).
     *
     * <p>For every header shape, compare what {@link GifWriter#bytesPerPixelOf} charges
     * against the size of the {@code DataBuffer} the frame actually decodes to. The ratio
     * must be at or above 1.00x on every row; a row below 1.00x is the bug class this slice
     * exists to close, in a shape the discriminator above would not catch.</p>
     *
     * <p>It walks the PRODUCTION lookup ({@code rawTypeOf} then {@code bytesPerPixelOf}), not
     * a second copy written here -- a census that re-derives the thing it audits agrees with
     * itself by construction.</p>
     */
    @Test
    void theChargedCostIsNeverBelowTheDecodedRasterForAnyHeaderShape() throws IOException {
        ColorSpace srgb = ColorSpace.getInstance(ColorSpace.CS_sRGB);
        ColorSpace gray = ColorSpace.getInstance(ColorSpace.CS_GRAY);
        int w = 40;
        int h = 30;
        Map<String, byte[]> corpus = new LinkedHashMap<>();
        corpus.put("png 8-bit RGB", png8(w, h, BufferedImage.TYPE_INT_RGB));
        corpus.put("png 8-bit RGBA", png8(w, h, BufferedImage.TYPE_INT_ARGB));
        corpus.put("png 8-bit GRAY", png8(w, h, BufferedImage.TYPE_BYTE_GRAY));
        corpus.put("png 8-bit INDEXED", png8(w, h, BufferedImage.TYPE_BYTE_INDEXED));
        corpus.put("png 1-bit BINARY", png8(w, h, BufferedImage.TYPE_BYTE_BINARY));
        corpus.put("png 16-bit RGBA", png16Rgba(w, h));
        corpus.put("png 16-bit RGB", pngOfType(w, h, DataBuffer.TYPE_USHORT, false, srgb));
        corpus.put("png 16-bit GRAY", pngOfType(w, h, DataBuffer.TYPE_USHORT, false, gray));
        corpus.put("jpeg 8-bit RGB", encoded(w, h, BufferedImage.TYPE_INT_RGB, "jpeg"));
        corpus.put("gif indexed", encoded(w, h, BufferedImage.TYPE_INT_ARGB, "gif"));

        List<String> under = new ArrayList<>();
        for (Map.Entry<String, byte[]> row : corpus.entrySet()) {
            long charged = (long) w * h * chargedPerPixel(row.getValue());
            long actual = decodedRasterBytes(row.getValue());
            if (charged < actual) {
                under.add(row.getKey() + ": charged " + charged + " < actual " + actual);
            }
        }
        assertTrue(under.isEmpty(), "every header shape must be charged at or above what it "
            + "actually decodes to; under-charged rows: " + under);
        assertEquals(10, corpus.size(), "the census covers ten header shapes; adding one is "
            + "deliberate and this number moves with it");
    }

    private static byte[] encoded(int w, int h, int type, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(new BufferedImage(w, h, type), format, out)) {
            throw new IOException("no " + format + " writer accepted this image type");
        }
        return out.toByteArray();
    }

    /** What the production lookup charges for these bytes. */
    private static int chargedPerPixel(byte[] image) throws IOException {
        try (ImageInputStream iis =
                 ImageIO.createImageInputStream(new ByteArrayInputStream(image))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            assertTrue(readers.hasNext(), "the census fixture must be decodable");
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                ImageTypeSpecifier type = GifWriter.rawTypeOf(reader);
                return GifWriter.bytesPerPixelOf(type);
            } finally {
                reader.dispose();
            }
        }
    }

    /** What the frame's raster actually occupies once decoded. */
    private static long decodedRasterBytes(byte[] image) throws IOException {
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(image));
        assertNotNull(decoded, "the census fixture must decode");
        DataBuffer buffer = decoded.getRaster().getDataBuffer();
        int elementBytes = DataBuffer.getDataTypeSize(buffer.getDataType()) / 8;
        return (long) buffer.getSize() * elementBytes * buffer.getNumBanks();
    }

    // ================= F-02: GIF decode budget + configurable limits =================

    @Test
    void gifRejectsAnOverDimensionFrameBeforeDecode(@TempDir Path dir) throws IOException {
        setProp("brewshot.gif.maxFrameDimension", "100");
        List<byte[]> frames = List.of(png(50, 50), png(200, 40)); // frame 1 axis 200 > 100
        IOException e = assertThrows(IOException.class,
            () -> GifWriter.write(frames, 100, dir.resolve("over-dim.gif")));
        assertTrue(e.getMessage().contains("max axis 100")
                && e.getMessage().contains("maxFrameDimension"),
            "the refusal names the axis limit: " + e.getMessage());
        assertFalse(Files.exists(dir.resolve("over-dim.gif")),
            "rejected before any output file is opened");
    }

    @Test
    void gifRejectsTooManyFrames(@TempDir Path dir) throws IOException {
        setProp("brewshot.gif.maxFrames", "3");
        List<byte[]> frames = new ArrayList<>();
        for (int i = 0; i < 5; i++) { frames.add(png(20, 20)); }
        IOException e = assertThrows(IOException.class,
            () -> GifWriter.write(frames, 100, dir.resolve("over-count.gif")));
        assertTrue(e.getMessage().contains("5 frames") && e.getMessage().contains("maxFrames"),
            "the refusal names the frame-count limit: " + e.getMessage());
    }

    @Test
    void gifRejectsOverDecodedWorkingSetBudget(@TempDir Path dir) throws IOException {
        // png() writes 8-bit RGB, so each 100x100 frame is charged 100*100*3 = 30000 bytes
        // (it was 40000 under the flat-4 accounting brewshot/639 replaced). Budget 40000
        // admits the first and the running sum trips on the second, proving the Σ
        // w*h*bytesPerPixel accounting cheaply, with tiny frames.
        setProp("brewshot.gif.maxDecodedBytes", "40000");
        List<byte[]> frames = List.of(png(100, 100), png(100, 100), png(100, 100));
        IOException e = assertThrows(IOException.class,
            () -> GifWriter.write(frames, 100, dir.resolve("over-decoded.gif")));
        assertTrue(e.getMessage().contains("decoded raster accounting")
                && e.getMessage().contains("maxDecodedBytes"),
            "the refusal names the decoded-byte budget: " + e.getMessage());
    }

    @Test
    void anInBudgetRecordingStillEncodes(@TempDir Path dir) throws IOException {
        // Generous explicit limits, so this is a pure GREEN control for the class.
        setProp("brewshot.gif.maxFrames", "1000");
        setProp("brewshot.gif.maxFrameDimension", "4096");
        setProp("brewshot.gif.maxDecodedBytes", "536870912");
        Path out = dir.resolve("ok.gif");
        GifWriter.write(List.of(png(32, 24), png(32, 24), png(32, 24)), 80, out);
        assertTrue(Files.exists(out) && Files.size(out) > 0, "an in-budget set writes a GIF");
    }

    @Test
    void aMinusDOverrideChangesTheFrameLimit(@TempDir Path dir) throws IOException {
        List<byte[]> frames = new ArrayList<>();
        for (int i = 0; i < 5; i++) { frames.add(png(20, 20)); }

        // Under a limit of 3 the 5-frame set is refused ...
        setProp("brewshot.gif.maxFrames", "3");
        assertThrows(IOException.class,
            () -> GifWriter.write(frames, 100, dir.resolve("blocked.gif")));

        // ... and raising the SAME property (the -D override read fresh) admits it.
        System.setProperty("brewshot.gif.maxFrames", "10");
        Path out = dir.resolve("raised.gif");
        GifWriter.write(frames, 100, out);
        assertTrue(Files.exists(out) && Files.size(out) > 0,
            "raising brewshot.gif.maxFrames lets the same set through");
    }

    @Test
    void theStaticGifEntryPointIsNowBoundedToo(@TempDir Path dir) {
        // Regression pin for the closed bypass: BrewShot.gif() used to hand caller frames
        // straight to GifWriter with NO budget. It now funnels the same enforcement.
        setProp("brewshot.gif.maxFrames", "2");
        List<byte[]> frames = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            try { frames.add(png(16, 16)); } catch (IOException e) { fail(e); }
        }
        IOException e = assertThrows(IOException.class,
            () -> BrewShot.gif(frames, 100, dir.resolve("static-bypass.gif")));
        assertTrue(e.getMessage().contains("maxFrames"),
            "the static gif() path enforces the same budget: " + e.getMessage());
    }

    // ================= PNG capture caps (screenshot path) =================

    @Test
    void captureBoundsRejectsAnOverDimensionImage() throws IOException {
        setProp("brewshot.maxImageDimension", "100");
        byte[] big = png(200, 50); // axis 200 > 100
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> BrewShot.enforceCaptureBounds(big));
        assertTrue(e.getMessage().contains("max axis 100")
                && e.getMessage().contains("maxImageDimension"),
            "the refusal names the dimension limit: " + e.getMessage());
    }

    @Test
    void captureBoundsRejectsAnOverPixelCountImage() throws IOException {
        // 150x150 = 22500 px; both axes are under the default 16384, so ONLY the pixel
        // budget can trip — isolating the megapixel axis from the dimension axis.
        setProp("brewshot.maxImagePixels", "10000");
        byte[] big = png(150, 150);
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> BrewShot.enforceCaptureBounds(big));
        assertTrue(e.getMessage().contains("22500")
                && e.getMessage().contains("maxImagePixels"),
            "the refusal names the pixel budget: " + e.getMessage());
    }

    @Test
    void captureBoundsPassesAnInBoundsImage() throws IOException {
        setProp("brewshot.maxImageDimension", "16384");
        setProp("brewshot.maxImagePixels", "67108864");
        BrewShot.enforceCaptureBounds(png(64, 48)); // must NOT throw
    }

    // ===== JPEG capture caps, and the parser checked against a reference decoder =====

    private static byte[] jpeg(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, ((x * 11 + y * 29) & 0xFF) << 16 | 0x3050);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, "jpg", out), "the JPEG fixture must actually encode");
        return out.toByteArray();
    }

    @Test
    void captureBoundsRejectsAnOverDimensionJpeg() throws IOException {
        setProp("brewshot.maxImageDimension", "100");
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> BrewShot.enforceCaptureBounds(jpeg(200, 50)));
        assertTrue(e.getMessage().contains("200x50") && e.getMessage().contains("max axis 100"),
            "the JPEG refusal names the real dimensions and the limit: " + e.getMessage());
    }

    @Test
    void captureBoundsPassesAnInBoundsJpeg() throws IOException {
        setProp("brewshot.maxImageDimension", "16384");
        setProp("brewshot.maxImagePixels", "67108864");
        BrewShot.enforceCaptureBounds(jpeg(64, 48)); // must NOT throw
    }

    /**
     * EQUIVALENCE against a reference decoder. The production path must not depend on
     * ImageIO, but the parser still has to be RIGHT, and "I read the spec carefully" is not
     * evidence. ImageIO appears here in TEST code only — that is the sanctioned use, and the
     * census in CaptureBoundsNativeCleanCensusTest covers the production file, not this one.
     *
     * <p>The non-square sizes are deliberate: a JPEG SOF stores HEIGHT BEFORE WIDTH, so a
     * transposition bug is invisible on a square fixture and obvious on 200x50.
     */
    @Test
    void theHeaderParserAgreesWithAReferenceDecoder() throws IOException {
        byte[][] fixtures = { png(64, 48), png(200, 50), png(1, 1), jpeg(64, 48), jpeg(200, 50) };
        for (byte[] bytes : fixtures) {
            BufferedImage reference = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            assertNotNull(reference, "fixture did not decode; the test data is wrong, not the parser");
            int[] parsed = BrewShot.readImageHeaderDimensions(bytes);
            assertNotNull(parsed, "the header parser returned nothing for a valid image");
            assertEquals(reference.getWidth(), parsed[0], "width disagrees with the reference decoder");
            assertEquals(reference.getHeight(), parsed[1], "height disagrees with the reference decoder");
        }
    }

    // ===== crafted JPEG marker bytes, and the declared-length guards (S3/N1/N2) =====
    // Real encoders put DHT AFTER SOF and never emit fill bytes, so every one of these
    // branches was UNPINNED: the reviewer showed that deleting the C4/C8/CC exclusion, the
    // fill-byte handling, or the standalone-marker handling all left 0 red. Correct code with
    // no test is a coin that has not been flipped.

    /** SOI, then the given segment bytes, then a minimal SOF0 declaring 16x32. */
    private static byte[] jpegWith(byte[] before) {
        byte[] sof = { (byte) 0xFF, (byte) 0xC0, 0x00, 0x11, 0x08,
                       0x00, 0x20,            // height 32
                       0x00, 0x10,            // width 16
                       0x03, 0x01, 0x11, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01 };
        byte[] out = new byte[2 + before.length + sof.length];
        out[0] = (byte) 0xFF; out[1] = (byte) 0xD8;
        System.arraycopy(before, 0, out, 2, before.length);
        System.arraycopy(sof, 0, out, 2 + before.length, sof.length);
        return out;
    }

    @Test
    void aDhtSegmentBeforeTheFrameHeaderIsSkippedNotMistakenForOne() {
        // 0xC4 sits inside the SOF0..SOF15 numeric range but describes Huffman tables, not a
        // frame. Reading it as a frame header yields garbage dimensions.
        byte[] dht = { (byte) 0xFF, (byte) 0xC4, 0x00, 0x06, 0x00, 0x01, 0x02, 0x03 };
        int[] wh = BrewShot.readImageHeaderDimensions(jpegWith(dht));
        assertNotNull(wh, "a DHT before the SOF must be skipped, not fail the walk");
        assertEquals(16, wh[0], "width after skipping DHT");
        assertEquals(32, wh[1], "height after skipping DHT");
    }

    @Test
    void fillBytesBeforeAMarkerAreResynchronisedOver() {
        byte[] fill = { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF };
        int[] wh = BrewShot.readImageHeaderDimensions(jpegWith(fill));
        assertNotNull(wh, "0xFF fill bytes are legal padding and must not desync the walk");
        assertEquals(16, wh[0]);
        assertEquals(32, wh[1]);
    }

    @Test
    void aStandaloneMarkerBeforeTheFrameHeaderCarriesNoLength() {
        // TEM (0x01) and the RSTn markers have no length field; treating them as if they did
        // reads a length out of the following bytes and walks off into the middle of a segment.
        byte[] standalone = { (byte) 0xFF, 0x01, (byte) 0xFF, (byte) 0xD0 };
        int[] wh = BrewShot.readImageHeaderDimensions(jpegWith(standalone));
        assertNotNull(wh, "standalone markers must be stepped over without reading a length");
        assertEquals(16, wh[0]);
        assertEquals(32, wh[1]);
    }

    @Test
    void anSofDeclaringALengthTooSmallToHoldDimensionsIsRefused() {
        // N1: segment length 2 cannot hold precision + height + width. Before the guard this
        // was admitted as 5x5 by reading PAST the segment it declared.
        byte[] shortSof = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xC0,
                            0x00, 0x02, 0x08, 0x00, 0x05, 0x00, 0x05 };
        assertNull(BrewShot.readImageHeaderDimensions(shortSof),
            "an SOF cannot declare a length of 2 and still carry dimensions");
        assertThrows(IllegalStateException.class,
            () -> BrewShot.enforceCaptureBounds(shortSof));
    }

    @Test
    void aPngWhoseIhdrDeclaresTheWrongLengthIsRefused() {
        // N2: IHDR is fixed at 13 bytes. A chunk claiming otherwise is not one we can read
        // positionally, so offsets 16 and 20 are not trustworthy.
        byte[] png = new byte[] {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0C, 'I', 'H', 'D', 'R',   // length 12, not 13
            0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00, 0x30
        };
        assertNull(BrewShot.readImageHeaderDimensions(png),
            "an IHDR declaring a length other than 13 must not be read positionally");
    }

    // ===== AT the limit, and one past it (must-fix M1, brewshot/634) =====
    // The first round had fixtures only WELL under and WELL over, so `w > maxDim` mutated to
    // `>=` left all 27 tests green: nothing ever sat exactly ON the boundary, which is the one
    // input that distinguishes the two operators. Each pair below is at-limit (must PASS) and
    // limit+1 (must REFUSE), for both axes and both formats.

    @Test
    void captureBoundsAdmitsAPngExactlyAtTheDimensionLimit() throws IOException {
        setProp("brewshot.maxImageDimension", "200");
        setProp("brewshot.maxImagePixels", "67108864");
        BrewShot.enforceCaptureBounds(png(200, 120)); // exactly AT: > is false, >= would refuse
    }

    @Test
    void captureBoundsRefusesAPngOnePastTheDimensionLimit() throws IOException {
        setProp("brewshot.maxImageDimension", "200");
        setProp("brewshot.maxImagePixels", "67108864");
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> BrewShot.enforceCaptureBounds(png(201, 120)));
        assertTrue(e.getMessage().contains("201x120"), "names the offending size: " + e.getMessage());
    }

    @Test
    void captureBoundsAdmitsAJpegExactlyAtTheDimensionLimit() throws IOException {
        setProp("brewshot.maxImageDimension", "200");
        setProp("brewshot.maxImagePixels", "67108864");
        BrewShot.enforceCaptureBounds(jpeg(200, 120));
    }

    @Test
    void captureBoundsRefusesAJpegOnePastTheDimensionLimit() throws IOException {
        setProp("brewshot.maxImageDimension", "200");
        setProp("brewshot.maxImagePixels", "67108864");
        assertThrows(IllegalStateException.class,
            () -> BrewShot.enforceCaptureBounds(jpeg(201, 120)));
    }

    @Test
    void captureBoundsAdmitsAnImageExactlyAtThePixelBudget() throws IOException {
        setProp("brewshot.maxImageDimension", "16384");
        setProp("brewshot.maxImagePixels", "9600"); // 120*80 exactly
        BrewShot.enforceCaptureBounds(png(120, 80));
    }

    @Test
    void captureBoundsRefusesAnImageOnePixelPastTheBudget() throws IOException {
        setProp("brewshot.maxImageDimension", "16384");
        setProp("brewshot.maxImagePixels", "9599"); // 120*80 = 9600, one over
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> BrewShot.enforceCaptureBounds(png(120, 80)));
        assertTrue(e.getMessage().contains("9600") && e.getMessage().contains("maxImagePixels"),
            "names the pixel budget: " + e.getMessage());
    }

    // ===== an UNREADABLE header must be REFUSED, not skipped (ruling brewshot/632) =====
    // Today enforceCaptureBounds returns on an unreadable header, with the comment
    // "not a size problem; leave decode errors to the consumer". The ruling is that this is
    // the wrong call when the check EXISTS to bound size: bytes whose size cannot be
    // established are exactly the bytes a size bound must not wave through. These three are
    // a DELIBERATE BEHAVIOUR CHANGE, not a refactor, which is why they are pinned separately
    // from the dimension and pixel cases above.

    @Test
    void captureBoundsRefusesATruncatedPngRatherThanSkippingTheBound() {
        // PNG signature + the IHDR length and type, and then nothing: the stream announces a
        // PNG and stops before width and height exist.
        byte[] truncated = new byte[] {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R'
        };
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> BrewShot.enforceCaptureBounds(truncated),
            "a truncated PNG header leaves the size unknown, so the size bound must REFUSE it");
        assertTrue(e.getMessage().toLowerCase().contains("header"),
            "the refusal names the header as the reason: " + e.getMessage());
    }

    @Test
    void captureBoundsRefusesAJpegWithNoFrameHeader() {
        // SOI, then a COMMENT segment (FFFE) carrying two payload bytes, then EOI. Structurally
        // a JPEG, but it never reaches an SOF marker, so no dimensions are ever declared.
        byte[] noSof = new byte[] {
            (byte) 0xFF, (byte) 0xD8,
            (byte) 0xFF, (byte) 0xFE, 0x00, 0x04, 0x41, 0x42,
            (byte) 0xFF, (byte) 0xD9
        };
        assertThrows(IllegalStateException.class,
            () -> BrewShot.enforceCaptureBounds(noSof),
            "a JPEG with no SOF never declares a size, so the size bound must REFUSE it");
    }

    @Test
    void captureBoundsRefusesBytesThatAreNotAnImageAtAll() {
        byte[] notAnImage = "this is not an image, it is a sentence".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class,
            () -> BrewShot.enforceCaptureBounds(notAnImage),
            "unrecognised bytes have no establishable size, so the size bound must REFUSE them");
    }

    @Test
    void aMinusDOverrideChangesTheImageLimit() throws IOException {
        byte[] img = png(200, 200);
        setProp("brewshot.maxImageDimension", "100");
        assertThrows(IllegalStateException.class, () -> BrewShot.enforceCaptureBounds(img));
        System.setProperty("brewshot.maxImageDimension", "500");
        BrewShot.enforceCaptureBounds(img); // raised limit admits the same bytes
    }

    // ================= F-01: CDP ingress ceiling =================

    @Test
    void anOversizedCdpMessageIsDroppedNotBuffered() {
        LinkedBlockingQueue<BrewShot.InboxMessage> q = new LinkedBlockingQueue<>();
        BrewShot.Accumulator acc = new BrewShot.Accumulator(q, 100, 4096);

        // One 250-byte ASCII message split across partials crosses the 100-byte ceiling.
        acc.accept("x".repeat(60), false);
        acc.accept("y".repeat(60), false);
        acc.accept("z".repeat(130), true);
        assertNull(q.poll(), "the oversized message is never enqueued");
        assertEquals(1, acc.dropped(), "and the drop is counted");

        // Control on the SAME accumulator: an in-ceiling message flows through intact.
        acc.accept("{\"ok\":1}", true);
        assertEquals("{\"ok\":1}", q.poll().raw(),
            "an in-ceiling message is enqueued whole");
        assertEquals(1, acc.dropped(), "the in-ceiling message adds no drop");
    }

    @Test
    void cdpMessageCeilingCountsExactUtf8BytesAcrossLegalWebSocketFragments() {
        LinkedBlockingQueue<BrewShot.InboxMessage> q = new LinkedBlockingQueue<>();
        BrewShot.Accumulator acc = new BrewShot.Accumulator(q, 4, 4096);

        // A complete supplementary code point is exactly four UTF-8 bytes and must
        // be admitted at equality, including across a callback boundary.
        String emoji = "\uD83D\uDE00";
        acc.accept(emoji, true);
        assertEquals(emoji, q.poll().raw(), "supplementary code point is measured exactly");

        // Three UTF-16 units, but six UTF-8 bytes. The old CharSequence.length()
        // implementation admits this under a 4-byte setting; this is the causal
        // discriminator for the lying maxCdpMessageBytes contract.
        acc.accept("ééé", true);
        assertNull(q.poll(), "encoded bytes, not UTF-16 units, enforce the byte ceiling");
        assertEquals(1, acc.dropped(), "the over-byte message is counted as dropped");

        // Paired control: equality remains inclusive for ordinary ASCII too.
        acc.accept("abcd", true);
        assertEquals("abcd", q.poll().raw());
        assertEquals(1, acc.dropped());
    }

    @Test
    void allocationFreeUtf8MeterMatchesTheJdkEncoder() {
        String[] samples = {
            "", "plain ASCII", "é", "€", "\uD83D\uDE00", "x\uD83D\uDE00y",
            "\uD83D", "\uDE00", "\uD83Dé\uDE00"
        };
        for (String sample : samples) {
            assertEquals(sample.getBytes(StandardCharsets.UTF_8).length,
                BrewShot.utf8Length(sample), "UTF-8 length drift for " + sample);
        }
    }

    @Test
    void incrementalUtf8MeterMatchesWholeEncodingAtEveryFragmentBoundary() {
        String[] samples = {
            "abc", "éa€", "\uD83D\uDE00", "a\uD83D\uDE00éz"
        };
        for (String sample : samples) {
            long exactBytes = sample.getBytes(StandardCharsets.UTF_8).length;
            for (int split = 0; split <= sample.length(); split++) {
                LinkedBlockingQueue<BrewShot.InboxMessage> q =
                    new LinkedBlockingQueue<>();
                BrewShot.Accumulator acc =
                    new BrewShot.Accumulator(q, exactBytes, 4);
                acc.accept(sample.substring(0, split), false);
                acc.accept(sample.substring(split), true);
                assertEquals(sample, q.poll().raw(),
                    "exact-byte message rejected at UTF-16 split " + split
                        + " of " + sample.length());
                assertEquals(0, acc.dropped());
            }
        }
    }

    @Test
    void aFloodOfSmallCdpMessagesIsBoundedCumulatively() {
        // The DEFECT this replaces: the per-message ceiling let an arbitrary NUMBER of
        // individually-small messages pile up in a default-unbounded inbox (Marlow's repro:
        // 20000 one-char messages → queued=20000). The cumulative cap now bounds the whole
        // queue. Mirror production: capacity is cap + 1 (one slot reserved for the sentinel).
        int cap = 8;
        LinkedBlockingQueue<BrewShot.InboxMessage> q =
            new LinkedBlockingQueue<>(cap + 1);
        BrewShot.Accumulator acc = new BrewShot.Accumulator(q, 1_000, cap);

        int flood = 20_000;
        for (int i = 0; i < flood; i++) { acc.accept("m", true); } // each well under the byte ceiling

        assertEquals(cap, q.size(), "the inbox is bounded to the cumulative cap, not the flood size");
        assertEquals(0, acc.dropped(), "no per-MESSAGE (byte-ceiling) drops — every message was small");
        assertEquals(flood - cap, acc.inboxDropped(),
            "every message past the cap is dropped and counted (loud, not silent)");
    }

    @Test
    void theCloseSignalSurvivesAFullInbox() {
        // The reserved-slot guarantee: even when the regular inbox is saturated and further
        // messages are being DROPPED, the close/error poison still gets a home — a blocked
        // caller fails fast instead of sleeping out the timeout.
        int cap = 4;
        LinkedBlockingQueue<BrewShot.InboxMessage> q =
            new LinkedBlockingQueue<>(cap + 1);
        BrewShot.Accumulator acc = new BrewShot.Accumulator(q, 1_000, cap);

        for (int i = 0; i < cap + 50; i++) { acc.accept("m", true); } // saturate + overflow
        assertEquals(cap, q.size(), "regular messages are capped");
        assertTrue(acc.inboxDropped() > 0, "and the overflow is being dropped");

        // Socket closes while the inbox is full — the sentinel must NOT be lost.
        acc.onClose(null, 1000, "bye");
        assertEquals(cap + 1, q.size(), "the reserved slot admits the close sentinel");

        // Drain: the last element is the poison signal, intact.
        BrewShot.InboxMessage last = null;
        for (BrewShot.InboxMessage s = q.poll(); s != null; s = q.poll()) { last = s; }
        assertTrue(last != null && last.socketClosed()
                && last.raw().contains("brewshotSocketClosed"),
            "the close signal arrived even though the inbox was full: " + last);
    }

    // ================= F-01: console/error retained-byte bound =================

    @Test
    void aSingleHugeConsoleEntryIsTruncatedNotRetainedWhole() {
        setProp("brewshot.maxConsoleBytes", "200");
        BrewShot.BoundedLog log = new BrewShot.BoundedLog();
        log.record("A".repeat(10_000)); // a single multi-KB entry — the F-01 case
        List<String> got = log.view();
        assertEquals(1, got.size(), "one entry retained");
        long retained = got.get(0).getBytes(StandardCharsets.UTF_8).length;
        assertTrue(retained < 400,
            "the huge entry is truncated to the byte budget, not kept whole (" + retained + " B)");
        assertTrue(got.get(0).contains("console byte budget"),
            "and stamped with a truncation marker: " + got.get(0));
        assertEquals(1, log.dropped(), "the truncation is counted");
    }

    @Test
    void manyConsoleEntriesAreBoundedToTheByteBudget() {
        setProp("brewshot.maxConsoleBytes", "500");
        BrewShot.BoundedLog log = new BrewShot.BoundedLog();
        for (int i = 0; i < 1000; i++) { log.record("entry-" + i + "-" + "p".repeat(40)); }
        long retained = 0;
        for (String s : log.view()) { retained += s.getBytes(StandardCharsets.UTF_8).length; }
        assertTrue(retained <= 500 + 64 /* one marker */,
            "total retained bytes bounded to the budget (" + retained + " B)");
        assertTrue(log.dropped() > 0, "the excess entries are dropped and counted");
    }

    @Test
    void anInBudgetConsoleEntryIsKeptWholeWithNoDrop() {
        setProp("brewshot.maxConsoleBytes", "1048576");
        BrewShot.BoundedLog log = new BrewShot.BoundedLog();
        log.record("log: hello world");
        assertEquals(List.of("log: hello world"), log.view(), "kept verbatim");
        assertEquals(0, log.dropped(), "no drop under a generous budget");
    }
}
