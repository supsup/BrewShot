package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import javax.imageio.ImageIO;
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
        // Each 100x100 frame decodes to 100*100*4 = 40000 bytes. Budget 40000 admits the
        // first (== is not >) but the running sum trips on the second — proving the Σ w*h*4
        // accounting, cheaply, with tiny frames (no need for an actually-huge input).
        setProp("brewshot.gif.maxDecodedBytes", "40000");
        List<byte[]> frames = List.of(png(100, 100), png(100, 100), png(100, 100));
        IOException e = assertThrows(IOException.class,
            () -> GifWriter.write(frames, 100, dir.resolve("over-decoded.gif")));
        assertTrue(e.getMessage().contains("decoded working set")
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
        LinkedBlockingQueue<String> q = new LinkedBlockingQueue<>();
        BrewShot.Accumulator acc = new BrewShot.Accumulator(q, 100);

        // One 250-char message split across partials: crosses the 100-char ceiling → dropped.
        acc.accept("x".repeat(60), false);
        acc.accept("y".repeat(60), false);
        acc.accept("z".repeat(130), true);
        assertNull(q.poll(), "the oversized message is never enqueued");
        assertEquals(1, acc.dropped(), "and the drop is counted");

        // Control on the SAME accumulator: an in-ceiling message flows through intact.
        acc.accept("{\"ok\":1}", true);
        assertEquals("{\"ok\":1}", q.poll(), "an in-ceiling message is enqueued whole");
        assertEquals(1, acc.dropped(), "the in-ceiling message adds no drop");
    }

    @Test
    void manyCdpMessagesStayBoundedPerMessageNotCumulative() {
        // The ceiling is PER MESSAGE — a stream of in-ceiling messages all pass; only the
        // individual oversized one is dropped. Pins that the bound doesn't wedge normal traffic.
        LinkedBlockingQueue<String> q = new LinkedBlockingQueue<>();
        BrewShot.Accumulator acc = new BrewShot.Accumulator(q, 50);
        for (int i = 0; i < 20; i++) { acc.accept("msg" + i, true); }        // all small
        acc.accept("BIG".repeat(100), true);                                  // 300 > 50 → drop
        for (int i = 0; i < 20; i++) { acc.accept("post" + i, true); }        // all small
        assertEquals(1, acc.dropped(), "exactly the one oversized message dropped");
        assertEquals(40, q.size(), "every in-ceiling message still enqueued");
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
