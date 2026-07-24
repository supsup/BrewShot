package com.brewshot;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import javax.imageio.ImageIO;

/**
 * The ONE temp-then-atomic write policy for every artifact BrewShot lands on disk.
 *
 * <p>Review brewshot/157 found the policy applied per-site rather than as a class: the diff
 * sidecars and Main-owned clip captures were atomic while the ordinary screenshot, the PDF,
 * and the GIF still wrote straight to their declared paths — so a crash, a full disk, or a
 * concurrent reader could leave a truncated file that reads as a finished artifact, and the
 * README's "each artifact the CLI writes" claim was inaccurate. Centralizing it here means a
 * new output format inherits the guarantee instead of having to remember it.
 *
 * <p>Every write lands in a sibling temp file in the SAME directory (ATOMIC_MOVE cannot cross
 * filesystems) and is then moved into place. The consequence that matters on failure: the
 * destination is never touched at all, so a failed re-record leaves the PREVIOUS good artifact
 * intact rather than deleting it.
 */
final class ArtifactWriter {

    private ArtifactWriter() { }

    /** Produces artifact content into a caller-supplied temp path. */
    @FunctionalInterface
    interface Producer {
        void writeTo(Path tmp) throws IOException;
    }

    /**
     * Run {@code producer} against a sibling temp file, then atomically move it onto
     * {@code target}. The temp is removed if the producer or the move fails; {@code target}
     * is left untouched in that case.
     */
    static void write(Path target, String suffix, Producer producer) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(dir, ".brewshot-", suffix);
        boolean landed = false;
        try {
            producer.writeTo(tmp);
            moveIntoPlace(tmp, target);
            landed = true;
        } finally {
            if (!landed) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // Best effort: a stray temp never corrupts the destination.
                }
            }
        }
    }

    /** Atomically write raw bytes (screenshots, PDFs, clip captures). */
    static void writeBytes(Path target, byte[] body) throws IOException {
        write(target, ".tmp", tmp -> Files.write(tmp, body));
    }

    /** Atomically write text (manifests, diff JSON verdicts). */
    static void writeString(Path target, String body) throws IOException {
        write(target, ".tmp", tmp -> Files.writeString(tmp, body));
    }

    /** Atomically encode and write a PNG (diff heatmaps). */
    static void writePng(BufferedImage img, Path target) throws IOException {
        write(target, ".png", tmp -> {
            if (!ImageIO.write(img, "png", tmp.toFile())) {
                throw new IOException("no PNG writer available");
            }
        });
    }

    /** ATOMIC_MOVE where the filesystem supports it, else a REPLACE_EXISTING move. */
    private static void moveIntoPlace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
