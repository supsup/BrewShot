package com.brewshot;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

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
 * anti-aliasing forgiveness is OPT-IN at the CLI ({@code --ignore-antialiasing}) and
 * the CLI defaults to strict / pixel-honest, so a genuine 1-pixel layout move can
 * never be silently swallowed by the heuristic; the library {@link Options#defaults()}
 * convenience keeps AA-on for callers who want the noise-tolerant primitive. When
 * forgiveness IS enabled everything it eats is COUNTED and printed —
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
     * {@code max(|dr|,|dg|,|db|) > tolerance}, valid range 0..255); {@code ignoreAntialiasing} enables the
     * 3×3 shifted-edge forgiveness (OPT-IN at the CLI via {@code --ignore-antialiasing};
     * everything it ignores is still counted in {@link Verdict#antialiasedIgnored}); {@code masks} are
     * {@code {x,y,w,h}} regions excluded from comparison on BOTH images (dynamic
     * regions — clocks, spinners — so the numbers stay stable and citable).
     */
    public record Options(int tolerance, boolean ignoreAntialiasing, List<int[]> masks) {
        /**
         * F-03 (audit): {@code tolerance} is a per-channel 8-bit delta floor, so only 0..255
         * is meaningful. The compare is {@code max > tolerance}; a tolerance of 255 (or more)
         * makes that unsatisfiable — EVERY diff reports zero change, a "green" gate that can
         * never fail. Out-of-range values are almost always a mistake (a typo, or a percent
         * mistaken for a channel delta); reject them LOUD at construction rather than silently
         * neutering the gate. This is the single validation point — the CLI surfaces it as exit 2.
         */
        public Options {
            if (tolerance < 0 || tolerance > 255) {
                throw new IllegalArgumentException(
                    "tolerance must be 0..255 (per-channel 8-bit delta floor), got: " + tolerance);
            }
        }

        public static Options defaults() {
            return new Options(DEFAULT_TOLERANCE, true, List.of());
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

        public boolean anyChange() {
            return sizeMismatch || changedPixels > 0;
        }
    }

    /** Compare two decoded images under {@code options} and render the verdict. */
    public static Verdict diff(BufferedImage a, BufferedImage b, Options options) {
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
        for (int[] m : options.masks()) {
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
                if (options.ignoreAntialiasing() && looksAntialiased(pa, pb, w, h, x, y, options.tolerance())) {
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
     * Shifted-edge forgiveness (the pixelmatch-style heuristic, simplified): a changed
     * pixel is treated as anti-aliasing/hinting noise when the color pair merely MOVED
     * within a 3×3 neighborhood — image B still shows A's color right next door, and
     * image A still shows B's color right next door. A genuine content change (new
     * color that exists in neither neighborhood) never qualifies, so a real 1-pixel
     * edit stays counted while a 1-pixel glyph-hinting shift is ignored (and tallied).
     */
    private static boolean looksAntialiased(int[] pa, int[] pb, int w, int h, int x, int y, int tolerance) {
        return neighborhoodContains(pb, w, h, x, y, pa[y * w + x], tolerance)
            && neighborhoodContains(pa, w, h, x, y, pb[y * w + x], tolerance);
    }

    private static boolean neighborhoodContains(int[] pixels, int w, int h, int x, int y,
                                                int color, int tolerance) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                int nx = x + dx, ny = y + dy;
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
                    continue;
                }
                if (!differs(pixels[ny * w + nx], color, tolerance)) {
                    return true;
                }
            }
        }
        return false;
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
        int w = a.getWidth(), h = a.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] pa = a.getRGB(0, 0, w, h, null, 0, w);
        int[] pb = b.getRGB(0, 0, w, h, null, 0, w);
        boolean[] masked = new boolean[w * h];
        for (int[] m : options.masks()) {
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
                         && looksAntialiased(pa, pb, w, h, x, y, options.tolerance()));
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

    /** The JSON sidecar body ({@code --json}), MiniJson-escaped, stable field order. */
    public static String toJson(Verdict v, Double failOverPct, Long failPixels, boolean gateExceeded) {
        StringBuilder j = new StringBuilder(512);
        j.append("{\n")
         .append("  \"sizeMismatch\": ").append(v.sizeMismatch()).append(",\n")
         .append("  \"width\": ").append(v.widthA()).append(",\n")
         .append("  \"height\": ").append(v.heightA()).append(",\n");
        if (v.sizeMismatch()) {
            j.append("  \"widthB\": ").append(v.widthB()).append(",\n")
             .append("  \"heightB\": ").append(v.heightB()).append(",\n");
        }
        j.append("  \"totalPixels\": ").append(v.totalPixels()).append(",\n")
         .append("  \"changedPixels\": ").append(v.changedPixels()).append(",\n")
         .append("  \"pctChanged\": ").append(String.format(java.util.Locale.ROOT, "%.4f", v.pctChanged())).append(",\n")
         .append("  \"antialiasedIgnored\": ").append(v.antialiasedIgnored()).append(",\n")
         .append("  \"maskedPixels\": ").append(v.maskedPixels()).append(",\n")
         .append("  \"bbox\": ").append(rect(v.changedBounds())).append(",\n");
        if (v.largestCluster() == null) {
            j.append("  \"largestCluster\": null,\n");
        } else {
            Cluster c = v.largestCluster();
            j.append("  \"largestCluster\": {\n")
             .append("    \"centroid\": [").append(c.centroidX()).append(", ").append(c.centroidY()).append("],\n")
             .append("    \"bbox\": [").append(c.x()).append(", ").append(c.y()).append(", ")
             .append(c.width()).append(", ").append(c.height()).append("],\n")
             .append("    \"pixels\": ").append(c.pixels()).append(",\n")
             .append("    \"share\": ").append(String.format(java.util.Locale.ROOT, "%.4f", c.shareOfChange())).append(",\n")
             .append("    \"label\": \"").append(c.label()).append("\"\n")
             .append("  },\n");
        }
        j.append("  \"gate\": {\n")
         .append("    \"failOverPct\": ").append(failOverPct == null ? "null" : failOverPct).append(",\n")
         .append("    \"failPixels\": ").append(failPixels == null ? "null" : failPixels).append(",\n")
         .append("    \"exceeded\": ").append(gateExceeded).append("\n")
         .append("  },\n")
         .append("  \"verdict\": \"").append(MiniJson.esc(v.prose())).append("\",\n")
         .append("  \"brewshot\": \"").append(BrewShot.VERSION).append("\"\n")
         .append("}\n");
        return j.toString();
    }

    private static String rect(int[] r) {
        if (r == null) {
            return "null";
        }
        List<String> parts = new ArrayList<>(4);
        for (int v : r) {
            parts.add(String.valueOf(v));
        }
        return "[" + String.join(", ", parts) + "]";
    }
}
