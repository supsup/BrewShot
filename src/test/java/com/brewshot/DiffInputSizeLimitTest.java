package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pre-decode size ceilings on diff INPUTS (BrewShot F-03/F-05 follow-on, plan 6bb775ef).
 *
 * <p>Without this guard {@code brewshot diff} hands any caller-named file straight to
 * {@link ImageIO#read}, whose first act is to allocate a {@code width*height} raster —
 * so a declared-huge image takes the memory before anything can reject it. The guard
 * reads the header only and refuses as a USAGE error (exit 2), which is what separates
 * "you asked for something out of bounds" from exit 1's "this file did not decode".
 *
 * <p>Every assertion here pins a property the implementation could plausibly get wrong:
 * that the boundary is inclusive rather than off-by-one, that the ceilings are re-read
 * rather than cached in a static, that the AREA cap is genuinely independent of the
 * per-axis cap, that a non-image is still exit 1 and not swallowed into exit 2, and —
 * the one that makes the guard worth having at all — that the refusal happens BEFORE
 * the allocation rather than after it.
 */
class DiffInputSizeLimitTest {

    private static final String DIMENSION_KEY = "brewshot.maxImageDimension";
    private static final String PIXELS_KEY = "brewshot.maxImagePixels";

    /** Exit 2 is the usage-error code; exit 1 means an image could not be read. */
    private static final int EXIT_USAGE = 2;
    private static final int EXIT_UNREADABLE = 1;
    private static final int EXIT_CLEAN = 0;

    private static Path png(Path dir, String name, int width, int height) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.dispose();
        Path out = dir.resolve(name);
        assertTrue(ImageIO.write(img, "png", out.toFile()), "test fixture must encode");
        return out;
    }

    /** Run a body with the two ceilings set, always restoring the prior values. */
    private static void withLimits(String dimension, String pixels, ThrowingRunnable body)
            throws Exception {
        String priorDimension = System.getProperty(DIMENSION_KEY);
        String priorPixels = System.getProperty(PIXELS_KEY);
        try {
            setOrClear(DIMENSION_KEY, dimension);
            setOrClear(PIXELS_KEY, pixels);
            body.run();
        } finally {
            setOrClear(DIMENSION_KEY, priorDimension);
            setOrClear(PIXELS_KEY, priorPixels);
        }
    }

    private static void setOrClear(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void overPerAxisLimitIsRefusedAsUsageNotAsUnreadable(@TempDir Path tmp) throws Exception {
        withLimits("100", null, () -> {
            Path wide = png(tmp, "wide.png", 101, 50);      // 101 > 100 on one axis only
            Path fine = png(tmp, "fine.png", 50, 50);
            int code = Main.run(new String[] {"diff", wide.toString(), fine.toString()});
            assertEquals(EXIT_USAGE, code,
                "an over-limit input is a usage refusal (2), not a decode failure (1)");
        });
    }

    @Test
    void theAreaCapIsIndependentOfThePerAxisCap(@TempDir Path tmp) throws Exception {
        // 40x40 clears the per-axis cap of 100 comfortably, so if this refuses, it can
        // only be the area cap doing it — the two ceilings are not the same check.
        withLimits("100", "1000", () -> {
            Path square = png(tmp, "square.png", 40, 40);   // 1600 px > 1000 area cap
            Path fine = png(tmp, "fine.png", 20, 20);
            assertEquals(EXIT_USAGE,
                Main.run(new String[] {"diff", square.toString(), fine.toString()}),
                "an input under the per-axis cap must still be refused on total area");
        });
    }

    @Test
    void theBoundaryIsInclusiveOnBothCeilings(@TempDir Path tmp) throws Exception {
        // Exactly-at-limit on BOTH axes and exactly-at-limit on area. An off-by-one in
        // either comparison (>= instead of >) turns this legitimate input into a refusal.
        withLimits("100", "10000", () -> {
            Path a = png(tmp, "a.png", 100, 100);
            Path b = png(tmp, "b.png", 100, 100);
            assertEquals(EXIT_CLEAN, Main.run(new String[] {"diff", a.toString(), b.toString()}),
                "100x100 under caps of 100/axis and 10000 area is AT the limit, not over it");
        });
    }

    @Test
    void oneOverTheAreaCapIsRefusedWhileExactlyAtItPasses(@TempDir Path tmp) throws Exception {
        // The tightest possible discriminator on the area comparison: same fixtures,
        // the cap moved by exactly one pixel.
        Path a = png(tmp, "a.png", 100, 100);
        Path b = png(tmp, "b.png", 100, 100);
        withLimits("100", "10000", () ->
            assertEquals(EXIT_CLEAN, Main.run(new String[] {"diff", a.toString(), b.toString()}),
                "area exactly at the cap must pass"));
        withLimits("100", "9999", () ->
            assertEquals(EXIT_USAGE, Main.run(new String[] {"diff", a.toString(), b.toString()}),
                "the same input one pixel over the cap must be refused"));
    }

    @Test
    void ceilingsAreReReadRatherThanCachedAcrossRuns(@TempDir Path tmp) throws Exception {
        // A static-final read of the system property would make the SECOND run here
        // inherit the first run's ceiling and fail.
        Path a = png(tmp, "a.png", 200, 200);
        Path b = png(tmp, "b.png", 200, 200);
        withLimits("100", null, () ->
            assertEquals(EXIT_USAGE, Main.run(new String[] {"diff", a.toString(), b.toString()}),
                "a tight ceiling refuses the 200px input"));
        withLimits("16384", null, () ->
            assertEquals(EXIT_CLEAN, Main.run(new String[] {"diff", a.toString(), b.toString()}),
                "raising the ceiling in the same JVM must take effect immediately"));
    }

    @Test
    void aNonImageIsStillReportedAsUnreadableNotAsOverLimit(@TempDir Path tmp) throws Exception {
        // The guard must not widen its own jurisdiction: a file with no registered
        // reader has no declared dimensions to judge, so it belongs to readImage.
        withLimits("100", "1000", () -> {
            Path notAnImage = tmp.resolve("notes.txt");
            Files.writeString(notAnImage, "this is not a png");
            Path fine = png(tmp, "fine.png", 10, 10);
            int code = Main.run(new String[] {"diff", notAnImage.toString(), fine.toString()});
            assertEquals(EXIT_UNREADABLE, code,
                "an undecodable file stays exit 1 — the size guard must not claim it");
        });
    }

    @Test
    void anOversizedInputIsRefusedWithoutAllocatingItsRaster(@TempDir Path tmp) throws Exception {
        // THE point of the guard. A PNG that DECLARES 40000x40000 is a few hundred bytes
        // on disk but 6.4e9 pixels decoded — ImageIO.read would attempt the allocation
        // before failing. Under the default 16384/axis ceiling this must be refused from
        // the header, fast, and without an OutOfMemoryError.
        Path bomb = declaredSizePng(tmp.resolve("bomb.png"), 40_000, 40_000);
        Path fine = png(tmp, "fine.png", 10, 10);
        assertTrue(Files.size(bomb) < 4096,
            "fixture must be a small file declaring a huge image, else it proves nothing");

        long startedAtNanos = System.nanoTime();
        int code = Main.run(new String[] {"diff", bomb.toString(), fine.toString()});
        long elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000L;

        assertEquals(EXIT_USAGE, code, "a declared-huge input must be refused as a usage error");
        assertTrue(elapsedMillis < 5_000L,
            "refusal must come from the header, not from attempting the decode (took "
                + elapsedMillis + " ms)");
    }

    @Test
    void anOversizedSecondJobIsRefusedBeforeTheFirstJobWritesAnything(@TempDir Path tmp)
            throws Exception {
        // Batch preflight: if the guard ran per-job inside the loop instead of ahead of
        // it, job 1 would already have written its sidecar by the time job 2 is refused.
        Path a = png(tmp, "a.png", 20, 20);
        Path b = png(tmp, "b.png", 20, 20);
        Path bomb = declaredSizePng(tmp.resolve("bomb.png"), 40_000, 40_000);
        Path sidecar = tmp.resolve("verdict.json");

        Main.DiffJob clean = new Main.DiffJob(
            a, b, BrewShotDiff.Options.defaults(), null, null, null, sidecar);
        Main.DiffJob oversized = new Main.DiffJob(
            bomb, a, BrewShotDiff.Options.defaults(), null, null, null, null);

        int code = Main.runDiffJobs(java.util.List.of(clean, oversized));

        assertEquals(EXIT_USAGE, code, "the batch is refused for the oversized job");
        assertTrue(Files.notExists(sidecar),
            "the first job must not have written its sidecar before the batch was refused");
    }

    @Test
    void theRefusalNamesTheOffendingFileAndTheOverridableProperty(@TempDir Path tmp)
            throws Exception {
        // An operator hitting a legitimate large-image case needs to know WHICH input
        // and WHICH knob, or the guard just looks like a bug.
        withLimits("100", null, () -> {
            Path wide = png(tmp, "wide.png", 101, 50);
            Path fine = png(tmp, "fine.png", 50, 50);
            String stderr = captureStderr(() ->
                Main.run(new String[] {"diff", wide.toString(), fine.toString()}));
            assertTrue(stderr.contains("wide.png"),
                "the message must name the offending input, got: " + stderr);
            assertTrue(stderr.contains(DIMENSION_KEY),
                "the message must name the property that raises the ceiling, got: " + stderr);
            assertNotEquals("", stderr.trim(), "a refusal must explain itself");
        });
    }

    /**
     * A minimal, valid PNG whose IHDR DECLARES {@code width x height} while the file
     * itself stays tiny. Built by hand because every encoder wants the real raster
     * first — which is precisely the allocation this guard exists to avoid.
     */
    private static Path declaredSizePng(Path target, int width, int height) throws IOException {
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        java.io.ByteArrayOutputStream ihdr = new java.io.ByteArrayOutputStream();
        writeInt(ihdr, width);
        writeInt(ihdr, height);
        ihdr.write(8);          // bit depth
        ihdr.write(2);          // colour type: truecolour
        ihdr.write(0);          // compression
        ihdr.write(0);          // filter
        ihdr.write(0);          // interlace
        writeChunk(body, "IHDR", ihdr.toByteArray());
        writeChunk(body, "IEND", new byte[0]);
        Files.write(target, body.toByteArray());
        return target;
    }

    private static void writeChunk(java.io.ByteArrayOutputStream out, String type, byte[] data)
            throws IOException {
        writeInt(out, data.length);
        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(typeBytes);
        out.write(data);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
    }

    private static void writeInt(java.io.ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static String captureStderr(ThrowingRunnable body) throws Exception {
        java.io.PrintStream original = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        try {
            System.setErr(new java.io.PrintStream(captured, true,
                java.nio.charset.StandardCharsets.UTF_8));
            body.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
