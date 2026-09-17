package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.ImageInputStream;
import org.junit.jupiter.api.Test;

/**
 * brewshot/639 item 5: a census over header shapes, asserting the decoded-cost accounting
 * never charges LESS than the raster it is bounding.
 *
 * <p>SEPARATE FROM {@code BrewShotResourceCapsTest} deliberately. A census asks one question
 * of a whole corpus; the cap tests are discriminators over single cases. Keeping them apart
 * also means the flat-4 mutant reddens exactly ONE test per class, each with its own
 * diagnostic, so each can be proven through {@code ai review mutate} -- which refuses to call
 * a mutation killed when the selected class fails in two different voices.</p>
 */
class GifDecodeChargeCensusTest {

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

}
