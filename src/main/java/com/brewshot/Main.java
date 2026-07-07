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
 * Exit codes: 0 ok · 2 bad arguments · 3 no Chrome found · 1 runtime failure.
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

        try (BrewShot shot = BrewShot.launch(width, height)) {
            switch (mode) {
                case "stdin" -> shot.html(new String(
                    System.in.readAllBytes(), StandardCharsets.UTF_8));
                case "url" -> shot.open(input);
                default -> shot.open(Path.of(input).toAbsolutePath().toUri().toString());
            }
            shot.settle(settleMs);
            if (evalExpr != null) {
                System.out.println(shot.eval(evalExpr));
            }
            shot.screenshot(out);
            System.err.println("brewshot: wrote " + out);
        }
        return 0;
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
              --version    print the version and exit

            requires a local Chrome/Chromium (or set BREWSHOT_CHROME).""");
    }
}
