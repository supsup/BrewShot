package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CLI arg handling — no Chrome needed: every case exits before launch. */
class MainCliTest {

    @Test
    void helpStatesTheExactByteAndImageAllocationContracts() throws Exception {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        PrintStream original = System.err;
        int code;
        try {
            System.setErr(new PrintStream(errors));
            code = Main.run(new String[] {"--help"});
        } finally {
            System.setErr(original);
        }
        String help = errors.toString();
        assertEquals(0, code);
        assertTrue(help.contains("brewshot.maxCdpMessageBytes")
                && help.contains("exact UTF-8 bytes per complete CDP message"), help);
        assertTrue(help.contains("image-header dimension checks after Base64 decode"), help);
        assertTrue(help.contains("not total GIF encoder heap"), help);
        assertTrue(help.contains("--dpr") && help.contains("integer 1-4")
                && help.contains("distinct from") && help.contains("--scale"), help);
        assertTrue(help.contains("--wait-timeout") && help.contains("requires --wait-js"), help);
    }

    @Test
    void exitCodes() throws Exception {
        assertEquals(0, Main.run(new String[] {"--help"}));
        assertEquals(0, Main.run(new String[] {"--version"}));
        assertEquals(2, Main.run(new String[] {}));                       // no input
        assertEquals(2, Main.run(new String[] {"--unknown-flag", "x"}));  // unknown flag
        assertEquals(2, Main.run(new String[] {"no-such-file.html"}));    // not url/file/-
        assertEquals(2, Main.run(new String[] {"--size", "wrong", "x.html"})); // bad WxH
    }

    @Test
    void equalsFormFlagsParseLikeSpaceForm() throws Exception {
        // --flag=value must NOT be rejected as an unknown flag (Lattice, brewshot/8)
        assertEquals(0, Main.run(new String[] {"--help"}));
        // these reach the input-resolution stage (exit 2 = "no such file", not
        // "unknown flag") which proves the = form was parsed, not swallowed:
        assertEquals(2, Main.run(new String[] {"--out=x.png", "no-such-file.html"}));
        assertEquals(2, Main.run(new String[] {"--size=1440x900", "no-such-file.html"}));
        assertEquals(2, Main.run(new String[] {"--wait-js=true", "no-such-file.html"}));
        // a genuinely unknown =flag still errors
        assertEquals(2, Main.run(new String[] {"--bogus=1", "no-such-file.html"}));
    }

    @Test
    void orphanWaitTimeoutRefusesBeforeInputChromeOrArtifacts(@TempDir Path directory)
            throws Exception {
        Path output = directory.resolve("never-written.png");
        Path receipt = directory.resolve("never-written.json");
        String missing = directory.resolve("definitely-missing.html").toString();
        java.util.List<String[]> spellingsAndOrders = java.util.List.of(
            new String[] {"--wait-timeout", "10000", "-o", output.toString(),
                "--json", receipt.toString(), missing},
            new String[] {missing, "--wait-timeout", "10000", "-o", output.toString(),
                "--json", receipt.toString()},
            new String[] {"--wait-timeout=10000", "-o", output.toString(),
                "--json", receipt.toString(), missing},
            new String[] {missing, "--wait-timeout=10000", "-o", output.toString(),
                "--json", receipt.toString()}
        );

        for (String[] args : spellingsAndOrders) {
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            PrintStream original = System.err;
            int code;
            try {
                System.setErr(new PrintStream(errors));
                code = Main.run(args);
            } finally {
                System.setErr(original);
            }
            assertEquals(2, code, java.util.Arrays.toString(args));
            assertTrue(errors.toString().contains("--wait-timeout requires --wait-js"),
                errors.toString());
            org.junit.jupiter.api.Assertions.assertFalse(Files.exists(output));
            org.junit.jupiter.api.Assertions.assertFalse(Files.exists(receipt));
            try (var files = Files.list(directory)) {
                assertEquals(0L, files.count(),
                    "the dependency refusal must precede every filesystem side effect");
            }
        }

        ByteArrayOutputStream pairedErrors = new ByteArrayOutputStream();
        PrintStream original = System.err;
        int pairedCode;
        try {
            System.setErr(new PrintStream(pairedErrors));
            pairedCode = Main.run(new String[] {
                "--wait-js", "true", "--wait-timeout=10000", missing});
        } finally {
            System.setErr(original);
        }
        assertEquals(2, pairedCode);
        assertTrue(pairedErrors.toString().contains("not a URL, an existing file, or '-'"),
            pairedErrors.toString());
        org.junit.jupiter.api.Assertions.assertFalse(
            pairedErrors.toString().contains("--wait-timeout requires --wait-js"),
            pairedErrors.toString());

        ByteArrayOutputStream missingInputErrors = new ByteArrayOutputStream();
        original = System.err;
        int missingInputCode;
        try {
            System.setErr(new PrintStream(missingInputErrors));
            missingInputCode = Main.run(new String[] {"--wait-timeout", "10000"});
        } finally {
            System.setErr(original);
        }
        assertEquals(2, missingInputCode);
        assertTrue(missingInputErrors.toString().contains("usage: brewshot"),
            missingInputErrors.toString());
        org.junit.jupiter.api.Assertions.assertFalse(
            missingInputErrors.toString().contains("--wait-timeout requires --wait-js"),
            "input presence validation must run before the dependency guard");
    }

    @Test
    void newFlagShapesValidate() throws Exception {
        assertEquals(2, Main.run(new String[] {"--cookie", "malformed", "x.html"}));
        assertEquals(2, Main.run(new String[] {"--header", "no-colon", "x.html"}));
    }

    @Test
    void clipFlagShapesValidate() throws Exception {
        // --clip-selector and --clip-js are exclusive clip sources
        assertEquals(2, Main.run(new String[] {
            "--clip-selector", "svg", "--clip-js", "({x:0,y:0,w:1,h:1})", "https://example.com"}));
        // --scale must be a positive finite number
        assertEquals(2, Main.run(new String[] {"--scale", "nope", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--scale", "0", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--scale", "-2", "https://example.com"}));
        // --clip-padding must be non-negative
        assertEquals(2, Main.run(new String[] {"--clip-padding", "-4", "https://example.com"}));
        // = forms parse through to input resolution (exit 2 = "no such file", not unknown flag)
        assertEquals(2, Main.run(new String[] {"--clip-selector=svg", "no-such-file.html"}));
        assertEquals(2, Main.run(new String[] {"--scale=3", "no-such-file.html"}));
        assertEquals(2, Main.run(new String[] {"--clip-padding=8", "no-such-file.html"}));
    }

    @Test
    void emulatedMediaFlagShapesValidate() throws Exception {
        // plan 02af3a3d: --color-scheme/--media are a fixed enum; a bad value is a usage
        // error (exit 2) caught during arg parsing, before Chrome is ever touched.
        assertEquals(2, Main.run(new String[] {"--color-scheme", "bogus", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--media", "bogus", "https://example.com"}));
        // = forms parse through to input resolution (exit 2 = "no such file", not unknown flag)
        assertEquals(2, Main.run(new String[] {"--color-scheme=dark", "no-such-file.html"}));
        assertEquals(2, Main.run(new String[] {"--media=print", "no-such-file.html"}));
        assertEquals(2, Main.run(new String[] {"--reduced-motion", "no-such-file.html"}));
        assertEquals(2, Main.run(new String[] {"--timezone", " ", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--timezone=Asia/Tokyo", "no-such-file.html"}));
    }

    @Test
    void dprIsAnIntegerOneThroughFourAndRefusesBeforeArtifacts(@TempDir Path directory)
            throws Exception {
        Path output = directory.resolve("never-written.png");
        Path receipt = directory.resolve("never-written.json");
        for (String rejected : new String[] {"0", "-1", "1.5", "NaN", "Infinity", "5"}) {
            assertEquals(2, Main.run(new String[] {
                "--dpr", rejected, "-o", output.toString(), "--json", receipt.toString(),
                "https://example.com"}), rejected);
            org.junit.jupiter.api.Assertions.assertFalse(Files.exists(output), rejected);
            org.junit.jupiter.api.Assertions.assertFalse(Files.exists(receipt), rejected);
        }
        assertEquals(2, Main.run(new String[] {"--dpr=2", "no-such-file.html"}),
            "equals form must parse and reach input resolution");
    }

    @Test
    void gifFlagShapesValidate() throws Exception {
        // plan 6cc2d9ec: every constraint is a LOUD exit 2 at parse, before Chrome.
        // modifiers without --gif N
        assertEquals(2, Main.run(new String[] {"--gif-element", "svg", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--gif-delay", "40", "https://example.com"}));
        // frame count / delay must be positive numbers — and `--gif 0` must REFUSE,
        // never fall through to a silent still shot (the 0-sentinel trap)
        assertEquals(2, Main.run(new String[] {"--gif", "nope", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--gif", "0", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--gif", "-3", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--gif", "10", "--gif-delay", "0", "https://example.com"}));
        // an explicit non-.gif -o under --gif is refused (evidence honesty, mirrors the
        // .pdf-vs-raster refusal), including a .pdf target
        assertEquals(2, Main.run(new String[] {"--gif", "10", "-o", "out.png", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--gif", "10", "-o", "out.pdf", "https://example.com"}));
        // still-shot-only flags are refused with --gif
        assertEquals(2, Main.run(new String[] {"--gif", "10", "--clip-selector", "svg", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--gif", "10", "--clip-js", "({})", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--gif", "10", "--clip-padding", "4", "https://example.com"}));
        // = forms parse through to input resolution (exit 2 = "no such file", not unknown flag)
        assertEquals(2, Main.run(new String[] {"--gif=10", "no-such-file.html"}));
        assertEquals(2, Main.run(new String[] {"--gif=10", "--gif-element=svg", "no-such-file.html"}));
        assertEquals(2, Main.run(new String[] {"--gif=10", "--gif-delay=25", "no-such-file.html"}));
        // F1 (review brewshot/126, live-repro'd): the STILL path with a .gif out must refuse —
        // the third direction of the misnamed-extension class (it wrote PNG bytes into a .gif,
        // exit 0). Case-insensitive like the guard itself.
        assertEquals(2, Main.run(new String[] {"-o", "out.gif", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"-o", "out.GIF", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--out=still.gif", "no-such-file.html"}));
    }

    @Test
    void gifOutputExtensionMatchesCaseInsensitively() {
        // The --gif output guard's unit: same normalization rule as isPdfOutput.
        org.junit.jupiter.api.Assertions.assertTrue(Main.isGifOutput(java.nio.file.Path.of("x.gif")));
        org.junit.jupiter.api.Assertions.assertTrue(Main.isGifOutput(java.nio.file.Path.of("x.GIF")));
        org.junit.jupiter.api.Assertions.assertFalse(Main.isGifOutput(java.nio.file.Path.of("x.png")));
        org.junit.jupiter.api.Assertions.assertFalse(Main.isGifOutput(java.nio.file.Path.of("gif")));
    }

    @Test
    void badFlagValuesExitCleanlyNotWithAStackTrace() throws Exception {
        // Lattice's four repros (brewshot/9): thrown parse/value exceptions must
        // route through err() -> exit 2, never escape as a stack trace + exit 1.
        assertEquals(2, Main.run(new String[] {"https://example.com", "-o"}));            // missing value
        assertEquals(2, Main.run(new String[] {"--size", "axb", "https://example.com"})); // WxH but non-numeric
        assertEquals(2, Main.run(new String[] {"https://example.com", "--settle", "nope"}));
        assertEquals(2, Main.run(new String[] {"https://example.com", "--wait-timeout", "nope"}));
        assertEquals(2, Main.run(new String[] {"--eval-file", "no-such-file.js", "https://example.com"}));
    }

    @Test
    void positiveParserNamesActuallyRejectZeroAndNegativeValues() throws Exception {
        assertEquals(2, Main.run(new String[] {"--size", "0x100", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--size", "100x-1", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--settle", "0", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {"--wait-timeout", "-1", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {
            "diff", "a.png", "b.png", "--mask", "0,0,0,1"}));
        assertEquals(2, Main.run(new String[] {
            "diff", "a.png", "b.png", "--fail-pixels", "-1"}));
        assertEquals(2, Main.run(new String[] {
            "diff", "a.png", "b.png", "--tolerance", "-1"}));
        assertEquals(2, Main.run(new String[] {
            "diff", "a.png", "b.png", "--tolerance", "255"}));
        assertEquals(2, Main.run(new String[] {
            "diff", "a.png", "b.png", "--fail-over", "100.0001"}));
        assertEquals(2, Main.run(new String[] {
            "diff", "a.png", "b.png", "--mask", "2147483647,0,1,1"}));

        // Zero is intentionally valid for non-negative diff thresholds and
        // coordinates; these parse and reach image IO (exit 1), not usage (2).
        assertEquals(1, Main.run(new String[] {
            "diff", "no-a.png", "no-b.png", "--mask", "0,0,1,1",
            "--fail-pixels", "0", "--tolerance", "0"}));
        assertEquals(1, Main.run(new String[] {
            "diff", "no-a.png", "no-b.png", "--fail-over", "100"}));
    }

    @Test
    void captureOutputAndManifestMustNotAlias(@TempDir Path directory) throws Exception {
        Path output = directory.resolve("capture.png");
        Path normalizedAlias =
            directory.resolve("not-created").resolve("..").resolve("capture.png");
        assertEquals(2, Main.run(new String[] {
            "https://example.com", "-o", output.toString(),
            "--json", normalizedAlias.toString()}));

        Path linkedOutput = directory.resolve("linked-capture.png");
        Path linkedManifest = directory.resolve("linked-manifest.json");
        Files.writeString(linkedOutput, "existing evidence");
        Files.createLink(linkedManifest, linkedOutput);
        org.junit.jupiter.api.Assertions.assertTrue(
            Files.isSameFile(linkedOutput, linkedManifest));

        assertEquals(2, Main.run(new String[] {
            "https://example.com", "-o", linkedOutput.toString(),
            "--json", linkedManifest.toString()}));
        assertEquals("existing evidence", Files.readString(linkedOutput));
        assertEquals("existing evidence", Files.readString(linkedManifest));
    }

    @Test
    void absentCaseVariantCaptureOutputsRejectBeforeInputResolutionOrWrite(
            @TempDir Path directory) throws Exception {
        Path output = directory.resolve("Result.png");
        Path manifest = directory.resolve("result.png");
        Path missingInput = directory.resolve("missing-input.html");
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(output));
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(manifest));

        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        PrintStream original = System.err;
        int code;
        try {
            System.setErr(new PrintStream(errors));
            code = Main.run(new String[] {
                missingInput.toString(), "-o", output.toString(),
                "--json", manifest.toString()});
        } finally {
            System.setErr(original);
        }

        assertEquals(2, code);
        org.junit.jupiter.api.Assertions.assertTrue(
            errors.toString().contains("must name different files"), errors.toString());
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(output));
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(manifest));
    }

    @Test
    void brokenManifestSymlinkCannotBecomeAnAliasAfterCaptureWrites(
            @TempDir Path directory) throws Exception {
        Path output = directory.resolve("future-capture.png");
        Path manifest = directory.resolve("future-manifest.json");
        try {
            Files.createSymbolicLink(manifest, output.getFileName());
        } catch (UnsupportedOperationException | SecurityException
                | java.io.IOException unavailable) {
            Assumptions.assumeTrue(false,
                "symbolic links unavailable for this test: " + unavailable);
        }
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(manifest),
            "the setup link must be broken until the capture path is created");

        assertEquals(2, Main.run(new String[] {
            "https://example.com", "-o", output.toString(),
            "--json", manifest.toString()}));
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(output),
            "alias rejection must happen before the capture can make the link valid");
        org.junit.jupiter.api.Assertions.assertTrue(Files.isSymbolicLink(manifest));
    }

    @Test
    void nonexistentOutputsUnderSymlinkedParentStillAlias(
            @TempDir Path directory) throws Exception {
        Path realDirectory = Files.createDirectory(directory.resolve("real"));
        Path linkedDirectory = directory.resolve("linked");
        try {
            Files.createSymbolicLink(linkedDirectory, realDirectory.getFileName());
        } catch (UnsupportedOperationException | SecurityException
                | java.io.IOException unavailable) {
            Assumptions.assumeTrue(false,
                "symbolic links unavailable for this test: " + unavailable);
        }
        Path output = realDirectory.resolve("future.png");
        Path manifest = linkedDirectory.resolve("future.png");

        assertEquals(2, Main.run(new String[] {
            "https://example.com", "-o", output.toString(),
            "--json", manifest.toString()}));
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(output));
    }

    @Test
    void jpegExtensionsMatchCaseInsensitively() {
        org.junit.jupiter.api.Assertions.assertTrue(
            Main.isJpegOutput(java.nio.file.Path.of("x.jpg")));
        org.junit.jupiter.api.Assertions.assertTrue(
            Main.isJpegOutput(java.nio.file.Path.of("x.JPEG")));
        org.junit.jupiter.api.Assertions.assertTrue(
            Main.isJpegOutput(java.nio.file.Path.of("x.JpEg")));
        org.junit.jupiter.api.Assertions.assertFalse(
            Main.isJpegOutput(java.nio.file.Path.of("x.png")));
        org.junit.jupiter.api.Assertions.assertFalse(
            Main.isJpegOutput(java.nio.file.Path.of("jpg.png")));
        org.junit.jupiter.api.Assertions.assertTrue(
            Main.isPngOutput(java.nio.file.Path.of("x.PNG")));
    }

    @Test
    void jpegQualityIsBoundedAndOnlyAcceptedForJpegStills() throws Exception {
        assertEquals(2, Main.run(new String[] {
            "--jpeg-quality", "0", "-o", "out.jpg", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {
            "--jpeg-quality", "101", "-o", "out.jpeg", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {
            "--jpeg-quality", "80", "-o", "out.png", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {
            "--jpeg-quality", "80", "-o", "out.pdf", "https://example.com"}));
        assertEquals(2, Main.run(new String[] {
            "--jpeg-quality", "80", "--gif", "2", "-o", "out.gif",
            "https://example.com"}));
    }

    @Test
    void unknownOutputAndMisnamedHeatmapExtensionsRefuseBeforeIo() throws Exception {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        PrintStream original = System.err;
        int webp;
        try {
            System.setErr(new PrintStream(errors));
            webp = Main.run(new String[] {
                "-o", "out.webp", "https://example.com"});
        } finally {
            System.setErr(original);
        }
        assertEquals(2, webp);
        org.junit.jupiter.api.Assertions.assertTrue(
            errors.toString().contains("unsupported output extension"), errors.toString());
        assertEquals(2, Main.run(new String[] {
            "diff", "a.png", "b.png", "--diff-out", "heat.bmp"}));
    }

    @Test
    void evalFileAndStdinCapsAreWiredIntoCli(@TempDir Path directory) throws Exception {
        Path oversizedEval = directory.resolve("large.js");
        Files.write(oversizedEval, new byte[Main.MAX_EVAL_FILE_BYTES + 1]);
        assertEquals(2, Main.run(new String[] {
            "--eval-file", oversizedEval.toString(), "https://example.com"}));

        byte[] oversizedHtml = new byte[Main.MAX_STDIN_HTML_BYTES + 1];
        InputStream original = System.in;
        try {
            System.setIn(new ByteArrayInputStream(oversizedHtml));
            assertEquals(2, Main.run(new String[] {"-"}));
        } finally {
            System.setIn(original);
        }
    }

    // ---- --size auto (plan 982dcab9): every case here exits before Chrome ----------

    private static final String SVG_NS = "xmlns=\"http://www.w3.org/2000/svg\"";

    /** Run the CLI capturing stderr; returns {exit code, stderr}. */
    private static Object[] runCapturingStderr(String... args) throws Exception {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        PrintStream original = System.err;
        int code;
        try {
            System.setErr(new PrintStream(errors));
            code = Main.run(args);
        } finally {
            System.setErr(original);
        }
        return new Object[] {code, errors.toString()};
    }

    @Test
    void helpDocumentsSizeAuto() throws Exception {
        Object[] result = runCapturingStderr("--help");
        String help = (String) result[1];
        assertEquals(0, result[0]);
        assertTrue(help.contains("[--size WxH|auto]") && help.contains("--size auto")
            && help.contains("viewBox") && help.contains("refuses (exit 2)"), help);
    }

    @Test
    void svgInputExtensionMatchesCaseInsensitively() {
        assertTrue(Main.isSvgInput("diagram.svg"));
        assertTrue(Main.isSvgInput("/tmp/Diagram.SVG"));
        org.junit.jupiter.api.Assertions.assertFalse(Main.isSvgInput("diagram.svgz"));
        org.junit.jupiter.api.Assertions.assertFalse(Main.isSvgInput("svg"));
        org.junit.jupiter.api.Assertions.assertFalse(Main.isSvgInput("page.html"));
    }

    @Test
    void autoViewportComesFromTheSvgAndNamesItsSource(@TempDir Path directory)
            throws Exception {
        Path sized = directory.resolve("sized.svg");
        Files.writeString(sized, "<svg " + SVG_NS + " width=\"1000\" height=\"780\"/>");
        Path boxed = directory.resolve("BOXED.SVG");
        Files.writeString(boxed, "<svg " + SVG_NS + " viewBox=\"0 0 640 360\"/>");

        // Absent --size and an explicit --size auto resolve a sized SVG identically.
        for (boolean explicitAuto : new boolean[] {false, true}) {
            Main.Viewport fromAttributes =
                Main.resolveAutoViewport(explicitAuto, "file", sized.toString());
            assertEquals(1000, fromAttributes.width());
            assertEquals(780, fromAttributes.height());
            assertEquals("svg-width-height", fromAttributes.source());
            assertTrue(fromAttributes.notice().contains("viewport 1000x780")
                && fromAttributes.notice().contains(sized.toString()), fromAttributes.notice());

            Main.Viewport fromViewBox =
                Main.resolveAutoViewport(explicitAuto, "file", boxed.toString());
            assertEquals(640, fromViewBox.width());
            assertEquals(360, fromViewBox.height());
            assertEquals("svg-viewbox", fromViewBox.source());
        }
    }

    @Test
    void nonSvgInputsKeepTheDefaultViewportUntouched(@TempDir Path directory)
            throws Exception {
        Path page = directory.resolve("page.html");
        // SVG-shaped CONTENT under a non-.svg name is still a page: the extension decides.
        Files.writeString(page, "<svg " + SVG_NS + " width=\"10\" height=\"10\"/>");
        org.junit.jupiter.api.Assertions.assertNull(
            Main.resolveAutoViewport(false, "file", page.toString()));
        org.junit.jupiter.api.Assertions.assertNull(
            Main.resolveAutoViewport(false, "url", "https://example.com/logo.svg"));
        org.junit.jupiter.api.Assertions.assertNull(
            Main.resolveAutoViewport(false, "stdin", "-"));
    }

    @Test
    void undeterminedSvgFallsBackLoudlyWithoutSizeButRefusesUnderSizeAuto(
            @TempDir Path directory) throws Exception {
        Path bare = directory.resolve("bare.svg");
        Files.writeString(bare, "<svg " + SVG_NS + "><rect width=\"5\" height=\"5\"/></svg>");
        Path relative = directory.resolve("relative.svg");
        Files.writeString(relative,
            "<svg " + SVG_NS + " width=\"50em\" height=\"30em\" viewBox=\"0 0 5 3\"/>");

        // No --size at all: the plan's fallback, never silent.
        Main.Viewport fallback = Main.resolveAutoViewport(false, "file", bare.toString());
        assertEquals(1280, fallback.width());
        assertEquals(900, fallback.height());
        assertEquals("default-fallback", fallback.source());
        assertTrue(fallback.notice().contains("SVG size not determined")
            && fallback.notice().contains("no absolute width or height")
            && fallback.notice().contains("using the default 1280x900 viewport")
            && fallback.notice().contains("--size WxH"), fallback.notice());
        assertTrue(Main.resolveAutoViewport(false, "file", relative.toString())
            .notice().contains("unit \"em\""));

        // --size auto: the caller asked for a size that cannot be delivered. Exit 2, named,
        // before Chrome and before any artifact.
        Path output = directory.resolve("never-written.png");
        Path receipt = directory.resolve("never-written.json");
        for (String[] args : java.util.List.of(
                new String[] {bare.toString(), "--size", "auto", "-o", output.toString(),
                    "--json", receipt.toString()},
                new String[] {"--size=auto", relative.toString(), "-o", output.toString(),
                    "--json", receipt.toString()})) {
            Object[] result = runCapturingStderr(args);
            String errors = (String) result[1];
            assertEquals(2, result[0], errors);
            assertTrue(errors.contains("--size auto cannot determine the size of")
                && errors.contains("pass --size WxH instead"), errors);
            org.junit.jupiter.api.Assertions.assertFalse(Files.exists(output));
            org.junit.jupiter.api.Assertions.assertFalse(Files.exists(receipt));
        }
    }

    @Test
    void sizeAutoRefusesInputsThatAreNotALocalSvgBeforeStdinOrChrome(
            @TempDir Path directory) throws Exception {
        Path page = directory.resolve("page.html");
        Files.writeString(page, "<p>page</p>");
        Path output = directory.resolve("never-written.png");

        Object[] file = runCapturingStderr(
            page.toString(), "--size", "auto", "-o", output.toString());
        assertEquals(2, file[0], (String) file[1]);
        assertTrue(((String) file[1]).contains("--size auto reads the size from a local .svg file")
            && ((String) file[1]).contains("not an .svg file"), (String) file[1]);

        Object[] url = runCapturingStderr(
            "https://example.com/logo.svg", "--size", "auto", "-o", output.toString());
        assertEquals(2, url[0], (String) url[1]);
        assertTrue(((String) url[1]).contains("the input is a URL"), (String) url[1]);

        // stdin must be refused WITHOUT being read: a stream that fails on read proves it.
        InputStream originalIn = System.in;
        Object[] stdin;
        try {
            System.setIn(new InputStream() {
                @Override
                public int read() {
                    throw new AssertionError("--size auto must refuse before reading stdin");
                }
            });
            stdin = runCapturingStderr("-", "--size", "auto", "-o", output.toString());
        } finally {
            System.setIn(originalIn);
        }
        assertEquals(2, stdin[0], (String) stdin[1]);
        assertTrue(((String) stdin[1]).contains("the input is stdin"), (String) stdin[1]);
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(output));
    }

    @Test
    void sizeSpellingsOtherThanWxHOrAutoStillRefuse() throws Exception {
        for (String bad : new String[] {"AUTO", "Auto", "autox", "auto,auto", "1000"}) {
            Object[] result = runCapturingStderr("--size", bad, "https://example.com");
            assertEquals(2, result[0], bad + ": " + result[1]);
        }
    }
}
