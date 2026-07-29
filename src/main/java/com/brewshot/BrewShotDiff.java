package com.brewshot;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Pixel diff → a citable textual verdict (plan 84f468d0, roadmap #6257 B-item).
 *
 * <p>BrewShot captures beautifully but couldn't <em>compare</em>; the highest-value
 * comparison artifact for PR evidence is a verdict you can paste into a review —
 * "2.1% of pixels changed; largest cluster at 340,120 (190×60) — in the header" —
 * not pixels. This class is the pure-JDK engine: decode-agnostic (callers hand it
 * {@link BufferedImage}s), zero dependencies, one O(w·h) pass plus a BFS over the
 * changed mask.
 *
 * <p>Design decisions folded from the consumer review (brewshot #25):
 * anti-aliasing forgiveness is ON by default (a raw AA diff is a noise wall and the
 * verdict would be untrustworthy) and everything it eats is COUNTED and printed —
 * nothing is silently ignored. This is the diff PRIMITIVE only: no baseline store
 * (that's a fast-follow once diff semantics settle over deterministic captures).
 *
 * <p>Like GIF recording, this rides ImageIO/AWT — JVM/jar path, not the macOS
 * native binary (the documented caveat class).
 */
public final class BrewShotDiff {

    private BrewShotDiff() { }

    /** Default per-channel tolerance: deltas at or below this never count as change. */
    public static final int DEFAULT_TOLERANCE = 16;

    /** Fraction of image height treated as the header (top) / footer (bottom) band. */
    private static final double BAND_SHARE = 0.15;

    /**
     * Diff knobs. {@code tolerance} is the per-channel floor (a pixel changes only when
     * {@code max(|dr|,|dg|,|db|) > tolerance}); {@code ignoreAntialiasing} enables the
     * luminance-slope/sibling-cluster forgiveness (ON by default at the CLI — everything
     * it ignores is still counted in {@link Verdict#antialiasedIgnored}); {@code masks} are
     * {@code {x,y,w,h}} regions excluded from comparison on BOTH images (dynamic
     * regions — clocks, spinners — so the numbers stay stable and citable).
     * Extents must be positive and non-overflowing. Masks are clipped to image
     * bounds; one wholly outside the image intentionally excludes zero pixels.
     * Constructor inputs and accessor results are deep-copied.
     */
    public record Options(int tolerance, boolean ignoreAntialiasing, List<int[]> masks) {
        public Options {
            // A channel delta is at most 255 and comparison is strictly
            // `delta > tolerance`; 255 would disable all possible changes.
            Validation.intRange("diff tolerance", tolerance, 0, 254);
            masks = copyMasks(masks);
        }

        public static Options defaults() {
            return new Options(DEFAULT_TOLERANCE, true, List.of());
        }

        /** A fresh deep copy, so callers cannot mutate the stored mask arrays. */
        @Override
        public List<int[]> masks() {
            return copyMasks(masks);
        }

        private static List<int[]> copyMasks(List<int[]> source) {
            if (source == null) {
                throw new IllegalArgumentException("diff masks must not be null");
            }
            List<int[]> copy = new ArrayList<>(source.size());
            for (int index = 0; index < source.size(); index++) {
                int[] mask = source.get(index);
                if (mask == null) {
                    throw new IllegalArgumentException(
                        "diff mask " + index + " must not be null");
                }
                if (mask.length != 4) {
                    throw new IllegalArgumentException(
                        "diff mask " + index + " must be {x,y,w,h}, got length "
                            + mask.length);
                }
                Validation.positiveInt("diff mask " + index + " width", mask[2]);
                Validation.positiveInt("diff mask " + index + " height", mask[3]);
                try {
                    Math.addExact(mask[0], mask[2]);
                    Math.addExact(mask[1], mask[3]);
                } catch (ArithmeticException overflow) {
                    throw new IllegalArgumentException(
                        "diff mask " + index + " extent overflows integer coordinates",
                        overflow);
                }
                copy.add(mask.clone());
            }
            return List.copyOf(copy);
        }
    }

    /** The largest connected region of change: where to look first. */
    public record Cluster(int centroidX, int centroidY, int x, int y, int width, int height,
                          long pixels, double shareOfChange, String label) { }

    /**
     * The whole comparison, machine-readable. {@code prose} is the citable one-liner.
     * A {@code sizeMismatch} verdict carries the dimensions in prose and no pixel
     * numbers (comparing differently-sized rasters pixel-by-pixel would be a lie).
     */
    public record Verdict(int widthA, int heightA, int widthB, int heightB,
                          boolean sizeMismatch,
                          long totalPixels, long changedPixels, double pctChanged,
                          long antialiasedIgnored, long maskedPixels,
                          int[] changedBounds, Cluster largestCluster, String prose) {
        public Verdict {
            if (changedBounds != null && changedBounds.length != 4) {
                throw new IllegalArgumentException(
                    "changedBounds must be {x,y,w,h}, got length " + changedBounds.length);
            }
            changedBounds = changedBounds == null ? null : changedBounds.clone();
        }

        public boolean anyChange() {
            return sizeMismatch || changedPixels > 0;
        }

        /** A fresh copy, so a returned bounds array cannot mutate this verdict. */
        @Override
        public int[] changedBounds() {
            return changedBounds == null ? null : changedBounds.clone();
        }
    }

    /** Compare two decoded images under {@code options} and render the verdict. */
    public static Verdict diff(BufferedImage a, BufferedImage b, Options options) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        Objects.requireNonNull(options, "options");
        int wa = a.getWidth(), ha = a.getHeight();
        int wb = b.getWidth(), hb = b.getHeight();
        if (wa != wb || ha != hb) {
            return new Verdict(wa, ha, wb, hb, true, 0, 0, 0, 0, 0, null, null,
                "size mismatch: " + wa + "x" + ha + " vs " + wb + "x" + hb
                    + " — dimensions must match to compare pixels (re-shoot at the same"
                    + " viewport/scale, or crop first).");
        }
        int w = wa, h = ha;
        int[] pa = a.getRGB(0, 0, w, h, null, 0, w);
        int[] pb = b.getRGB(0, 0, w, h, null, 0, w);

        boolean[] masked = new boolean[w * h];
        long maskedCount = 0;
        List<int[]> masks = options.masks();
        for (int[] m : masks) {
            int mx = Math.max(0, m[0]), my = Math.max(0, m[1]);
            int mx2 = Math.min(w, m[0] + m[2]), my2 = Math.min(h, m[1] + m[3]);
            for (int y = my; y < my2; y++) {
                for (int x = mx; x < mx2; x++) {
                    if (!masked[y * w + x]) {
                        masked[y * w + x] = true;
                        maskedCount++;
                    }
                }
            }
        }

        boolean[] changed = new boolean[w * h];
        long changedCount = 0;
        long aaIgnored = 0;
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (masked[i] || !differs(pa[i], pb[i], options.tolerance())) {
                    continue;
                }
                if (options.ignoreAntialiasing() && looksAntialiased(pa, pb, w, h, x, y)) {
                    aaIgnored++;
                    continue;
                }
                changed[i] = true;
                changedCount++;
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
            }
        }

        long total = (long) w * h;
        // F2 (consumer review brewshot #45): the pct denominator is the COMPARABLE pixels —
        // total minus masked. Masking a dynamic region must not DILUTE the gate: a 100-px
        // change is the same 100-px change whether or not a clock was masked elsewhere.
        long comparable = total - maskedCount;
        double pct = comparable == 0 ? 0 : 100.0 * changedCount / comparable;
        int[] bounds = changedCount == 0 ? null
            : new int[] {minX, minY, maxX - minX + 1, maxY - minY + 1};
        Cluster largest = changedCount == 0 ? null : largestCluster(changed, w, h, changedCount);
        return new Verdict(w, h, w, h, false, total, changedCount, pct, aaIgnored,
            maskedCount, bounds, largest, prose(w, h, total, changedCount, pct, aaIgnored, maskedCount, largest));
    }

    /** Changed iff any channel moved beyond the tolerance floor (alpha included). */
    private static boolean differs(int argbA, int argbB, int tolerance) {
        if (argbA == argbB) {
            return false;
        }
        int da = Math.abs(((argbA >>> 24) & 0xFF) - ((argbB >>> 24) & 0xFF));
        int dr = Math.abs(((argbA >>> 16) & 0xFF) - ((argbB >>> 16) & 0xFF));
        int dg = Math.abs(((argbA >>> 8) & 0xFF) - ((argbB >>> 8) & 0xFF));
        int db = Math.abs((argbA & 0xFF) - (argbB & 0xFF));
        int max = Math.max(Math.max(dr, dg), Math.max(db, da));
        return max > tolerance;
    }

    /**
     * Pixelmatch-style edge-context discriminator. A changed pixel is forgivable only
     * when either image places it on a real luminance slope (both a darker and a lighter
     * neighbor) and one slope endpoint belongs to a stable same-color neighborhood in
     * both images. The sibling check makes this cluster-aware instead of accepting any
     * reciprocal one-pixel color move. Opaque black/white translations have only one
     * side of a slope and therefore remain gate-relevant; soft coverage ramps qualify.
     */
    private static boolean looksAntialiased(int[] pa, int[] pb, int w, int h, int x, int y) {
        return hasAntialiasedEdgeContext(pa, pb, w, h, x, y)
            || hasAntialiasedEdgeContext(pb, pa, w, h, x, y);
    }

    private static boolean hasAntialiasedEdgeContext(
            int[] source, int[] counterpart, int w, int h, int x, int y) {
        int x0 = Math.max(x - 1, 0), y0 = Math.max(y - 1, 0);
        int x2 = Math.min(x + 1, w - 1), y2 = Math.min(y + 1, h - 1);
        int equalBrightness = x == x0 || x == x2 || y == y0 || y == y2 ? 1 : 0;
        long center = compositedLuminance(source[y * w + x]);
        long darkestDelta = 0, lightestDelta = 0;
        int darkestX = x, darkestY = y, lightestX = x, lightestY = y;

        for (int ny = y0; ny <= y2; ny++) {
            for (int nx = x0; nx <= x2; nx++) {
                if (nx == x && ny == y) {
                    continue;
                }
                long delta = compositedLuminance(source[ny * w + nx]) - center;
                if (delta == 0) {
                    equalBrightness++;
                    if (equalBrightness > 2) {
                        return false;
                    }
                } else if (delta < darkestDelta) {
                    darkestDelta = delta;
                    darkestX = nx;
                    darkestY = ny;
                } else if (delta > lightestDelta) {
                    lightestDelta = delta;
                    lightestX = nx;
                    lightestY = ny;
                }
            }
        }

        if (darkestDelta == 0 || lightestDelta == 0) {
            return false;
        }
        return stableSiblingCluster(source, counterpart, w, h, darkestX, darkestY)
            || stableSiblingCluster(source, counterpart, w, h, lightestX, lightestY);
    }

    private static boolean stableSiblingCluster(
            int[] source, int[] counterpart, int w, int h, int x, int y) {
        return hasManySiblings(source, w, h, x, y)
            && hasManySiblings(counterpart, w, h, x, y);
    }

    private static boolean hasManySiblings(int[] pixels, int w, int h, int x, int y) {
        int x0 = Math.max(x - 1, 0), y0 = Math.max(y - 1, 0);
        int x2 = Math.min(x + 1, w - 1), y2 = Math.min(y + 1, h - 1);
        int siblings = x == x0 || x == x2 || y == y0 || y == y2 ? 1 : 0;
        int color = pixels[y * w + x];
        for (int ny = y0; ny <= y2; ny++) {
            for (int nx = x0; nx <= x2; nx++) {
                if (nx == x && ny == y) {
                    continue;
                }
                if (pixels[ny * w + nx] == color && ++siblings > 2) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Integer Rec. 601 luminance after alpha-compositing the pixel over white. */
    private static long compositedLuminance(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        int red = compositeOverWhite((argb >>> 16) & 0xFF, alpha);
        int green = compositeOverWhite((argb >>> 8) & 0xFF, alpha);
        int blue = compositeOverWhite(argb & 0xFF, alpha);
        return 299L * red + 587L * green + 114L * blue;
    }

    private static int compositeOverWhite(int channel, int alpha) {
        return 255 + ((channel - 255) * alpha) / 255;
    }

    /** BFS connected components (8-connectivity) over the changed mask; keep the largest. */
    private static Cluster largestCluster(boolean[] changed, int w, int h, long changedTotal) {
        boolean[] seen = new boolean[changed.length];
        long bestCount = 0;
        long bestSumX = 0, bestSumY = 0;
        int bestMinX = 0, bestMinY = 0, bestMaxX = 0, bestMaxY = 0;
        Deque<Integer> queue = new ArrayDeque<>();
        for (int start = 0; start < changed.length; start++) {
            if (!changed[start] || seen[start]) {
                continue;
            }
            long count = 0, sumX = 0, sumY = 0;
            int minX = w, minY = h, maxX = -1, maxY = -1;
            seen[start] = true;
            queue.add(start);
            while (!queue.isEmpty()) {
                int i = queue.poll();
                int x = i % w, y = i / w;
                count++;
                sumX += x;
                sumY += y;
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
                            continue;
                        }
                        int ni = ny * w + nx;
                        if (changed[ni] && !seen[ni]) {
                            seen[ni] = true;
                            queue.add(ni);
                        }
                    }
                }
            }
            if (count > bestCount) {
                bestCount = count;
                bestSumX = sumX;
                bestSumY = sumY;
                bestMinX = minX;
                bestMinY = minY;
                bestMaxX = maxX;
                bestMaxY = maxY;
            }
        }
        int cx = (int) (bestSumX / bestCount);
        int cy = (int) (bestSumY / bestCount);
        return new Cluster(cx, cy, bestMinX, bestMinY,
            bestMaxX - bestMinX + 1, bestMaxY - bestMinY + 1,
            bestCount, (double) bestCount / changedTotal, bandLabel(cy, h));
    }

    /** Positional label by centroid y-band: where a reviewer should look first. */
    private static String bandLabel(int centroidY, int height) {
        if (centroidY < height * BAND_SHARE) {
            return "header";
        }

        if (centroidY > height * (1 - BAND_SHARE)) {
            return "footer";
        }
        return "body";
    }

    /** The citable one-liner. Every count that shaped the numbers is disclosed. The
     *  percentage is changed/COMPARABLE (total minus masked) — see the F2 note in diff(). */
    private static String prose(int w, int h, long total, long changed, double pct,
                                long aaIgnored, long masked, Cluster largest) {
        StringBuilder sb = new StringBuilder(160);
        if (changed == 0) {
            sb.append("no pixel changes (").append(w).append('x').append(h)
              .append(", ").append(total).append(" px");
        } else {
            sb.append(String.format(java.util.Locale.ROOT, "%.2f%%", pct))
              .append(" of comparable pixels changed (").append(changed).append(" of ").append(total - masked);
        }
        if (aaIgnored > 0) {
            sb.append("; ").append(aaIgnored).append(" anti-aliasing px ignored");
        }
        if (masked > 0) {
            sb.append("; ").append(masked).append(" px masked");
        }
        sb.append(')');
        if (largest != null) {
            sb.append("; largest cluster at ").append(largest.centroidX()).append(',')
              .append(largest.centroidY()).append(" (")
              .append(largest.width()).append('x').append(largest.height()).append(", ")
              .append(String.format(java.util.Locale.ROOT, "%.0f%%", largest.shareOfChange() * 100))
              .append(" of the change) — in the ").append(largest.label()).append('.');
        } else {
            sb.append('.');
        }
        return sb.toString();
    }

    /**
     * Diff heatmap: image A dimmed toward white, changed pixels solid magenta — the
     * eyes-artifact companion to the textual verdict ({@code --diff-out}).
     */
    public static BufferedImage heatmap(BufferedImage a, BufferedImage b, Options options) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        Objects.requireNonNull(options, "options");
        int w = a.getWidth(), h = a.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] pa = a.getRGB(0, 0, w, h, null, 0, w);
        int[] pb = b.getRGB(0, 0, w, h, null, 0, w);
        boolean[] masked = new boolean[w * h];
        List<int[]> masks = options.masks();
        for (int[] m : masks) {
            int mx = Math.max(0, m[0]), my = Math.max(0, m[1]);
            int mx2 = Math.min(w, m[0] + m[2]), my2 = Math.min(h, m[1] + m[3]);
            for (int y = my; y < my2; y++) {
                for (int x = mx; x < mx2; x++) {
                    masked[y * w + x] = true;
                }
            }
        }
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                boolean isChange = !masked[i] && differs(pa[i], pb[i], options.tolerance())
                    && !(options.ignoreAntialiasing()
                         && looksAntialiased(pa, pb, w, h, x, y));
                if (isChange) {
                    px[i] = 0xFF00FF;
                } else {
                    int r = (pa[i] >>> 16) & 0xFF, g = (pa[i] >>> 8) & 0xFF, bl = pa[i] & 0xFF;
                    px[i] = ((r + 2 * 255) / 3 << 16) | ((g + 2 * 255) / 3 << 8) | ((bl + 2 * 255) / 3);
                }
            }
        }
        out.setRGB(0, 0, w, h, px, 0, w);
        return out;
    }

    /** The JSON sidecar body ({@code --json}), MiniJson-serialized, stable field order. */
    public static String toJson(Verdict v, Double failOverPct, Long failPixels, boolean gateExceeded) {
        Objects.requireNonNull(v, "verdict");
        java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("sizeMismatch", v.sizeMismatch());
        root.put("width", v.widthA());
        root.put("height", v.heightA());
        if (v.sizeMismatch()) {
            root.put("widthB", v.widthB());
            root.put("heightB", v.heightB());
        }
        root.put("totalPixels", v.totalPixels());
        root.put("changedPixels", v.changedPixels());
        root.put("pctChanged", decimal4(v.pctChanged()));
        root.put("antialiasedIgnored", v.antialiasedIgnored());
        root.put("maskedPixels", v.maskedPixels());
        root.put("bbox", v.changedBounds());
        if (v.largestCluster() == null) {
            root.put("largestCluster", null);
        } else {
            Cluster c = v.largestCluster();
            java.util.Map<String, Object> cluster = new java.util.LinkedHashMap<>();
            cluster.put("centroid", new int[] {c.centroidX(), c.centroidY()});
            cluster.put("bbox", new int[] {c.x(), c.y(), c.width(), c.height()});
            cluster.put("pixels", c.pixels());
            cluster.put("share", decimal4(c.shareOfChange()));
            cluster.put("label", c.label());
            root.put("largestCluster", cluster);
        }
        java.util.Map<String, Object> gate = new java.util.LinkedHashMap<>();
        gate.put("failOverPct", failOverPct);
        gate.put("failPixels", failPixels);
        gate.put("exceeded", gateExceeded);
        root.put("gate", gate);
        root.put("verdict", v.prose());
        root.put("brewshot", BrewShot.VERSION);
        return MiniJson.stringifyPretty(root) + "\n";
    }

    private static java.math.BigDecimal decimal4(double value) {
        return java.math.BigDecimal.valueOf(value).setScale(
            4, java.math.RoundingMode.HALF_UP);
    }
}
