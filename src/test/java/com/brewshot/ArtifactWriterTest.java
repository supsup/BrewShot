package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shared artifact-write policy (review brewshot/157). These assert the property that
 * actually matters on failure — the DESTINATION is never left holding partial or destroyed
 * content — rather than merely that a temp file was used.
 */
class ArtifactWriterTest {

    /** Files the policy is allowed to leave behind in the output directory. */
    private static List<String> strays(Path dir, String... artifacts) throws IOException {
        List<String> expected = List.of(artifacts);
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString())
                .filter(n -> !expected.contains(n))
                .toList();
        }
    }

    @Test
    void aFailedWriteLeavesThePreviousArtifactIntact(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("shot.png");
        Files.writeString(target, "PREVIOUS-GOOD-ARTIFACT");

        assertThrows(IOException.class, () -> ArtifactWriter.write(target, ".tmp", tmp -> {
            Files.writeString(tmp, "half-written garba");
            throw new IOException("simulated capture failure mid-write");
        }), "the producer's failure must propagate, not be swallowed");

        assertEquals("PREVIOUS-GOOD-ARTIFACT", Files.readString(target),
            "a failed re-record must not corrupt OR delete the previous good artifact —"
                + " the defect GifWriter had, where the failure path deleted the destination");
        assertEquals(List.of(), strays(dir, "shot.png"),
            "the temp file must be cleaned up after a failed write");
    }

    @Test
    void aFailedFirstWriteLeavesNoArtifactAtAll(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("first.png");

        assertThrows(IOException.class, () -> ArtifactWriter.write(target, ".tmp", tmp -> {
            Files.writeString(tmp, "partial");
            throw new IOException("simulated failure");
        }));

        assertTrue(Files.notExists(target),
            "a failed FIRST write must not leave a truncated file that reads as a"
                + " finished artifact");
        assertEquals(List.of(), strays(dir, "first.png"), "no temp litter");
    }

    @Test
    void successfulWritesReplaceContentAndLeaveNoLitter(@TempDir Path dir) throws Exception {
        Path bytes = dir.resolve("a.bin");
        Files.writeString(bytes, "old");
        ArtifactWriter.writeBytes(bytes, new byte[] {1, 2, 3});
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(bytes));
        assertEquals(List.of(), strays(dir, "a.bin"));

        Path text = dir.resolve("b.json");
        ArtifactWriter.writeString(text, "{\"ok\":true}");
        assertEquals("{\"ok\":true}", Files.readString(text));
        assertEquals(List.of(), strays(dir, "a.bin", "b.json"));
    }

    /**
     * The GIF lane is the one newly-routed writer that is testable browser-free (screenshot
     * and PDF need Chrome). A real recording must land a valid artifact and leave the output
     * directory clean — proving the encode genuinely goes through the temp-then-move path.
     */
    @Test
    void gifRecordingLandsAtomicallyWithoutLitter(@TempDir Path dir) throws Exception {
        Path gif = dir.resolve("out.gif");
        List<byte[]> frames = List.of(png(dir, 1), png(dir, 2));
        GifWriter.write(frames, 40, gif);

        assertTrue(Files.size(gif) > 0, "a recording must land a non-empty GIF");
        byte[] head = Files.readAllBytes(gif);
        assertEquals('G', head[0] & 0xFF);
        assertEquals('I', head[1] & 0xFF);
        assertEquals('F', head[2] & 0xFF);
        assertEquals(List.of("out.gif"), Files.list(dir)
                .map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".gif")).toList(),
            "no temp .gif litter beside the finished recording");
    }

    /** A tiny distinct PNG frame. */
    private static byte[] png(Path dir, int shade) throws IOException {
        java.awt.image.BufferedImage img =
            new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(new java.awt.Color(shade * 60, shade * 30, shade * 10));
        g.fillRect(0, 0, 4, 4);
        g.dispose();
        Path p = Files.createTempFile(dir, "frame", ".png");
        javax.imageio.ImageIO.write(img, "png", p.toFile());
        byte[] bytes = Files.readAllBytes(p);
        Files.delete(p);
        return bytes;
    }
}
