package com.brewshot;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link GifWriter} robustness (plan 04bb9898, downtime finding brewshot/63, Fixpoint-
 * confirmed brewshot/65). A single undecodable frame must fail LOUD with the frame index
 * (never a bare NPE, never a silent skip that changes timing), and must NOT leave a
 * partial/truncated GIF on disk masquerading as a finished artifact.
 */
class GifWriterTest {

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
}
