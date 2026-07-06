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
        if (pngFrames.isEmpty()) { throw new IllegalArgumentException("no frames"); }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (ImageOutputStream os = ImageIO.createImageOutputStream(out.toFile())) {
            writer.setOutput(os);
            writer.prepareWriteSequence(null);
            for (byte[] png : pngFrames) {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
                IIOMetadata meta = writer.getDefaultImageMetadata(
                    ImageTypeSpecifier.createFromRenderedImage(img),
                    writer.getDefaultWriteParam());
                applyFrameMetadata(meta, frameDelayMs);
                writer.writeToSequence(new IIOImage(img, null, meta), null);
            }
            writer.endWriteSequence();
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
