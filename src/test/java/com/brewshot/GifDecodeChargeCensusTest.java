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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
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
     * THE ACCOUNTING NEVER CHARGES LESS THAN THE RASTER ACTUALLY COSTS (brewshot/639 item 5).
     *
     * <p>Driven THROUGH {@code enforceDecodeBounds}, which is the thing that charges. For each
     * header shape: decode it, measure the {@code DataBuffer} it really occupies, then set
     * {@code maxDecodedBytes} to one byte BELOW that and require a refusal. A refusal there
     * says charge &gt;= actual, which is the whole property, and the refusal text carries the
     * per-pixel figure the production path derived so the evidence table is production-sourced
     * rather than recomputed here.</p>
     *
     * <p>THE FIRST VERSION OF THIS TEST CALLED {@code bytesPerPixelOf} DIRECTLY AND THE FLAT-4
     * MUTANT SURVIVED IT. It validated the per-pixel table while never executing the line that
     * multiplies by it -- a census of the callee standing in for the caller. `ai review mutate`
     * returned survived where my own reading had called it covered, which is the argument for
     * the rail in one line.</p>
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
        List<String> table = new ArrayList<>();
        String previousLimit = System.getProperty(MAX_DECODED);
        String previousDim = System.getProperty(MAX_DIMENSION);
        try {
            System.setProperty(MAX_DIMENSION, "4096");
            for (Map.Entry<String, byte[]> row : corpus.entrySet()) {
                long actual = decodedRasterBytes(row.getValue());
                System.setProperty(MAX_DECODED, String.valueOf(actual - 1));
                List<byte[]> one = List.of(row.getValue());
                String refusal = null;
                try {
                    GifWriter.enforceDecodeBounds(one);
                } catch (IOException refused) {
                    refusal = refused.getMessage();
                }
                if (refusal == null) {
                    under.add(row.getKey() + ": admitted at a budget of " + (actual - 1)
                        + ", so it was charged less than the " + actual
                        + " bytes its raster actually occupies");
                    continue;
                }
                long charged = (long) w * h * perPixelFrom(refusal);
                if (charged < actual) {
                    under.add(row.getKey() + ": charged " + charged + " < actual " + actual);
                }
                table.add(String.format("%-20s charged %-6d actual %-6d %.2fx",
                    row.getKey(), charged, actual, charged / (double) actual));
            }
        } finally {
            restore(MAX_DECODED, previousLimit);
            restore(MAX_DIMENSION, previousDim);
        }

        assertTrue(under.isEmpty(), "every header shape must be charged at or above what it "
            + "actually decodes to; under-charged rows: " + under + "\nfull table:\n"
            + String.join("\n", table));
        assertEquals(10, corpus.size(), "the census covers ten header shapes; adding one is "
            + "deliberate and this number moves with it");
        assertEquals(10, table.size(), "and every one of them produced a measured row");
    }

    private static final String MAX_DECODED = "brewshot.gif.maxDecodedBytes";
    private static final String MAX_DIMENSION = "brewshot.gif.maxFrameDimension";

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    /**
     * The per-pixel charge the refusal reports, which is the figure the PRODUCTION path
     * derived. Parsed rather than recomputed so this census cannot agree with itself.
     */
    private static int perPixelFrom(String refusal) {
        Matcher m = Pattern.compile("at (\\d+) bytes/pixel").matcher(refusal);
        assertTrue(m.find(), "the refusal names its per-pixel charge: " + refusal);
        return Integer.parseInt(m.group(1));
    }

    private static byte[] encoded(int w, int h, int type, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(new BufferedImage(w, h, type), format, out)) {
            throw new IOException("no " + format + " writer accepted this image type");
        }
        return out.toByteArray();
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
