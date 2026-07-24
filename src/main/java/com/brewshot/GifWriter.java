package com.brewshot;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/**
 * Animated-GIF assembly from PNG frames using only the JDK's ImageIO GIF
 * writer — no dependency (the BrewShot discipline: everything rides what the
 * JDK already ships). Loops forever; per-frame delay in milliseconds.
 *
 * <p>Frames share ONE stable global palette. ImageIO's default GIF writer
 * re-quantizes each RGB frame independently, so a gradient lands on a slightly
 * different 256-colour table every frame and FLICKERS. Instead we sample all
 * frames once, compute a single palette by median cut, and hand the writer
 * frames that are already {@code TYPE_BYTE_INDEXED} against that fixed
 * {@link IndexColorModel} — so the writer emits the same colour table for every
 * frame (no re-quantization) and gradients hold steady. Each frame is
 * Floyd–Steinberg dithered against that one palette so banding is diffused
 * consistently rather than shifting frame-to-frame.
 */
final class GifWriter {

    /** Total colours in the shared palette (GIF hard-caps at 256). */
    private static final int MAX_COLORS = 256;
    /** Cap on pixels sampled for palette building, so histogram cost stays bounded. */
    private static final int PALETTE_SAMPLE_BUDGET = 250_000;

    // ----- pre-decode resource bounds (F-02) --------------------------------
    // The honest accounting: the recorders' FrameBudget sums COMPRESSED PNG
    // byte[].length, but write() below retains EVERY frame DECODED (w*h*4 bytes)
    // to build the shared palette and index each — a modest set of compressed
    // PNGs can decode to a multi-GB working set and OOM. And the static
    // BrewShot.gif() entry point fed arbitrary caller frames with no budget at
    // all. So this precheck bounds the DECODED working set — frame count, per-
    // frame axis, and Σ w*h*4 — reading only each PNG's HEADER (never a full
    // pixel array) so an over-budget input fails LOUD and cheap BEFORE the decode
    // loop. Every GIF path (poll recorders, screencast recorder, static gif())
    // funnels through write(), so enforcing here closes them all, the static
    // gif() bypass included. Limits are read FRESH from system properties on each
    // call (so a -D override — or a test's System.setProperty — takes effect).

    /** Default max px per axis for any single GIF frame. -Dbrewshot.gif.maxFrameDimension. */
    static final int DEFAULT_MAX_FRAME_DIMENSION = 4096;
    /** Default max number of frames in one GIF. -Dbrewshot.gif.maxFrames. */
    static final int DEFAULT_MAX_FRAMES = 1000;
    /** Default decoded working-set budget (bytes = Σ w*h*4), 512 MB. -Dbrewshot.gif.maxDecodedBytes. */
    static final long DEFAULT_MAX_DECODED_BYTES = 536_870_912L;

    private static int maxFrameDimension() {
        return intProp("brewshot.gif.maxFrameDimension", DEFAULT_MAX_FRAME_DIMENSION);
    }

    private static int maxFrames() {
        return intProp("brewshot.gif.maxFrames", DEFAULT_MAX_FRAMES);
    }

    private static long maxDecodedBytes() {
        return longProp("brewshot.gif.maxDecodedBytes", DEFAULT_MAX_DECODED_BYTES);
    }

    private static int intProp(String key, int dflt) {
        String v = System.getProperty(key);
        if (v != null) {
            try { int n = Integer.parseInt(v.trim()); if (n > 0) { return n; } }
            catch (NumberFormatException ignored) { /* fall through to default */ }
        }
        return dflt;
    }

    private static long longProp(String key, long dflt) {
        String v = System.getProperty(key);
        if (v != null) {
            try { long n = Long.parseLong(v.trim()); if (n > 0) { return n; } }
            catch (NumberFormatException ignored) { /* fall through to default */ }
        }
        return dflt;
    }

    /**
     * Reject a frame set whose DECODED working set would blow the budget, BEFORE
     * any full decode. Reads only each PNG's header dimensions via an
     * {@link ImageReader} (no pixel array is allocated), so an over-dimension /
     * over-frame-count / over-decoded-budget input fails loud and cheap instead
     * of OOMing in {@link #write}'s decode loop. Package-private so it is unit-
     * testable browser-free.
     */
    static void enforceDecodeBounds(List<byte[]> pngFrames) throws IOException {
        int frameLimit = maxFrames();
        if (pngFrames.size() > frameLimit) {
            throw new IOException("gif refused: " + pngFrames.size()
                + " frames exceeds the limit " + frameLimit + " (brewshot.gif.maxFrames)");
        }
        int maxDim = maxFrameDimension();
        long maxDecoded = maxDecodedBytes();
        long decoded = 0;
        for (int i = 0; i < pngFrames.size(); i++) {
            byte[] png = pngFrames.get(i);
            int w;
            int h;
            try (ImageInputStream iis =
                     ImageIO.createImageInputStream(new ByteArrayInputStream(png))) {
                Iterator<ImageReader> readers =
                    iis == null ? null : ImageIO.getImageReaders(iis);
                if (readers == null || !readers.hasNext()) {
                    throw new IOException("frame " + i + " of " + pngFrames.size()
                        + " is not a decodable image (" + png.length + " bytes)");
                }
                ImageReader reader = readers.next();
                try {
                    reader.setInput(iis, true, true);
                    w = reader.getWidth(0);
                    h = reader.getHeight(0);
                } finally {
                    reader.dispose();
                }
            }
            if (w > maxDim || h > maxDim) {
                throw new IOException("gif refused: frame " + i + " is " + w + "x" + h
                    + ", exceeds max axis " + maxDim + " (brewshot.gif.maxFrameDimension)");
            }
            decoded += (long) w * h * 4;
            if (decoded > maxDecoded) {
                throw new IOException("gif refused: decoded working set reaches " + decoded
                    + " bytes by frame " + i + " of " + pngFrames.size() + ", exceeds "
                    + maxDecoded + " (brewshot.gif.maxDecodedBytes)");
            }
        }
    }

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

        // Bound the DECODED working set BEFORE the decode loop below — header-only
        // inspection, loud on breach. This is the single chokepoint every GIF path
        // (poll recorders, screencast, and the static BrewShot.gif() entry point)
        // funnels through, so it closes the gif() budget-bypass too.
        enforceDecodeBounds(pngFrames);

        // Decode every frame once, up front — we need all of them both to build
        // the shared palette and to index each against it. ImageIO.read returns
        // NULL (not throws) when no reader recognizes the bytes; decoding here,
        // before the palette pass, moves the loud frame-attributed failure ahead
        // of the expensive write and never silently drops a frame (a dropped
        // frame silently changes the GIF's timing/duration, a worse lie).
        List<BufferedImage> frames = new ArrayList<>(pngFrames.size());
        for (int i = 0; i < pngFrames.size(); i++) {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngFrames.get(i)));
            if (img == null) {
                throw new IOException("frame " + i + " of " + pngFrames.size()
                    + " is not a decodable image (" + pngFrames.get(i).length + " bytes)");
            }
            frames.add(img);
        }

        IndexColorModel palette = buildGlobalPalette(frames);

        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (ImageOutputStream os = ImageIO.createImageOutputStream(out.toFile())) {
            writer.setOutput(os);
            writer.prepareWriteSequence(null);
            for (int i = 0; i < frames.size(); i++) {
                BufferedImage indexed = ditherToPalette(frames.get(i), palette);
                IIOMetadata meta = writer.getDefaultImageMetadata(
                    ImageTypeSpecifier.createFromRenderedImage(indexed),
                    writer.getDefaultWriteParam());
                applyFrameMetadata(meta, i == 0 ? firstFrameDelayMs : frameDelayMs);
                writer.writeToSequence(new IIOImage(indexed, null, meta), null);
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

    // ----- global palette (median cut) --------------------------------------

    /**
     * Compute one palette shared by all frames via median cut over a pooled
     * colour histogram. Median cut is the classic GIF quantizer: it recursively
     * splits the colour cube along its longest axis at the population median, so
     * dense regions of colour space get more palette entries. Frames are opaque
     * screenshots (the writer keeps {@code transparentColorFlag=FALSE}), so the
     * palette is fully opaque — no transparency slot is reserved.
     */
    private static IndexColorModel buildGlobalPalette(List<BufferedImage> frames) {
        // Pool a colour histogram across all frames, subsampling with a stride
        // so huge full-page captures don't blow the histogram budget. Sampling
        // only affects palette SELECTION; every pixel is still dithered later.
        long totalPixels = 0;
        for (BufferedImage f : frames) { totalPixels += (long) f.getWidth() * f.getHeight(); }
        int stride = (int) Math.max(1, totalPixels / PALETTE_SAMPLE_BUDGET);

        Map<Integer, int[]> hist = new HashMap<>(); // rgb -> {count}
        for (BufferedImage f : frames) {
            int w = f.getWidth();
            int h = f.getHeight();
            for (long p = 0; p < (long) w * h; p += stride) {
                int x = (int) (p % w);
                int y = (int) (p / w);
                int rgb = f.getRGB(x, y) & 0xFFFFFF;
                hist.computeIfAbsent(rgb, k -> new int[1])[0]++;
            }
        }

        int[] colors = new int[hist.size()];
        int[] counts = new int[hist.size()];
        int n = 0;
        for (Map.Entry<Integer, int[]> e : hist.entrySet()) {
            colors[n] = e.getKey();
            counts[n] = e.getValue()[0];
            n++;
        }

        List<int[]> paletteRgb = medianCut(colors, counts);

        int size = paletteRgb.size();
        byte[] r = new byte[size];
        byte[] g = new byte[size];
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            r[i] = (byte) paletteRgb.get(i)[0];
            g[i] = (byte) paletteRgb.get(i)[1];
            b[i] = (byte) paletteRgb.get(i)[2];
        }
        int bits = Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(1, size - 1)));
        return new IndexColorModel(bits, size, r, g, b);
    }

    /** A box in RGB space owning a contiguous slice of the colour array. */
    private static final class Box {
        int lo;   // inclusive index into the shared colours/counts arrays
        int hi;   // exclusive
        long population;
        Box(int lo, int hi, long population) { this.lo = lo; this.hi = hi; this.population = population; }
        int size() { return hi - lo; }
    }

    /**
     * Median-cut the histogram down to at most {@link #MAX_COLORS} average
     * colours. Splits the box with the largest population first, along its
     * widest channel, at the population median.
     */
    private static List<int[]> medianCut(int[] colors, int[] counts) {
        List<int[]> result = new ArrayList<>();
        if (colors.length == 0) {
            result.add(new int[] {0, 0, 0}); // degenerate: 1-colour palette
            return result;
        }

        long total = 0;
        for (int c : counts) { total += c; }

        List<Box> boxes = new ArrayList<>();
        boxes.add(new Box(0, colors.length, total));

        while (boxes.size() < MAX_COLORS) {
            // Pick the most-populous splittable box.
            Box target = null;
            for (Box box : boxes) {
                if (box.size() > 1 && (target == null || box.population > target.population)) {
                    target = box;
                }
            }
            if (target == null) { break; } // every box is a single colour

            // Widest channel across this box's colours.
            int rMin = 255, rMax = 0, gMin = 255, gMax = 0, bMin = 255, bMax = 0;
            for (int i = target.lo; i < target.hi; i++) {
                int rgb = colors[i];
                int rr = (rgb >> 16) & 0xFF, gg = (rgb >> 8) & 0xFF, bb = rgb & 0xFF;
                rMin = Math.min(rMin, rr); rMax = Math.max(rMax, rr);
                gMin = Math.min(gMin, gg); gMax = Math.max(gMax, gg);
                bMin = Math.min(bMin, bb); bMax = Math.max(bMax, bb);
            }
            int rRange = rMax - rMin, gRange = gMax - gMin, bRange = bMax - bMin;
            int shift = (rRange >= gRange && rRange >= bRange) ? 16 : (gRange >= bRange ? 8 : 0);

            // Sort this slice by the chosen channel (co-sorting counts).
            sortByChannel(colors, counts, target.lo, target.hi, shift);

            // Split at the population median.
            long half = target.population / 2;
            long acc = 0;
            int split = target.lo + 1;
            for (int i = target.lo; i < target.hi - 1; i++) {
                acc += counts[i];
                if (acc >= half) { split = i + 1; break; }
                split = i + 1;
            }

            long leftPop = 0;
            for (int i = target.lo; i < split; i++) { leftPop += counts[i]; }
            Box left = new Box(target.lo, split, leftPop);
            Box right = new Box(split, target.hi, target.population - leftPop);
            boxes.remove(target);
            boxes.add(left);
            boxes.add(right);
        }

        for (Box box : boxes) {
            long wr = 0, wg = 0, wb = 0, pop = 0;
            for (int i = box.lo; i < box.hi; i++) {
                int rgb = colors[i];
                int c = counts[i];
                wr += (long) ((rgb >> 16) & 0xFF) * c;
                wg += (long) ((rgb >> 8) & 0xFF) * c;
                wb += (long) (rgb & 0xFF) * c;
                pop += c;
            }
            if (pop == 0) { pop = 1; }
            result.add(new int[] {(int) (wr / pop), (int) (wg / pop), (int) (wb / pop)});
        }
        return result;
    }

    /** In-place insertion-free quicksort of a colour slice by one channel, co-moving counts. */
    private static void sortByChannel(int[] colors, int[] counts, int lo, int hi, int shift) {
        // Simple iterative quicksort; slices are bounded by histogram size.
        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[] {lo, hi - 1});
        while (!stack.isEmpty()) {
            int[] range = stack.pop();
            int l = range[0], h = range[1];
            if (l >= h) { continue; }
            int pivot = (colors[(l + h) >>> 1] >> shift) & 0xFF;
            int i = l, j = h;
            while (i <= j) {
                while (((colors[i] >> shift) & 0xFF) < pivot) { i++; }
                while (((colors[j] >> shift) & 0xFF) > pivot) { j--; }
                if (i <= j) {
                    int tc = colors[i]; colors[i] = colors[j]; colors[j] = tc;
                    int tn = counts[i]; counts[i] = counts[j]; counts[j] = tn;
                    i++; j--;
                }
            }
            if (l < j) { stack.push(new int[] {l, j}); }
            if (i < h) { stack.push(new int[] {i, h}); }
        }
    }

    // ----- per-frame indexing (Floyd–Steinberg against the fixed palette) ----

    /**
     * Index one frame against the shared palette with Floyd–Steinberg error
     * diffusion. Because the palette is identical for every frame, the diffused
     * banding pattern is stable rather than shifting between frames.
     */
    private static BufferedImage ditherToPalette(BufferedImage src, IndexColorModel palette) {
        int w = src.getWidth();
        int h = src.getHeight();
        int size = palette.getMapSize();
        int[] pr = new int[size];
        int[] pg = new int[size];
        int[] pb = new int[size];
        {
            byte[] r = new byte[size]; byte[] g = new byte[size]; byte[] b = new byte[size];
            palette.getReds(r); palette.getGreens(g); palette.getBlues(b);
            for (int i = 0; i < size; i++) {
                pr[i] = r[i] & 0xFF; pg[i] = g[i] & 0xFF; pb[i] = b[i] & 0xFF;
            }
        }

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, palette);
        WritableRaster raster = out.getRaster();

        // Two running error rows (current + next), each channel interleaved.
        float[] curr = new float[(w + 2) * 3];
        float[] next = new float[(w + 2) * 3];
        Map<Integer, Integer> exactCache = new HashMap<>();

        for (int y = 0; y < h; y++) {
            for (int i = 0; i < next.length; i++) { next[i] = 0; }
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                int base = (x + 1) * 3;
                int r = clamp(((rgb >> 16) & 0xFF) + Math.round(curr[base]));
                int g = clamp(((rgb >> 8) & 0xFF) + Math.round(curr[base + 1]));
                int b = clamp((rgb & 0xFF) + Math.round(curr[base + 2]));

                int idx = nearest(r, g, b, pr, pg, pb, size, exactCache);
                raster.setSample(x, y, 0, idx);

                int er = r - pr[idx];
                int eg = g - pg[idx];
                int eb = b - pb[idx];

                // Floyd–Steinberg: 7/16 right, 3/16 below-left, 5/16 below, 1/16 below-right.
                diffuse(curr, base + 3, er, eg, eb, 7f / 16);
                diffuse(next, base - 3, er, eg, eb, 3f / 16);
                diffuse(next, base, er, eg, eb, 5f / 16);
                diffuse(next, base + 3, er, eg, eb, 1f / 16);
            }
            float[] tmp = curr; curr = next; next = tmp;
        }
        return out;
    }

    private static void diffuse(float[] row, int base, int er, int eg, int eb, float wgt) {
        row[base] += er * wgt;
        row[base + 1] += eg * wgt;
        row[base + 2] += eb * wgt;
    }

    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    /** Nearest palette entry by squared-RGB distance; exact hits are memoized. */
    private static int nearest(int r, int g, int b, int[] pr, int[] pg, int[] pb, int size,
                               Map<Integer, Integer> cache) {
        int key = (r << 16) | (g << 8) | b;
        Integer hit = cache.get(key);
        if (hit != null) { return hit; }
        int best = 0;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            long dr = r - pr[i], dg = g - pg[i], db = b - pb[i];
            long dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) { bestDist = dist; best = i; if (dist == 0) { break; } }
        }
        cache.put(key, best);
        return best;
    }

    // ----- GIF frame metadata (unchanged behaviour) --------------------------

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
