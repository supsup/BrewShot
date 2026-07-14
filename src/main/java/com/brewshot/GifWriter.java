package com.brewshot;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

/**
 * Animated-GIF assembly from PNG frames using only the JDK's ImageIO GIF
 * writer — no dependency (the BrewShot discipline: everything rides what the
 * JDK already ships). Loops forever; per-frame delay in milliseconds.
 */
final class GifWriter {

    private GifWriter() { }

    /** Write {@code pngFrames} as a looping animated GIF at {@code out}. */
    static void write(List<byte[]> pngFrames, int frameDelayMs, Path out) throws IOException {
        write(pngFrames, frameDelayMs, frameDelayMs, out);
    }

    /**
     * Write a looping GIF where the FIRST frame is held for
     * {@code firstFrameDelayMs} and every subsequent frame plays at
     * {@code frameDelayMs} — so a viewer registers the opening state before the
     * animation runs. Pass {@code firstFrameDelayMs == frameDelayMs} for a
     * uniform GIF.
     */
    static void write(List<byte[]> pngFrames, int frameDelayMs, int firstFrameDelayMs, Path out)
            throws IOException {
        if (pngFrames.isEmpty()) { throw new IllegalArgumentException("no frames"); }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (ImageOutputStream os = ImageIO.createImageOutputStream(out.toFile())) {
            writer.setOutput(os);
            writer.prepareWriteSequence(null);
            for (int i = 0; i < pngFrames.size(); i++) {
                // ImageIO.read returns NULL (not throws) when no reader recognizes the
                // bytes — a bare createFromRenderedImage(null) NPE would then kill the whole
                // sequence at write time (the most expensive moment) with no attribution.
                // Fail loud WITH the frame index instead; never silently skip — a dropped
                // frame silently changes the GIF's timing/duration, a worse lie.
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngFrames.get(i)));
                if (img == null) {
                    throw new IOException("frame " + i + " of " + pngFrames.size()
                        + " is not a decodable image (" + pngFrames.get(i).length + " bytes)");
                }
                IIOMetadata meta = writer.getDefaultImageMetadata(
                    ImageTypeSpecifier.createFromRenderedImage(img),
                    writer.getDefaultWriteParam());
                applyFrameMetadata(meta, i == 0 ? firstFrameDelayMs : frameDelayMs);
                writer.writeToSequence(new IIOImage(img, null, meta), null);
            }
            writer.endWriteSequence();
        } catch (IOException | RuntimeException e) {
            // The try-with-resources closes the stream on the way out, leaving a PARTIAL
            // out file (N-1 frames) that reads as a plausible truncated GIF. Delete it so a
            // broken recording never masquerades as a finished artifact.
            try { java.nio.file.Files.deleteIfExists(out); } catch (IOException ignored) { }
            throw e;
        } finally {
            writer.dispose();
        }
    }

    /** Stamp GraphicControl (delay) + Netscape loop-forever onto a frame. */
    private static void applyFrameMetadata(IIOMetadata meta, int delayMs) throws IOException {
        String fmt = meta.getNativeMetadataFormatName(); // javax_imageio_gif_image_1.0
        IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(fmt);

        IIOMetadataNode gce = child(root, "GraphicControlExtension");
        gce.setAttribute("disposalMethod", "none");
        gce.setAttribute("userInputFlag", "FALSE");
        gce.setAttribute("transparentColorFlag", "FALSE");
        gce.setAttribute("transparentColorIndex", "0");
        gce.setAttribute("delayTime", String.valueOf(Math.max(2, delayMs / 10))); // centiseconds

        IIOMetadataNode apps = child(root, "ApplicationExtensions");
        IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
        app.setAttribute("applicationID", "NETSCAPE");
        app.setAttribute("authenticationCode", "2.0");
        app.setUserObject(new byte[] {1, 0, 0}); // loop count 0 = forever
        apps.appendChild(app);

        meta.setFromTree(fmt, root);
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(name)) {
                return (IIOMetadataNode) root.item(i);
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }
}
