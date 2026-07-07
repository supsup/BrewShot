package com.brewshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
 * assertion failed (screenshot still written) · 1 runtime failure.
 * Note: GIF recording is library-only for now (ImageIO/AWT is not yet
 * supported by native-image on macOS); the PNG path is native-clean.
 */
public final class Main {

    private Main() { }

    public static void main(String[] args) throws Exception {
        // Exit code decided OUTSIDE any try-with-resources: a System.exit
        // inside one would skip close() and orphan a running Chrome.
        System.exit(run(args));
    }

    /** Testable entry: parse, shoot, return the exit code. Never calls System.exit. */
    static int run(String[] args) throws Exception {
        String input = null;
        Path out = Path.of("brewshot.png");
        int width = 1280;
        int height = 900;
        long settleMs = 800;
        String evalExpr = null;
        String waitJs = null;
        long waitTimeoutMs = 10_000;
        String clipJs = null;
        String failJs = null;
        Path jsonManifest = null;
        java.util.List<String[]> cookies = new java.util.ArrayList<>();
        java.util.List<String[]> headers = new java.util.ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-o", "--out" -> out = Path.of(requireValue(args, ++i));
                case "--size" -> {
                    String[] wh = requireValue(args, ++i).split("x");
                    if (wh.length != 2) { return err("--size wants WxH, e.g. 1440x1000"); }
                    width = Integer.parseInt(wh[0]);
                    height = Integer.parseInt(wh[1]);
                }
                case "--settle" -> settleMs = Long.parseLong(requireValue(args, ++i));
                case "--eval" -> evalExpr = requireValue(args, ++i);
                case "--eval-file" -> evalExpr = Files.readString(Path.of(requireValue(args, ++i)));
                case "--wait-js" -> waitJs = requireValue(args, ++i);
                case "--wait-timeout" -> waitTimeoutMs = Long.parseLong(requireValue(args, ++i));
                case "--clip-js" -> clipJs = requireValue(args, ++i);
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
        if (input == null) { usage(); return 2; }

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
            if (clipJs != null) {
                Object r = shot.eval(clipJs);
                Object x = MiniJson.get(r, "x"), y = MiniJson.get(r, "y");
                Object w = MiniJson.get(r, "w"), h = MiniJson.get(r, "h");
                if (!(x instanceof Double) || !(y instanceof Double)
                        || !(w instanceof Double) || !(h instanceof Double)) {
                    return err("--clip-js must return {x,y,w,h} (page coordinates), got: " + r);
                }
                Files.write(out, shot.screenshotClip(
                    (Double) x, (Double) y, (Double) w, (Double) h));
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
              --cookie     name=value[@domain] session auth  (repeatable)
              --header     'Name: value' on every request    (repeatable)
              --fail-js    JS assertion; false -> exit 4 (PNG still written)
              --json       write a machine-readable manifest beside the PNG
              --version    print the version and exit

            requires a local Chrome/Chromium (or set BREWSHOT_CHROME).""");
    }
}
