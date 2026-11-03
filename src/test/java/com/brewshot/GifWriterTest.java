package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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

    /**
     * Case 3, Stage A: this names the proposed real-writer receipt/observer overload.
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
