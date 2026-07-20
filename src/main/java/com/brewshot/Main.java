package com.brewshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The {@code brewshot} CLI — one screenshot, no ceremony. Built for the
 * native-image target (instant startup, no JVM), and equally runnable as
 * {@code java -jar brewshot.jar}.
 *
 * <pre>
 *   brewshot https://example.com -o page.png
 *   brewshot ./report.html -o report.png            # local file
 *   cat page.html | brewshot - -o page.png          # direct HTML on stdin
 *   brewshot URL -o page.png --size 1440x1000 --settle 1500 --eval "document.title"
 * </pre>
 *
 * Exit codes: 0 ok · 2 bad arguments · 3 no Chrome found · 4 --fail-js
 * assertion failed (screenshot still written) or a `diff` gate exceeded
 * (verdict still written) · 1 runtime failure.
 * Note: GIF recording and `diff` are library/jar-path (ImageIO/AWT is not yet
 * supported by native-image on macOS); the PNG shoot path is native-clean.
 */
public final class Main {

    private Main() { }

    public static void main(String[] args) throws Exception {
        // Exit code decided OUTSIDE any try-with-resources: a System.exit
        // inside one would skip close() and orphan a running Chrome.
        System.exit(run(args));
    }

    /** Testable entry: parse, shoot, return the exit code. Never calls System.exit. */
    static int run(String[] rawArgs) throws Exception {
        // Accept both `--out x.png` AND `--out=x.png` (the near-universal
        // convention; agent-generated wrappers lean on it). Split a leading
        // long-flag `--name=value` into two tokens up front so the loop below
        // needs no `=` awareness. A bare `-` (stdin) and `=` inside a VALUE
        // token are untouched.
        java.util.List<String> norm = new java.util.ArrayList<>(rawArgs.length + 4);
        for (String a : rawArgs) {
            int eq = a.indexOf('=');
            if (a.startsWith("--") && eq > 2) {
                norm.add(a.substring(0, eq));
                norm.add(a.substring(eq + 1));
            } else {
                norm.add(a);
            }
        }
        String[] args = norm.toArray(new String[0]);
        // `brewshot diff` — the compare lane (plan 84f468d0). Dispatched AFTER the
        // --name=value normalization so diff flags accept both spellings, BEFORE the
        // shoot-lane parsing (diff shares none of its flags and never touches Chrome).
        if (args.length > 0 && args[0].equals("diff")) {
            return runDiff(java.util.Arrays.copyOfRange(args, 1, args.length));
        }
        String input = null;
        Path out = Path.of("brewshot.png");
        int width = 1280;
        int height = 900;
        long settleMs = 800;
        String evalExpr = null;
        String waitJs = null;
        long waitTimeoutMs = 10_000;
        String clipJs = null;
        String clipSelector = null;
        double scale = 1.0;
        double clipPadding = 0;
        String failJs = null;
        Path jsonManifest = null;
        java.util.List<String[]> cookies = new java.util.ArrayList<>();
        java.util.List<String[]> headers = new java.util.ArrayList<>();

        // The ARGUMENT-PARSING phase only: a bad flag value (missing value,
        // non-numeric --size/--settle/--wait-timeout, unreadable --eval-file)
        // is a usage error → clean message + exit 2, never a stack trace. The
        // launch/shoot phase below keeps its real errors (exit 1).
        try {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-o", "--out" -> out = Path.of(requireValue(args, ++i));
                case "--size" -> {
                    String[] wh = requireValue(args, ++i).split("x");
                    if (wh.length != 2) { return err("--size wants WxH, e.g. 1440x1000"); }
                    width = posInt("--size width", wh[0]);
                    height = posInt("--size height", wh[1]);
                }
                case "--settle" -> settleMs = posLong("--settle", requireValue(args, ++i));
                case "--eval" -> evalExpr = requireValue(args, ++i);
                case "--eval-file" -> evalExpr = Files.readString(Path.of(requireValue(args, ++i)));
                case "--wait-js" -> waitJs = requireValue(args, ++i);
                case "--wait-timeout" -> waitTimeoutMs = posLong("--wait-timeout", requireValue(args, ++i));
                case "--clip-js" -> clipJs = requireValue(args, ++i);
                case "--clip-selector" -> clipSelector = requireValue(args, ++i);
                case "--scale" -> scale = posDouble("--scale", requireValue(args, ++i));
                case "--clip-padding" -> clipPadding = nonNegDouble("--clip-padding", requireValue(args, ++i));
                case "--fail-js" -> failJs = requireValue(args, ++i);
                case "--json" -> jsonManifest = Path.of(requireValue(args, ++i));
                case "--cookie" -> {
                    // name=value@domain (domain defaults to localhost)
                    String[] nv = requireValue(args, ++i).split("=", 2);
                    if (nv.length != 2) { return err("--cookie wants name=value[@domain]"); }
                    String[] vd = nv[1].split("@", 2);
                    cookies.add(new String[] {nv[0], vd[0], vd.length == 2 ? vd[1] : "localhost"});
                }
                case "--header" -> {
                    // "Name: value"
                    String[] hv = requireValue(args, ++i).split(":", 2);
                    if (hv.length != 2) { return err("--header wants 'Name: value'"); }
                    headers.add(new String[] {hv[0].trim(), hv[1].trim()});
                }
                case "-h", "--help" -> { usage(); return 0; }
                case "--version" -> { System.out.println("brewshot " + BrewShot.VERSION); return 0; }
                default -> {
                    if (args[i].startsWith("-") && !args[i].equals("-")) {
                        return err("unknown flag: " + args[i]);
                    }
                    input = args[i];
                }
            }
        }
        } catch (IllegalArgumentException | java.io.IOException e) {
            return err(e.getMessage());
        }
        if (input == null) { usage(); return 2; }
        if (clipSelector != null && clipJs != null) {
            return err("--clip-selector and --clip-js are mutually exclusive (pick one clip source)");
        }
        // .pdf output is decided by extension, CASE-INSENSITIVELY (an `-o out.PDF` that fell
        // through to the raster path would write PNG bytes into a .PDF file and report success).
        boolean pdfOut = isPdfOutput(out);
        // Raster-only flags cannot be honored on the paged PDF path, and the .pdf branch runs
        // FIRST, so they would be silently ignored — a full-page PDF where the caller asked for a
        // crop. BrewShot output is review evidence, so refuse LOUDLY (exit 2) rather than emit a
        // silently-wrong artifact (Fix review, brewshot 99 F1).
        if (pdfOut && (clipSelector != null || clipJs != null || scale != 1.0 || clipPadding != 0)) {
            return err("clip/scale flags are raster-only and cannot apply to a .pdf output "
                + "(PDF is paged, not clipped) — drop --clip-selector/--clip-js/--scale/--clip-padding, "
                + "or write a raster format (.png/.jpg)");
        }

        // Resolve the input MODE before touching Chrome, so arg mistakes fail
        // fast with a clear message.
        String mode; // "stdin" | "url" | "file"
        if (input.equals("-")) { mode = "stdin"; }
        else if (input.matches("^[a-z][a-z0-9+.-]*://.*")) { mode = "url"; }
        else if (Files.exists(Path.of(input))) { mode = "file"; }
        else { return err("not a URL, an existing file, or '-': " + input); }

        if (!BrewShot.available()) {
            System.err.println("brewshot: no Chrome/Chromium found (set BREWSHOT_CHROME)");
            return 3;
        }

        long t0 = System.currentTimeMillis();
        Object evalResult = null;
        boolean failJsPassed = true;
        try (BrewShot shot = BrewShot.launch(width, height)) {
            for (String[] h : headers) { shot.header(h[0], h[1]); }
            for (String[] c : cookies) { shot.cookie(c[0], c[1], c[2]); }
            switch (mode) {
                case "stdin" -> shot.html(new String(
                    System.in.readAllBytes(), StandardCharsets.UTF_8));
                case "url" -> shot.open(input);
                default -> shot.open(Path.of(input).toAbsolutePath().toUri().toString());
            }
            if (waitJs != null) { shot.waitFor(waitJs, waitTimeoutMs); }
            shot.settle(settleMs);
            if (evalExpr != null) {
                evalResult = shot.eval(evalExpr);
                System.out.println(evalResult);
            }
            if (pdfOut) {
                // Output path ends .pdf (case-insensitive) → render the whole document via
                // Page.printToPDF. Clip/scale flags are raster-only and don't map to PDF paged
                // output; a .pdf combined with them was already refused above (never silent here).
                shot.pdf(out);
            } else if (clipSelector != null) {
                // Selector-driven clip: elementBox throws on no-match — that's a page-content
                // failure (the page didn't have the element), not a usage error: exit 1, loud.
                double[] b;
                try {
                    b = shot.elementBox(clipSelector);
                } catch (IllegalArgumentException e) {
                    System.err.println("brewshot: " + e.getMessage());
                    return 1;
                }
                Files.write(out, shot.screenshotClip(
                    Math.max(0, b[0] - clipPadding), Math.max(0, b[1] - clipPadding),
                    b[2] + 2 * clipPadding, b[3] + 2 * clipPadding, scale));
            } else if (clipJs != null) {
                Object r = shot.eval(clipJs);
                Object x = MiniJson.get(r, "x"), y = MiniJson.get(r, "y");
                Object w = MiniJson.get(r, "w"), h = MiniJson.get(r, "h");
                if (!(x instanceof Double) || !(y instanceof Double)
                        || !(w instanceof Double) || !(h instanceof Double)) {
                    return err("--clip-js must return {x,y,w,h} (page coordinates), got: " + r);
                }
                Files.write(out, shot.screenshotClip(
                    Math.max(0, (Double) x - clipPadding), Math.max(0, (Double) y - clipPadding),
                    (Double) w + 2 * clipPadding, (Double) h + 2 * clipPadding, scale));
            } else if (scale != 1.0) {
                // Standalone --scale: clip the full PAGE box (scroll dimensions, not just the
                // viewport) at scale — crisp full-page stills with zero extra flags. Chrome's
                // clip.scale RE-RENDERS the region (a true re-raster), it does not upscale.
                Object dims = shot.eval("[document.documentElement.scrollWidth,"
                    + "document.documentElement.scrollHeight].join(',')");
                String[] wh = String.valueOf(dims).split(",");
                Files.write(out, shot.screenshotClip(0, 0,
                    Double.parseDouble(wh[0]), Double.parseDouble(wh[1]), scale));
            } else {
                shot.screenshot(out);
            }
            // --fail-js: assert AFTER the screenshot so failures still carry eyes.
            if (failJs != null) {
                Object ok = shot.eval("!!(" + failJs + ")");
                failJsPassed = Boolean.TRUE.equals(ok);
            }
            if (jsonManifest != null) {
                writeManifest(jsonManifest, input, mode, width, height, settleMs, waitJs,
                    out, evalResult, failJs, failJsPassed,
                    System.currentTimeMillis() - t0);
            }
            System.err.println("brewshot: wrote " + out);
        }
        if (!failJsPassed) {
            System.err.println("brewshot: --fail-js assertion FAILED (screenshot still written): "
                + failJs);
            return 4;
        }
        return 0;
    }

    // ---- brewshot diff (plan 84f468d0) --------------------------------------------

    /**
     * One diff comparison: the unit of the LIST-OF-JOBS seam. The CLI builds exactly
     * one; a future JSON multi-shot manifest slots a whole list into
     * {@link #runDiffJobs} without reshaping (the diff gate will hammer shots —
     * amortizing setup across a 30-shot verify run is the seam's whole point).
     */
    record DiffJob(Path a, Path b, BrewShotDiff.Options options,
                   Double failOverPct, Long failPixels, Path diffOut, Path jsonOut) { }

    /** Parse `brewshot diff a.png b.png [flags]` into one job and run it. */
    private static int runDiff(String[] args) {
        java.util.List<Path> images = new java.util.ArrayList<>(2);
        int tolerance = BrewShotDiff.DEFAULT_TOLERANCE;
        boolean ignoreAntialiasing = true;   // Fix's call (brewshot #25): AA-ignore ON by default
        java.util.List<int[]> masks = new java.util.ArrayList<>();
        Double failOverPct = null;
        Long failPixels = null;
        Path diffOut = null;
        Path jsonOut = null;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--tolerance" -> tolerance = posInt("--tolerance", requireValue(args, ++i));
                    // AA forgiveness is ON by default; --pixel-exact is the opt-OUT for
                    // byte-faithful comparisons (every ignored pixel is counted either way).
                    case "--pixel-exact" -> ignoreAntialiasing = false;
                    case "--mask" -> {
                        String[] p = requireValue(args, ++i).split(",");
                        if (p.length != 4) { return err("--mask wants x,y,w,h"); }
                        masks.add(new int[] {posInt("--mask x", p[0].trim()), posInt("--mask y", p[1].trim()),
                            posInt("--mask w", p[2].trim()), posInt("--mask h", p[3].trim())});
                    }
                    case "--fail-over" -> failOverPct = nonNegDouble("--fail-over", requireValue(args, ++i));
                    case "--fail-pixels" -> failPixels = posLong("--fail-pixels", requireValue(args, ++i));
                    case "--diff-out" -> diffOut = Path.of(requireValue(args, ++i));
                    case "--json" -> jsonOut = Path.of(requireValue(args, ++i));
                    case "-h", "--help" -> { diffUsage(); return 0; }
                    default -> {
                        if (args[i].startsWith("-")) {
                            return err("unknown diff flag: " + args[i]);
                        }
                        images.add(Path.of(args[i]));
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        }
        if (images.size() != 2) {
            diffUsage();
            return 2;
        }
        DiffJob job = new DiffJob(images.get(0), images.get(1),
            new BrewShotDiff.Options(tolerance, ignoreAntialiasing, java.util.List.copyOf(masks)),
            failOverPct, failPixels, diffOut, jsonOut);
        return runDiffJobs(java.util.List.of(job));
    }

    /**
     * Run diff jobs in order; the worst exit code wins. The verdict is ALWAYS printed
     * and the sidecars always written before the gate decides the exit — the exact
     * contract {@code --fail-js} honors (evidence first, verdict-as-exit second).
     * Exit: 0 clean · 4 a gate tripped (or size mismatch under any gate) · 1 an
     * image could not be read.
     */
    static int runDiffJobs(java.util.List<DiffJob> jobs) {
        int worst = 0;
        for (DiffJob job : jobs) {
            java.awt.image.BufferedImage a;
            java.awt.image.BufferedImage b;
            try {
                a = readImage(job.a());
                b = readImage(job.b());
            } catch (java.io.IOException e) {
                System.err.println("brewshot: " + e.getMessage());
                worst = Math.max(worst, 1);
                continue;
            }
            BrewShotDiff.Verdict verdict = BrewShotDiff.diff(a, b, job.options());
            boolean gated = job.failOverPct() != null || job.failPixels() != null;
            // A size mismatch IS a change: any gate treats it as exceeded. Ungated it
            // stays informational (verdict printed, exit 0) — diff is a primitive, the
            // caller decides what blocks.
            boolean exceeded =
                (verdict.sizeMismatch() && gated)
                || (job.failOverPct() != null && !verdict.sizeMismatch()
                    && verdict.pctChanged() > job.failOverPct())
                || (job.failPixels() != null && !verdict.sizeMismatch()
                    && verdict.changedPixels() > job.failPixels());
            System.out.println(verdict.prose());
            // F1 (consumer review brewshot #45): the JSON sidecar is the MACHINE artifact —
            // write it FIRST, and in its own try, so a heatmap IO failure can never suppress
            // it (each artifact fails independently; the verdict line above always printed).
            if (job.jsonOut() != null) {
                try {
                    Files.writeString(job.jsonOut(),
                        BrewShotDiff.toJson(verdict, job.failOverPct(), job.failPixels(), exceeded));
                    System.err.println("brewshot: wrote " + job.jsonOut());
                } catch (java.io.IOException e) {
                    System.err.println("brewshot: failed writing json sidecar: " + e.getMessage());
                    worst = Math.max(worst, 1);
                }
            }
            if (job.diffOut() != null && !verdict.sizeMismatch()) {
                try {
                    javax.imageio.ImageIO.write(
                        BrewShotDiff.heatmap(a, b, job.options()), "png", job.diffOut().toFile());
                    System.err.println("brewshot: wrote " + job.diffOut());
                } catch (java.io.IOException e) {
                    System.err.println("brewshot: failed writing diff heatmap: " + e.getMessage());
                    worst = Math.max(worst, 1);
                }
            }
            if (exceeded) {
                System.err.println("brewshot: diff gate FAILED (verdict still written above)"
                    + (verdict.sizeMismatch() ? " — size mismatch" : ""));
                worst = Math.max(worst, 4);
            }
        }
        return worst;
    }

    private static java.awt.image.BufferedImage readImage(Path p) throws java.io.IOException {
        java.awt.image.BufferedImage img;
        try {
            img = javax.imageio.ImageIO.read(p.toFile());
        } catch (java.io.IOException e) {
            throw new java.io.IOException("cannot read image " + p + ": " + e.getMessage(), e);
        }
        if (img == null) {
            throw new java.io.IOException("not a decodable image: " + p);
        }
        return img;
    }

    private static void diffUsage() {
        System.err.println("""
            brewshot diff — compare two PNGs into a citable textual verdict

            usage: brewshot diff a.png b.png [--tolerance N] [--pixel-exact]
                                 [--mask x,y,w,h]... [--fail-over PCT] [--fail-pixels N]
                                 [--diff-out diff.png] [--json verdict.json]

              --tolerance    per-channel delta floor; at/below never counts   (default 16)
              --pixel-exact  DISABLE the default anti-aliasing forgiveness (a 3x3
                             shifted-edge heuristic; whatever it ignores is counted
                             and printed in the verdict — nothing is silently eaten)
              --mask         exclude a region on both images (dynamic content —
                             clocks, spinners); repeatable
              --fail-over    exit 4 when changed%% exceeds PCT (verdict still written)
              --fail-pixels  exit 4 when changed pixels exceed N (verdict still written)
              --diff-out     write a heatmap PNG (base dimmed, changes magenta)
              --json         write the machine-readable verdict sidecar

            A size mismatch renders an explicit sizeMismatch verdict (never a crash);
            under any --fail-* gate it exits 4. Uses ImageIO (JVM/jar path — the same
            caveat as GIF recording; not the macOS native binary).""");
    }

    /** The machine-readable sidecar CI/agent wrappers want beside the PNG. */
    private static void writeManifest(Path manifest, String input, String mode,
            int width, int height, long settleMs, String waitJs, Path out,
            Object evalResult, String failJs, boolean failJsPassed, long elapsedMs)
            throws java.io.IOException {
        StringBuilder j = new StringBuilder(256);
        j.append("{\n")
         .append("  \"input\": \"").append(MiniJson.esc(input)).append("\",\n")
         .append("  \"mode\": \"").append(mode).append("\",\n")
         .append("  \"viewport\": \"").append(width).append('x').append(height).append("\",\n")
         .append("  \"settleMs\": ").append(settleMs).append(",\n")
         .append("  \"waitJs\": ").append(waitJs == null ? "null"
             : '"' + MiniJson.esc(waitJs) + '"').append(",\n")
         .append("  \"out\": \"").append(MiniJson.esc(out.toString())).append("\",\n")
         .append("  \"outBytes\": ").append(Files.size(out)).append(",\n")
         .append("  \"eval\": ").append(evalResult == null ? "null"
             : '"' + MiniJson.esc(String.valueOf(evalResult)) + '"').append(",\n")
         .append("  \"failJs\": ").append(failJs == null ? "null"
             : '"' + MiniJson.esc(failJs) + '"').append(",\n")
         .append("  \"failJsPassed\": ").append(failJsPassed).append(",\n")
         .append("  \"elapsedMs\": ").append(elapsedMs).append(",\n")
         .append("  \"brewshot\": \"").append(BrewShot.VERSION).append("\"\n")
         .append("}\n");
        Files.writeString(manifest, j.toString());
    }

    private static int posInt(String flag, String v) {
        try { return Integer.parseInt(v); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(flag + " wants a number, got: " + v); }
    }

    private static long posLong(String flag, String v) {
        try { return Long.parseLong(v); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(flag + " wants a number (ms), got: " + v); }
    }

    private static double posDouble(String flag, String v) {
        double d;
        try { d = Double.parseDouble(v); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(flag + " wants a number, got: " + v); }
        if (!Double.isFinite(d) || d <= 0) {
            throw new IllegalArgumentException(flag + " wants a positive number, got: " + v);
        }
        return d;
    }

    private static double nonNegDouble(String flag, String v) {
        double d;
        try { d = Double.parseDouble(v); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(flag + " wants a number, got: " + v); }
        if (!Double.isFinite(d) || d < 0) {
            throw new IllegalArgumentException(flag + " wants a non-negative number, got: " + v);
        }
        return d;
    }

    private static String requireValue(String[] args, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException("flag " + args[i - 1] + " wants a value");
        }
        return args[i];
    }

    private static int err(String msg) {
        System.err.println("brewshot: " + msg);
        return 2;
    }

    /**
     * Whether an output path selects the PDF branch — a {@code .pdf} extension, matched
     * CASE-INSENSITIVELY. This is the ONLY extension-based dispatch in the CLI; a
     * case-sensitive compare let {@code -o out.PDF} fall through and write PNG bytes into a
     * {@code .PDF} file (Fix review, brewshot 99 F2). Package-private so the dispatch decision
     * is unit-testable without Chrome. Mirrors the codebase's only other case-normalization
     * ({@code osName().toLowerCase(Locale.ROOT)} in {@link BrewShot}).
     */
    static boolean isPdfOutput(Path out) {
        return out.toString().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private static void usage() {
        System.err.println("""
            brewshot — Java brews screenshots (headless Chrome over CDP, zero deps)

            usage: brewshot <url | file.html | -> [-o out.png] [--size WxH]
                            [--settle ms] [--eval js] [--version]

              <url>        open an address (http/https/file)
              <file.html>  open a local file
              -            read direct HTML source from stdin
              -o           output PNG path            (default brewshot.png)
              --size       viewport, e.g. 1440x1000   (default 1280x900)
              --settle     ms to wait before shooting (default 800)
              --eval       print a JS expression's value before shooting
              --eval-file  like --eval, JS read from a file (no shell quoting)
              --wait-js    JS predicate to poll before shooting (deterministic ready)
              --wait-timeout  ms budget for --wait-js       (default 10000)
              --clip-js    JS returning {x,y,w,h} page-coords: shoot just that rect
              --clip-selector  CSS selector: shoot just the first matching element's
                           box (exit 1 if nothing matches; exclusive with --clip-js)
              --scale      re-render the clip at this factor (e.g. 3 = 3x the pixels,
                           a TRUE re-raster, not an upscale); alone (no clip flag) it
                           shoots the full page box at that scale     (default 1)
              --clip-padding   CSS px of breathing room inflated around the clip rect
              --cookie     name=value[@domain] session auth  (repeatable)
              --header     'Name: value' on every request    (repeatable)
              --fail-js    JS assertion; false -> exit 4 (PNG still written)
              --json       write a machine-readable manifest beside the PNG
              --version    print the version and exit

            subcommands:
              diff a.png b.png   pixel diff -> citable verdict + threshold gate
                                 (see 'brewshot diff --help'; no Chrome needed)

            requires a local Chrome/Chromium (or set BREWSHOT_CHROME).""");
    }
}
