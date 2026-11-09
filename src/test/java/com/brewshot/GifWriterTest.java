package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Node;

/**
 * {@link GifWriter} robustness (plan 04bb9898, downtime finding brewshot/63, Fixpoint-
 * confirmed brewshot/65). A single undecodable frame must fail LOUD with the frame index
 * (never a bare NPE, never a silent skip that changes timing), and must NOT leave a
 * partial/truncated GIF on disk masquerading as a finished artifact.
 */
class GifWriterTest {

    @Test
    void scalarSinkUsesLowEightBitsAndTheBulkWritePath() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int[] writes = {0}, closes = {0};
        OutputStream target = new OutputStream() {
            @Override public void write(int value) { throw new AssertionError("scalar bypassed bulk path"); }
            @Override public void write(byte[] values, int offset, int length) {
                writes[0]++;
                bytes.write(values, offset, length);
            }
            @Override public void close() { closes[0]++; }
        };
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        var sink = new GifWriter.CountingDigestSink(target, digest);
        sink.write(-1);
        sink.write(0x100);
        sink.write(0x1234);
        sink.write(new byte[] {8, 9, 10, 11}, 1, 2);
        sink.flush();
        assertThrows(IOException.class, sink::completedCount, "open is not completed");
        sink.close();
        sink.close();
        byte[] expected = {(byte) 0xff, 0, 0x34, 9, 10};
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, bytes.toByteArray());
        assertEquals(4, writes[0], "each scalar must reach the same bulk path");
        assertEquals(1, closes[0], "close remains idempotent");
        assertEquals(5L, sink.completedCount());
        assertEquals(ArtifactWriterTest.receiptHash(expected),
            java.util.HexFormat.of().formatHex(digest.digest()));
        assertThrows(IOException.class, () -> sink.write(1));
        assertThrows(IOException.class, () -> sink.write(new byte[] {1}, 0, 1));
        assertThrows(IOException.class, sink::flush);
    }

    @Test
    void failedScalarWriteDoesNotHashUnacceptedBytesAndCannotPublishAReceipt() throws Exception {
        IOException rejected = new IOException("sink-write-witness");
        int[] writes = {0}, closes = {0};
        OutputStream target = new OutputStream() {
            @Override public void write(int value) { throw new AssertionError("scalar bypass"); }
            @Override public void write(byte[] values, int offset, int length) throws IOException {
                writes[0]++;
                throw rejected;
            }
            @Override public void close() { closes[0]++; }
        };
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        var sink = new GifWriter.CountingDigestSink(target, digest);
        org.junit.jupiter.api.Assertions.assertSame(rejected,
            assertThrows(IOException.class, () -> sink.write(0x1ff)));
        assertEquals(ArtifactWriterTest.receiptHash(new byte[0]),
            java.util.HexFormat.of().formatHex(digest.digest()), "hash only successful writes");
        assertThrows(IOException.class, () -> sink.write(2));
        assertThrows(IOException.class, () -> sink.write(new byte[] {2}, 0, 1));
        assertThrows(IOException.class, sink::flush);
        assertEquals(1, writes[0], "a failed sink must not retry the target");
        sink.close();
        sink.close();
        assertEquals(1, closes[0], "even a failed sink closes its target once");
        assertThrows(IOException.class, sink::completedCount);
    }

    @Test
    void flushAndCloseFailuresCannotBecomeCompletedSinkReceipts() throws Exception {
        for (boolean failFlush : new boolean[] {false, true}) {
            IOException rejected = new IOException(failFlush ? "flush-witness" : "close-witness");
            int[] closes = {0};
            OutputStream target = new OutputStream() {
                @Override public void write(int value) { }
                @Override public void flush() throws IOException { if (failFlush) throw rejected; }
                @Override public void close() throws IOException {
                    closes[0]++;
                    if (!failFlush) throw rejected;
                }
            };
            var sink = new GifWriter.CountingDigestSink(target,
                java.security.MessageDigest.getInstance("SHA-256"));
            sink.write(7);
            if (failFlush) {
                org.junit.jupiter.api.Assertions.assertSame(rejected, assertThrows(IOException.class, sink::flush));
                sink.close();
            } else {
                sink.flush();
                org.junit.jupiter.api.Assertions.assertSame(rejected, assertThrows(IOException.class, sink::close));
            }
            sink.close();
            assertEquals(1, closes[0]);
            assertThrows(IOException.class, sink::completedCount);
            assertThrows(IOException.class, () -> sink.write(8));
        }
    }

    @Test
    void scalarSinkCountsRemainLongAndOverflowPoisonsTheReceipt() throws Exception {
        var sink = new GifWriter.CountingDigestSink(OutputStream.nullOutputStream(),
            java.security.MessageDigest.getInstance("SHA-256"));
        // Seed the private counter at the boundary without allocating an enormous artifact.
        var count = GifWriter.CountingDigestSink.class.getDeclaredField("count");
        count.setAccessible(true);
        count.setLong(sink, Long.MAX_VALUE - 1);
        sink.write(1);
        assertEquals(Long.MAX_VALUE, count.getLong(sink));
        assertThrows(ArithmeticException.class, () -> sink.write(2));
        sink.close();
        assertThrows(IOException.class, sink::completedCount, "overflow must not wrap into a receipt");
    }

    /**
     * The real-writer receipt/observer overload exposes encoded-stream progress.
     * The observer sees the production stream after writes and flushes, including before
     * eager discard; it does not supply a replacement encoder or test-local hashing stream.
     */
    @Test
    void realGifReceiptMatchesLegacyBytesAndItsEncodedCacheStaysBounded(
            @TempDir Path directory) throws Exception {
        List<byte[]> frames = new java.util.ArrayList<>();
        int random = 0x13579bdf;
        for (int frame = 0; frame < 16; frame++) {
            BufferedImage image = new BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 96; y++) for (int x = 0; x < 96; x++) {
                random ^= random << 13; random ^= random >>> 17; random ^= random << 5;
                image.setRGB(x, y, random & 0xffffff);
            }
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(image, "png", encoded));
            frames.add(encoded.toByteArray());
        }
        Path legacy = directory.resolve("legacy.gif");
        GifWriter.write(frames, 25, 75, legacy);
        byte[] legacyBytes = Files.readAllBytes(legacy);
        assertTrue(legacyBytes.length > 32_768,
            "INSTRUMENT: the real encoding must pressure more than the allowed encoded cache");

        Path venue = Files.createDirectory(directory.resolve("receipt"));
        Path out = venue.resolve("capture.gif");
        EncodedCacheCensus census = new EncodedCacheCensus();
        var receipt = ArtifactWriterTest.withoutArtifactReads(venue,
            () -> GifWriter.writeWithReceipt(frames, 25, 75, out, true, census::observe));
        byte[] actual = Files.readAllBytes(out); // independent oracle AFTER producer/recording stop
        org.junit.jupiter.api.Assertions.assertArrayEquals(legacyBytes, actual,
            "opt-in must preserve palette, metadata, frame order, delays and trailer bytes");
        assertEquals(actual.length, receipt.outBytes());
        assertEquals(ArtifactWriterTest.receiptHash(actual), receipt.outSha256());
        assertEquals(0x3b, actual[actual.length - 1] & 0xff, "the digest includes the final trailer");
        assertTrue(census.calls > frames.size(), "INSTRUMENT: observer must reach the real stream");
        assertTrue(census.scalarSteps > 0 && census.chunkSteps > 0,
            "INSTRUMENT: real scalar AND bulk writes must be observed");
        assertTrue(census.peak > 0 && census.peak <= 16_384,
            "physical retained encoded payload, not logical pending bytes: " + census.peak);
        assertTrue(census.backwardChecks > 0, "INSTRUMENT: backward-seek guard must be reached");
        assertEquals("8", encodedDelay(out, 0));
        assertEquals("3", encodedDelay(out, 1));
    }

    private static final class EncodedCacheCensus {
        long calls, lastPosition, scalarSteps, chunkSteps, peak, backwardChecks;

        void observe(ImageOutputStream stream) throws IOException {
            calls++;
            assertTrue(stream instanceof MemoryCacheImageOutputStream,
                "INSTRUMENT: observe the actual bounded memory stream, not an unrelated counter");
            long position = stream.getStreamPosition();
            long step = position - lastPosition;
            if (step == 1) scalarSteps++;
            if (step > 1) chunkSteps++;
            lastPosition = position;
            try {
                var cacheField = MemoryCacheImageOutputStream.class.getDeclaredField("cache");
                cacheField.setAccessible(true);
                Object cache = cacheField.get(stream);
                var blocksField = cache.getClass().getDeclaredField("cache");
                blocksField.setAccessible(true);
                Object value = blocksField.get(cache);
                assertTrue(value instanceof List<?>, "INSTRUMENT: unfamiliar JDK cache representation");
                long retained = 0;
                for (Object block : (List<?>) value) {
                    if (block == null) continue;
                    assertTrue(block instanceof byte[], "INSTRUMENT: unfamiliar retained block");
                    retained += ((byte[]) block).length;
                }
                peak = Math.max(peak, retained);
                assertTrue(retained <= 16_384,
                    "encoded cache retains " + retained + " bytes at stream position " + position);
            } catch (ReflectiveOperationException | java.lang.reflect.InaccessibleObjectException unavailable) {
                throw new AssertionError("INSTRUMENT: supply the declared java.desktop test opens", unavailable);
            }
            if (position > 0) {
                // The actual production stream must refuse even an unflushed backwards move:
                // hashing a prefix and subsequently editing it would no longer attest the file.
                assertThrows(IOException.class, () -> stream.seek(position - 1),
                    "a receipt stream must fail loudly rather than accept a backwards seek");
                backwardChecks++;
                assertEquals(position, stream.getStreamPosition(), "refusal must not move the cursor");
            }
        }
    }

    /** A minimal, genuinely-decodable PNG frame. */
    private static byte[] validPng(int rgb) throws IOException {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                img.setRGB(x, y, rgb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void validFramesWriteAGif(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("ok.gif");
        GifWriter.write(List.of(validPng(0xff0000), validPng(0x00ff00)), 100, out);
        assertTrue(Files.exists(out) && Files.size(out) > 0, "a valid sequence writes a GIF");
    }

    @Test
    void anUndecodableFrameMidSequenceFailsWithItsIndexAndLeavesNoPartialFile(@TempDir Path dir)
            throws IOException {
        // A valid frame, then garbage, then another valid frame: the write reaches frame 1,
        // ImageIO.read returns null, and the writer must throw naming index 1 — NOT a bare
        // NPE, and NOT silently drop the frame (which would change the GIF's timing).
        Path out = dir.resolve("broken.gif");
        List<byte[]> frames = List.of(validPng(0xff0000), new byte[] {1, 2, 3, 4}, validPng(0x00ff00));

        IOException ex = assertThrows(IOException.class, () -> GifWriter.write(frames, 100, out));
        assertTrue(ex.getMessage().contains("frame 1"),
            "the exception names the offending frame index: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("of 3"),
            "and the sequence size for context: " + ex.getMessage());

        // The partial output (frame 0 already written before the crash) must be deleted, so a
        // downstream consumer never sees a plausible-looking truncated GIF.
        assertFalse(Files.exists(out),
            "a failed write leaves no partial GIF masquerading as finished");
    }

    @Test
    void anEmptyFrameListIsRejected(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class,
            () -> GifWriter.write(List.of(), 100, dir.resolve("empty.gif")));
    }

    @Test
    void representativeDelaysEncodeAtRoundedCentisecondGranularity(@TempDir Path dir)
            throws IOException {
        int[][] cases = {
            {1, 2}, {19, 2}, {20, 2}, {25, 3}, {75, 8}, {76, 8},
        };
        for (int[] testCase : cases) {
            int requestedMs = testCase[0];
            Path out = dir.resolve("rounded-" + requestedMs + ".gif");
            GifWriter.write(List.of(validPng(0xff0000)), requestedMs, out);
            assertEquals(Integer.toString(testCase[1]), encodedDelay(out, 0),
                requestedMs + "ms encoded delay");
        }
    }

    @Test
    void firstFrameAndFollowingFramesBothUseRoundedDelays(@TempDir Path dir)
            throws IOException {
        Path out = dir.resolve("poster-delay.gif");
        GifWriter.write(
            List.of(validPng(0xff0000), validPng(0x00ff00)), 25, 75, out);

        assertEquals("8", encodedDelay(out, 0), "75ms poster hold rounds to 80ms");
        assertEquals("3", encodedDelay(out, 1), "25ms frame delay rounds to 30ms");
    }

    @Test
    void invalidSequenceCannotOverwriteAnExistingCompleteArtifact(@TempDir Path dir)
            throws IOException {
        Path out = dir.resolve("existing.gif");
        Files.writeString(out, "known-good-old-artifact");

        assertThrows(IOException.class,
            () -> GifWriter.write(List.of(validPng(0xff0000), new byte[] {1, 2}), 40, out));

        assertEquals("known-good-old-artifact", Files.readString(out));
    }

    private static Node find(Node node, String name) {
        if (name.equals(node.getNodeName())) {
            return node;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            Node found = find(child, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String encodedDelay(Path gif, int frameIndex) throws IOException {
        try (var input = ImageIO.createImageInputStream(gif.toFile())) {
            var reader = ImageIO.getImageReaders(input).next();
            try {
                reader.setInput(input);
                var metadata = reader.getImageMetadata(frameIndex);
                Node root = metadata.getAsTree(metadata.getNativeMetadataFormatName());
                IIOMetadataNode control =
                    (IIOMetadataNode) find(root, "GraphicControlExtension");
                return control.getAttribute("delayTime");
            } finally {
                reader.dispose();
            }
        }
    }
}
