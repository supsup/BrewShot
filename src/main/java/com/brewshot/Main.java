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
 * Note: GIF recording is library-only for now (ImageIO/AWT is not yet
 * supported by native-image on macOS); the PNG path below is native-clean.
 */
public final class Main {

    private Main() { }

    public static void main(String[] args) throws Exception {
        String input = null;
        Path out = Path.of("brewshot.png");
        int width = 1280;
        int height = 900;
        long settleMs = 800;
        String evalExpr = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-o", "--out" -> out = Path.of(args[++i]);
                case "--size" -> {
                    String[] wh = args[++i].split("x");
                    width = Integer.parseInt(wh[0]);
                    height = Integer.parseInt(wh[1]);
                }
                case "--settle" -> settleMs = Long.parseLong(args[++i]);
                case "--eval" -> evalExpr = args[++i];
                case "-h", "--help" -> { usage(); return; }
                default -> input = args[i];
            }
        }
        if (input == null) { usage(); System.exit(2); }
        if (!BrewShot.available()) {
            System.err.println("brewshot: no Chrome/Chromium found (set BREWSHOT_CHROME)");
            System.exit(3);
        }

        try (BrewShot shot = BrewShot.launch(width, height)) {
            if (input.equals("-")) {
                shot.html(new String(System.in.readAllBytes(), StandardCharsets.UTF_8));
            } else if (input.matches("^[a-z][a-z0-9+.-]*://.*")) {
                shot.open(input);
            } else if (Files.exists(Path.of(input))) {
                shot.open(Path.of(input).toAbsolutePath().toUri().toString());
            } else {
                System.err.println("brewshot: not a URL, file, or '-': " + input);
                System.exit(2);
                return;
            }
            shot.settle(settleMs);
            if (evalExpr != null) {
                System.out.println(shot.eval(evalExpr));
            }
            shot.screenshot(out);
            System.err.println("brewshot: wrote " + out);
        }
    }

    private static void usage() {
        System.err.println("""
            brewshot — Java brews screenshots (headless Chrome over CDP, zero deps)

            usage: brewshot <url | file.html | -> [-o out.png] [--size WxH]
                            [--settle ms] [--eval js]

              <url>        open an address (http/https/file)
              <file.html>  open a local file
              -            read direct HTML source from stdin
              -o           output PNG path            (default brewshot.png)
              --size       viewport, e.g. 1440x1000   (default 1280x900)
              --settle     ms to wait before shooting (default 800)
              --eval       print a JS expression's value before shooting

            requires a local Chrome/Chromium (or set BREWSHOT_CHROME).""");
    }
}
