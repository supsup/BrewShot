package com.brewshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
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
 *   brewshot ./fx.html --gif 40 --gif-element ".lx-math" -o fx.gif   # film an element
 * </pre>
 *
 * Exit codes: 0 ok · 2 bad arguments · 3 no Chrome found · 4 --fail-js
 * assertion failed (output artifact still written) or a `diff` gate exceeded
 * (verdict still written) · 1 runtime failure.
 * Note: the {@code --gif} lane and `diff` are library/jar-path (ImageIO/AWT is
 * not yet supported by native-image on macOS — the CLI refuses/reports this
 * loudly rather than half-working); the PNG shoot path is native-clean.
 */
public final class Main {

    private static final int CLI_JPEG_QUALITY = 90;
    static final int MAX_STDIN_HTML_BYTES = 16 * 1024 * 1024;
    static final int MAX_EVAL_FILE_BYTES = 1024 * 1024;

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
        int jpegQuality = CLI_JPEG_QUALITY;
        boolean jpegQualitySet = false;
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
        String colorScheme = null;
        String mediaType = null;
        boolean reducedMotion = false;
        int gifFrames = 0;
        boolean gifSet = false;     // explicit flag, NOT a 0-sentinel: `--gif 0` must refuse
                                    // loudly below, never fall through to a silent still shot
        int gifDelayMs = 40;        // capture == playback cadence (the single-delay overloads)
        boolean gifDelaySet = false;
        String gifElement = null;
        boolean outSet = false;

        // The ARGUMENT-PARSING phase only: a bad flag value (missing value,
        // non-numeric --size/--settle/--wait-timeout, unreadable --eval-file)
        // is a usage error → clean message + exit 2, never a stack trace. The
        // launch/shoot phase below keeps its real errors (exit 1).
        try {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-o", "--out" -> { out = Path.of(requireValue(args, ++i)); outSet = true; }
                case "--size" -> {
                    String[] wh = requireValue(args, ++i).split("x");
                    if (wh.length != 2) { return err("--size wants WxH, e.g. 1440x1000"); }
                    width = posInt("--size width", wh[0]);
                    height = posInt("--size height", wh[1]);
                }
                case "--settle" -> settleMs = posLong("--settle", requireValue(args, ++i));
                case "--eval" -> evalExpr = requireValue(args, ++i);
                case "--eval-file" -> evalExpr = BoundedUtf8.read(
                    Path.of(requireValue(args, ++i)), MAX_EVAL_FILE_BYTES, "--eval-file");
                case "--wait-js" -> waitJs = requireValue(args, ++i);
                case "--wait-timeout" -> waitTimeoutMs = posLong("--wait-timeout", requireValue(args, ++i));
                case "--clip-js" -> clipJs = requireValue(args, ++i);
                case "--clip-selector" -> clipSelector = requireValue(args, ++i);
                case "--scale" -> scale = posDouble("--scale", requireValue(args, ++i));
                case "--clip-padding" -> clipPadding = nonNegDouble("--clip-padding", requireValue(args, ++i));
                case "--fail-js" -> failJs = requireValue(args, ++i);
                case "--json" -> jsonManifest = Path.of(requireValue(args, ++i));
                case "--jpeg-quality" -> {
                    jpegQuality = boundedInt(
                        "--jpeg-quality", requireValue(args, ++i), 1, 100);
                    jpegQualitySet = true;
                }
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
                case "--color-scheme" -> colorScheme =
                    requireOneOf("--color-scheme", requireValue(args, ++i), "dark", "light");
                case "--media" -> mediaType =
                    requireOneOf("--media", requireValue(args, ++i), "print", "screen");
                case "--reduced-motion" -> reducedMotion = true;
                case "--gif" -> { gifFrames = posInt("--gif", requireValue(args, ++i)); gifSet = true; }
                case "--gif-delay" -> {
                    gifDelayMs = posInt("--gif-delay", requireValue(args, ++i));
                    gifDelaySet = true;
                }
                case "--gif-element" -> gifElement = requireValue(args, ++i);
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
        // GIF lane (plan 6cc2d9ec): --gif N flips the shoot to the recordGif* family. All
        // constraints are LOUD usage errors (exit 2) — BrewShot output is review evidence,
        // so a flag that cannot be honored refuses rather than silently degrading.
        if (!gifSet && (gifElement != null || gifDelaySet)) {
            return err("--gif-element/--gif-delay modify a GIF recording — add --gif N (frame count)");
        }
        // F1 (review brewshot/126): the STILL path with a .gif output is the THIRD direction of
        // the misnamed-extension class — without this guard it wrote PNG bytes into a .gif with
        // exit 0. A .gif output has unambiguous intent now the gif lane exists: refuse and say so.
        if (!gifSet && isGifOutput(out)) {
            return err("-o names a .gif but --gif N was not given — add --gif N to record, "
                + "or use a raster output (.png/.jpg/.jpeg) for a still");
        }
        if (gifSet) {
            if (jpegQualitySet) {
                return err("--jpeg-quality applies only to .jpg/.jpeg still outputs, not GIF");
            }
            if (gifFrames < 1) { return err("--gif wants a positive frame count, got: " + gifFrames); }
            if (gifDelayMs < 1) { return err("--gif-delay wants a positive ms value, got: " + gifDelayMs); }
            try {
                BrewShot.effectiveGifDelayMs(gifDelayMs);
            } catch (IllegalArgumentException invalidDelay) {
                return err(invalidDelay.getMessage());
            }
            if (!outSet) {
                out = Path.of("brewshot.gif");
            } else if (!isGifOutput(out)) {
                return err("--gif records a GIF — give -o a .gif path, got: " + out
                    + " (GIF bytes under a misnamed extension would lie to their reader)");
            }
            if (clipSelector != null || clipJs != null) {
                return err("--clip-selector/--clip-js are still-shot flags — film an element with "
                    + "--gif-element SEL instead");
            }
            if (clipPadding != 0) {
                return err("--clip-padding is still-shot only (the GIF recorders film the exact "
                    + "element/page box)");
            }
        }
        // .pdf output is decided by extension, CASE-INSENSITIVELY (an `-o out.PDF` that fell
        // through to the raster path would write PNG bytes into a .PDF file and report success).
        boolean pdfOut = isPdfOutput(out);
        boolean jpegOut = isJpegOutput(out);
        boolean pngOut = isPngOutput(out);
        if (!gifSet && !pdfOut && !jpegOut && !pngOut) {
            return err("unsupported output extension: " + out
                + " (use .png, .jpg/.jpeg, .pdf, or .gif with --gif N)");
        }
        if (jpegQualitySet && !jpegOut) {
            return err("--jpeg-quality applies only to .jpg/.jpeg still outputs");
        }
        // Raster-only flags cannot be honored on the paged PDF path, and the .pdf branch runs
        // FIRST, so they would be silently ignored — a full-page PDF where the caller asked for a
        // crop. BrewShot output is review evidence, so refuse LOUDLY (exit 2) rather than emit a
        // silently-wrong artifact (Fix review, brewshot 99 F1).
        if (pdfOut && (clipSelector != null || clipJs != null || scale != 1.0 || clipPadding != 0)) {
            return err("clip/scale flags are raster-only and cannot apply to a .pdf output "
                + "(PDF is paged, not clipped) — drop --clip-selector/--clip-js/--scale/--clip-padding, "
                + "or write a raster format (.png/.jpg/.jpeg)");
        }
        if (jsonManifest != null) {
            try {
                requireDistinctPaths("-o", out, "--json", jsonManifest);
            } catch (IllegalArgumentException | java.io.IOException aliasFailure) {
                return err(aliasFailure.getMessage());
            }
        }

        // Resolve the input MODE before touching Chrome, so arg mistakes fail
        // fast with a clear message.
        String mode; // "stdin" | "url" | "file"
        if (input.equals("-")) { mode = "stdin"; }
        else if (input.matches("^[a-z][a-z0-9+.-]*://.*")) { mode = "url"; }
        else if (Files.exists(Path.of(input))) { mode = "file"; }
        else { return err("not a URL, an existing file, or '-': " + input); }

        String stdinHtml = null;
        if (mode.equals("stdin")) {
            try {
                stdinHtml = BoundedUtf8.read(
                    System.in, MAX_STDIN_HTML_BYTES, "stdin HTML");
            } catch (java.io.IOException tooLarge) {
                return err(tooLarge.getMessage());
            }
        }

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
            if (colorScheme != null) { shot.colorScheme(colorScheme); }
            if (mediaType != null) { shot.media(mediaType); }
            if (reducedMotion) { shot.reducedMotion("reduce"); }
            switch (mode) {
                case "stdin" -> shot.html(stdinHtml);
                case "url" -> shot.open(input);
                default -> shot.open(Path.of(input).toAbsolutePath().toUri().toString());
            }
            if (waitJs != null) { shot.waitFor(waitJs, waitTimeoutMs); }
            shot.settle(settleMs);
            if (evalExpr != null) {
                evalResult = shot.eval(evalExpr);
                System.out.println(evalResult);
            }
            if (gifSet) {
                // The GIF lane: element-targeted when --gif-element names a selector,
                // else the whole page. Single-delay overloads (capture == playback);
                // --scale composes as a true re-raster, exactly like the still path.
                try {
                    if (gifElement != null) {
                        shot.recordGifElement(gifElement, gifFrames, gifDelayMs, scale, out);
                    } else {
                        shot.recordGifFullPage(gifFrames, gifDelayMs, scale, out);
                    }
                } catch (IllegalArgumentException e) {
                    // No element matches --gif-element: a page-content failure (parity with
                    // --clip-selector's posture), not a usage error — exit 1, loud.
                    System.err.println("brewshot: " + e.getMessage());
                    return 1;
                } catch (LinkageError e) {
                    // The documented native-image gap, loudly: GIF assembly rides ImageIO/AWT,
                    // which native-image does not support on macOS — the jar path has it.
                    System.err.println("brewshot: GIF recording needs the jar path "
                        + "(java -jar brewshot.jar) — the native binary cannot encode GIFs "
                        + "(ImageIO/AWT is unsupported under native-image): " + e);
                    return 1;
                }
            } else if (pdfOut) {
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
                ArtifactWriter.writeBytes(out, shot.screenshotClip(
                    Math.max(0, b[0] - clipPadding), Math.max(0, b[1] - clipPadding),
                    b[2] + 2 * clipPadding, b[3] + 2 * clipPadding, scale,
                    jpegOut ? BrewShot.ImageFormat.JPEG : BrewShot.ImageFormat.PNG,
                    jpegOut ? jpegQuality : 0));
            } else if (clipJs != null) {
                Object r = shot.eval(clipJs);
                Object x = MiniJson.get(r, "x"), y = MiniJson.get(r, "y");
                Object w = MiniJson.get(r, "w"), h = MiniJson.get(r, "h");
                if (!(x instanceof Double) || !(y instanceof Double)
                        || !(w instanceof Double) || !(h instanceof Double)) {
                    return err("--clip-js must return {x,y,w,h} (page coordinates), got: " + r);
                }
                ArtifactWriter.writeBytes(out, shot.screenshotClip(
                    Math.max(0, (Double) x - clipPadding), Math.max(0, (Double) y - clipPadding),
                    (Double) w + 2 * clipPadding, (Double) h + 2 * clipPadding, scale,
                    jpegOut ? BrewShot.ImageFormat.JPEG : BrewShot.ImageFormat.PNG,
                    jpegOut ? jpegQuality : 0));
            } else if (scale != 1.0) {
                // Standalone --scale: clip the full PAGE box (scroll dimensions, not just the
                // viewport) at scale — crisp full-page stills with zero extra flags. Chrome's
                // clip.scale RE-RENDERS the region (a true re-raster), it does not upscale.
                Object dims = shot.eval("[document.documentElement.scrollWidth,"
                    + "document.documentElement.scrollHeight].join(',')");
                String[] wh = String.valueOf(dims).split(",");
                ArtifactWriter.writeBytes(out, shot.screenshotClip(0, 0,
                    Double.parseDouble(wh[0]), Double.parseDouble(wh[1]), scale,
                    jpegOut ? BrewShot.ImageFormat.JPEG : BrewShot.ImageFormat.PNG,
                    jpegOut ? jpegQuality : 0));
            } else {
                shot.screenshot(out,
                    jpegOut ? BrewShot.ImageFormat.JPEG : BrewShot.ImageFormat.PNG,
                    jpegOut ? jpegQuality : 0);
            }
            // --fail-js: assert AFTER the screenshot so failures still carry eyes.
            if (failJs != null) {
                Object ok = shot.eval("!!(" + failJs + ")");
                failJsPassed = Boolean.TRUE.equals(ok);
            }
            if (jsonManifest != null) {
                writeManifest(jsonManifest, input, mode, width, height, settleMs, waitJs,
                    out, evalResult, failJs, failJsPassed,
                    System.currentTimeMillis() - t0, gifSet ? gifDelayMs : null,
                    jpegOut ? jpegQuality : null);
            }
            System.err.println("brewshot: wrote " + out);
        }
        if (!failJsPassed) {
            System.err.println("brewshot: --fail-js assertion FAILED (output artifact still written): "
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
                    case "--tolerance" -> tolerance =
                        boundedInt("--tolerance", requireValue(args, ++i), 0, 254);
                    // AA forgiveness is ON by default; --pixel-exact is the opt-OUT for
                    // byte-faithful comparisons (every ignored pixel is counted either way).
                    case "--pixel-exact" -> ignoreAntialiasing = false;
                    case "--mask" -> {
                        String[] p = requireValue(args, ++i).split(",");
                        if (p.length != 4) { return err("--mask wants x,y,w,h"); }
                        masks.add(new int[] {intValue("--mask x", p[0].trim()),
                            intValue("--mask y", p[1].trim()),
                            posInt("--mask w", p[2].trim()), posInt("--mask h", p[3].trim())});
                    }
                    case "--fail-over" -> failOverPct =
                        boundedDouble("--fail-over", requireValue(args, ++i), 0, 100);
                    case "--fail-pixels" -> failPixels =
                        nonNegLong("--fail-pixels", requireValue(args, ++i));
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
        if (diffOut != null && !isPngOutput(diffOut)) {
            return err("--diff-out writes PNG and requires a .png output path, got: " + diffOut);
        }
        if (images.size() != 2) {
            diffUsage();
            return 2;
        }
        BrewShotDiff.Options options;
        try {
            options = new BrewShotDiff.Options(
                tolerance, ignoreAntialiasing, java.util.List.copyOf(masks));
        } catch (IllegalArgumentException invalidOptions) {
            return err(invalidOptions.getMessage());
        }
        DiffJob job = new DiffJob(images.get(0), images.get(1), options,
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
        // Preflight the complete batch before reading or writing any job. A
        // sidecar/heatmap must never replace another artifact or either source
        // baseline, including through normalized spellings, symlinks, or hard
        // links that Files.isSameFile can identify.
        try {
            requireDistinctDiffPaths(jobs);
        } catch (IllegalArgumentException | java.io.IOException aliasFailure) {
            return err(aliasFailure.getMessage());
        }
        // Same no-write preflight window: refuse an oversized input from its HEADER,
        // before any job decodes pixels. Batched here rather than per-job so an
        // oversized second job cannot be reached only after the first has already
        // allocated and written.
        try {
            for (DiffJob job : jobs) {
                requireImageWithinLimits(job.a());
                requireImageWithinLimits(job.b());
            }
        } catch (ImageTooLargeException oversized) {
            return err(oversized.getMessage());
        } catch (java.io.IOException probeFailure) {
            // An unreadable header is NOT a usage error. Fall through and let
            // readImage report it canonically as exit 1, unchanged.
        }

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
                    ArtifactWriter.writeString(job.jsonOut(),
                        BrewShotDiff.toJson(verdict, job.failOverPct(), job.failPixels(), exceeded),
                        StandardCharsets.UTF_8);
                    System.err.println("brewshot: wrote " + job.jsonOut());
                } catch (java.io.IOException e) {
                    System.err.println("brewshot: failed writing json sidecar: " + e.getMessage());
                    worst = Math.max(worst, 1);
                }
            }
            if (job.diffOut() != null && !verdict.sizeMismatch()) {
                try {
                    ArtifactWriter.write(job.diffOut(), temporary -> {
                        boolean written = javax.imageio.ImageIO.write(
                            BrewShotDiff.heatmap(a, b, job.options()),
                            "png", temporary.toFile());
                        if (!written) {
                            throw new java.io.IOException("no PNG writer available");
                        }
                    });
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

    /** A diff input whose DECLARED dimensions blow a configured ceiling (usage error, exit 2). */
    private static final class ImageTooLargeException extends Exception {
        ImageTooLargeException(String message) { super(message); }
    }

    /** Max px per axis for a diff input; default 16384, override -Dbrewshot.maxImageDimension=N. */
    private static long maxImageDimension() {
        return Long.getLong("brewshot.maxImageDimension", 16_384L);
    }

    /** Max total area (w*h) for a diff input; default 64 MP, override -Dbrewshot.maxImagePixels=N. */
    private static long maxImagePixels() {
        return Long.getLong("brewshot.maxImagePixels", 67_108_864L);
    }

    /**
     * Refuse an oversized diff INPUT from its header, BEFORE the full decode.
     *
     * <p>{@link javax.imageio.ImageIO#read} allocates a {@code w*h} raster as its first
     * act, so by the time a decompression-bomb-scale PNG fails it has already tried to
     * take the memory — a 100000x100000 declared image asks for 40 GB from a 26-byte
     * file. This reads only {@code getWidth(0)}/{@code getHeight(0)} off an
     * {@link javax.imageio.ImageReader}, which parses the header and decodes no pixels,
     * so the refusal costs nothing.
     *
     * <p>Both ceilings are read FRESH on every call rather than cached in a static, so a
     * test (and a caller who sets the property late) sees the current value. The
     * comparison is strictly {@code >}: an exactly-at-limit input is accepted.
     *
     * <p>Deliberately NOT this method's job: deciding that a file is not an image. When
     * no reader is registered, or the stream cannot be opened, it returns quietly and
     * leaves {@link #readImage} to report it canonically as exit 1 — so this guard can
     * only ever turn a would-be decode into a usage refusal, never change the "not an
     * image" path.
     */
    private static void requireImageWithinLimits(Path p)
            throws ImageTooLargeException, java.io.IOException {
        try (javax.imageio.stream.ImageInputStream stream =
                 javax.imageio.ImageIO.createImageInputStream(p.toFile())) {
            if (stream == null) {
                return;
            }
            java.util.Iterator<javax.imageio.ImageReader> readers =
                javax.imageio.ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return;
            }
            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                long width = reader.getWidth(0);
                long height = reader.getHeight(0);
                long maxDimension = maxImageDimension();
                long maxPixels = maxImagePixels();
                if (width > maxDimension || height > maxDimension) {
                    throw new ImageTooLargeException(p + ": " + width + "x" + height
                        + " exceeds the per-axis limit brewshot.maxImageDimension="
                        + maxDimension + " px — raise it with -Dbrewshot.maxImageDimension=N");
                }
                // Both operands are already <= maxDimension (16384 by default), so the
                // product cannot overflow a long even at the widest accepted input.
                if (width * height > maxPixels) {
                    throw new ImageTooLargeException(p + ": " + width + "x" + height + " = "
                        + (width * height) + " px exceeds the area limit brewshot.maxImagePixels="
                        + maxPixels + " — raise it with -Dbrewshot.maxImagePixels=N");
                }
            } finally {
                reader.dispose();
            }
        }
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

              --tolerance    per-channel delta floor, 0-254; at/below never
                             counts                                      (default 16)
              --pixel-exact  DISABLE the default anti-aliasing forgiveness (a 3x3
                             shifted-edge heuristic; whatever it ignores is counted
                             and printed in the verdict — nothing is silently eaten)
              --mask         exclude a region on both images (dynamic content —
                             clocks, spinners); repeatable
              --fail-over    exit 4 when changed%% exceeds PCT, 0-100
                             (verdict still written)
              --fail-pixels  exit 4 when changed pixels exceed N (verdict still written)
              --diff-out     write a heatmap PNG (base dimmed, changes magenta)
              --json         write the machine-readable verdict sidecar

            A size mismatch renders an explicit sizeMismatch verdict (never a crash);
            under any --fail-* gate it exits 4. Uses ImageIO (JVM/jar path — the same
            caveat as GIF recording; not the macOS native binary).""");
    }

    /** The machine-readable sidecar CI/agent wrappers want beside the PNG. */
    static void writeManifest(Path manifest, String input, String mode,
            int width, int height, long settleMs, String waitJs, Path out,
            Object evalResult, String failJs, boolean failJsPassed, long elapsedMs,
            Integer requestedGifDelayMs, Integer jpegQuality)
            throws java.io.IOException {
        java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("input", input);
        fields.put("mode", mode);
        fields.put("viewport", width + "x" + height);
        fields.put("settleMs", settleMs);
        fields.put("waitJs", waitJs);
        fields.put("out", out.toString());
        fields.put("outBytes", Files.size(out));
        fields.put("eval", evalResult);
        fields.put("failJs", failJs);
        fields.put("failJsPassed", failJsPassed);
        fields.put("elapsedMs", elapsedMs);
        if (requestedGifDelayMs != null) {
            fields.put("gifDelayMsRequested", requestedGifDelayMs);
            fields.put("gifDelayMsEncoded",
                BrewShot.effectiveGifDelayMs(requestedGifDelayMs));
        }
        if (jpegQuality != null) {
            fields.put("jpegQuality", jpegQuality);
        }
        fields.put("brewshot", BrewShot.VERSION);
        ArtifactWriter.writeString(
            manifest, MiniJson.stringifyPretty(fields) + "\n", StandardCharsets.UTF_8);
    }

    private static int posInt(String flag, String v) {
        int value;
        try { value = Integer.parseInt(v); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(flag + " wants a number, got: " + v); }
        return Validation.positiveInt(flag, value);
    }

    private static long posLong(String flag, String v) {
        long value;
        try { value = Long.parseLong(v); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(flag + " wants a number (ms), got: " + v); }
        return Validation.positiveLong(flag, value);
    }

    private static long nonNegLong(String flag, String v) {
        long value;
        try { value = Long.parseLong(v); }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " wants a number, got: " + v);
        }
        return Validation.nonNegativeLong(flag, value);
    }

    private static int intValue(String flag, String v) {
        try { return Integer.parseInt(v); }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " wants an integer, got: " + v);
        }
    }

    private static int boundedInt(String flag, String v, int min, int max) {
        int value = intValue(flag, v);
        return Validation.intRange(flag, value, min, max);
    }

    private static double posDouble(String flag, String v) {
        double d;
        try { d = Double.parseDouble(v); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(flag + " wants a number, got: " + v); }
        return Validation.positiveFinite(flag, d);
    }

    private static double nonNegDouble(String flag, String v) {
        double d;
        try { d = Double.parseDouble(v); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(flag + " wants a number, got: " + v); }
        return Validation.nonNegativeFinite(flag, d);
    }

    private static double boundedDouble(String flag, String v, double min, double max) {
        double d;
        try { d = Double.parseDouble(v); }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " wants a number, got: " + v);
        }
        return Validation.finiteRange(flag, d, min, max);
    }

    private static void requireDistinctPaths(
            String firstRole, Path first, String secondRole, Path second)
            throws java.io.IOException {
        if (first == null || second == null) {
            return;
        }
        if (pathsAlias(first, second)) {
            throw new IllegalArgumentException(
                firstRole + " and " + secondRole
                    + " must name different files, but both resolve to " + first);
        }
    }

    private record NamedPath(String role, Path path) { }

    /**
     * Compare the entire future batch, not only one job at a time: an early
     * job's output cannot overwrite a later baseline before that job reads it,
     * and no two sidecars/heatmaps may overwrite each other.
     */
    private static void requireDistinctDiffPaths(java.util.List<DiffJob> jobs)
            throws java.io.IOException {
        java.util.List<NamedPath> outputs = new java.util.ArrayList<>();
        java.util.List<NamedPath> inputs = new java.util.ArrayList<>();
        for (int index = 0; index < jobs.size(); index++) {
            DiffJob job = jobs.get(index);
            String prefix = "diff job " + (index + 1) + " ";
            inputs.add(new NamedPath(prefix + "first input", job.a()));
            inputs.add(new NamedPath(prefix + "second input", job.b()));
            if (job.jsonOut() != null) {
                outputs.add(new NamedPath(prefix + "--json", job.jsonOut()));
            }
            if (job.diffOut() != null) {
                outputs.add(new NamedPath(prefix + "--diff-out", job.diffOut()));
            }
        }
        for (int first = 0; first < outputs.size(); first++) {
            for (int second = first + 1; second < outputs.size(); second++) {
                NamedPath a = outputs.get(first);
                NamedPath b = outputs.get(second);
                requireDistinctPaths(a.role(), a.path(), b.role(), b.path());
            }
        }
        for (NamedPath output : outputs) {
            for (NamedPath input : inputs) {
                requireDistinctPaths(
                    output.role(), output.path(), input.role(), input.path());
            }
        }
    }

    /**
     * Lexical normalization covers paths that do not exist yet; isSameFile
     * additionally catches existing symlink and hard-link aliases. When either
     * target is absent, reject normalized case-fold collisions conservatively:
     * Java has no portable read-only per-mount case-sensitivity query, and a
     * live probe would itself write during the no-write preflight.
     */
    static boolean pathsAlias(Path first, Path second) throws java.io.IOException {
        Path identityFirst = ArtifactWriter.outputIdentity(first);
        Path identitySecond = ArtifactWriter.outputIdentity(second);
        if (identityFirst.equals(identitySecond)) {
            return true;
        }
        boolean firstExists = Files.exists(identityFirst);
        boolean secondExists = Files.exists(identitySecond);
        if (firstExists && secondExists) {
            return Files.isSameFile(identityFirst, identitySecond);
        }
        return foldedAbsentPath(identityFirst).equals(foldedAbsentPath(identitySecond));
    }

    /**
     * A deliberately broad Unicode fold for absent-path fail-closed identity.
     * NFKC catches canonically/compatibly equivalent spellings; upper-then-lower
     * also folds multi-character mappings such as sharp-s.
     */
    private static String foldedAbsentPath(Path path) {
        String normalized =
            Normalizer.normalize(path.toString(), Normalizer.Form.NFKC);
        String folded =
            normalized.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
        return Normalizer.normalize(folded, Normalizer.Form.NFKC);
    }

    /** Validate a flag value against a fixed allowed set; throws (usage error, exit 2) otherwise. */
    private static String requireOneOf(String flag, String v, String... allowed) {
        for (String a : allowed) {
            if (a.equals(v)) { return v; }
        }
        throw new IllegalArgumentException(
            flag + " wants one of " + String.join("|", allowed) + ", got: " + v);
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
     * CASE-INSENSITIVELY. A case-sensitive compare let {@code -o out.PDF} fall through and write PNG bytes into a
     * {@code .PDF} file (Fix review, brewshot 99 F2). Package-private so the dispatch decision
     * is unit-testable without Chrome. Mirrors the codebase's only other case-normalization
     * ({@code osName().toLowerCase(Locale.ROOT)} in {@link BrewShot}).
     */
    static boolean isPdfOutput(Path out) {
        return out.toString().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    /**
     * Whether an output path is a {@code .gif}, matched case-insensitively — the {@code --gif}
     * lane's output guard (an explicit non-.gif {@code -o} under {@code --gif} is refused, exit 2,
     * never GIF bytes under a misnamed extension). Same normalization rule as
     * {@link #isPdfOutput}; package-private so the guard is unit-testable without Chrome.
     */
    static boolean isGifOutput(Path out) {
        return out.toString().toLowerCase(Locale.ROOT).endsWith(".gif");
    }

    /**
     * Whether a still output selects Chrome's JPEG encoder. Both conventional
     * extensions are matched case-insensitively; PNG is the only other accepted
     * raster extension.
     */
    static boolean isJpegOutput(Path out) {
        String lower = out.toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }

    /** Whether a still output explicitly names the lossless PNG format. */
    static boolean isPngOutput(Path out) {
        return out.toString().toLowerCase(Locale.ROOT).endsWith(".png");
    }

    private static void usage() {
        System.err.println("""
            brewshot — Java brews screenshots (headless Chrome over CDP, zero deps)

            usage: brewshot <url | file.html | -> [-o out.png] [--size WxH]
                            [--settle ms] [--eval js] [--version]

              <url>        open an address (http/https/file)
              <file.html>  open a local file
              -            read direct HTML source from stdin
              -o           output .png, .jpg/.jpeg, .pdf, or (with --gif) .gif
                           path; JPEG quality defaults to 90 (default brewshot.png)
              --size       viewport, e.g. 1440x1000   (default 1280x900)
              --settle     ms to wait before shooting (default 800)
              --eval       print a JS expression's value before shooting
              --eval-file  like --eval, JS read from a UTF-8 file (max 1 MiB;
                           no shell quoting)
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
              --color-scheme dark|light  force prefers-color-scheme before capture
              --media      print|screen  force the emulated media type (e.g. @media print)
              --reduced-motion  force prefers-reduced-motion: reduce before capture
              --fail-js    JS assertion; false -> exit 4 (output artifact still written)
              --json       write a machine-readable manifest beside the output
              --jpeg-quality  JPEG quality 1-100, only with .jpg/.jpeg output
                           (default 90)
              --gif N      record N frames as a looping GIF instead of a still
                           (default -o becomes brewshot.gif; an explicit non-.gif
                           -o is refused). JAR PATH ONLY: GIF assembly rides
                           ImageIO/AWT, which the native binary does not have —
                           run via java -jar brewshot.jar
              --gif-delay  ms between frames, capture AND playback; GIF rounds
                           to nearest 10 ms, minimum 20 ms          (default 40)
              --gif-element  CSS selector: film just that element's box (resolved
                           once, exit 1 if nothing matches); composes with --scale
              --version    print the version and exit

            subcommands:
              diff a.png b.png   pixel diff -> citable verdict + threshold gate
                                 (see 'brewshot diff --help'; no Chrome needed)

            stdin HTML is UTF-8 and capped at 16 MiB. Unknown output extensions
            are refused rather than receiving misnamed PNG bytes.

            requires a local Chrome/Chromium (or set BREWSHOT_CHROME).""");
    }
}
