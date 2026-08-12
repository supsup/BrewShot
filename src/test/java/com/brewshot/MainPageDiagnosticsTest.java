package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainPageDiagnosticsTest {

    @Test
    void everyDiagnosticsFlagRequiresAnExplicitJsonPathBeforeInputOrChrome(
            @TempDir Path directory) throws Exception {
        for (String flag : List.of(
                "--page-diagnostics", "--fail-page-errors", "--fail-console-errors")) {
            Path output = directory.resolve(flag.substring(2) + ".png");
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            PrintStream original = System.err;
            int code;
            try {
                System.setErr(new PrintStream(errors));
                code = Main.run(new String[] {
                    flag, "-o", output.toString(), directory.resolve("missing.html").toString()});
            } finally {
                System.setErr(original);
            }
            assertEquals(2, code, flag);
            assertTrue(errors.toString().contains("require an explicit --json PATH"),
                errors.toString());
            assertFalse(Files.exists(output), "argument refusal must happen before artifact write");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void receiptSeparatesUncaughtAndConsoleErrorGatesWithoutInventingAnotherLog() {
        Main.PageDiagnosticsReceipt pageGate = new Main.PageDiagnosticsReceipt(
            List.of("log: first", "error: benign-third-party"), 0,
            List.of("console.error: benign-third-party"), 0,
            false, true, false);
        Map<String, Object> pageJson = pageGate.toJson();
        Map<String, Object> pageGateJson = (Map<String, Object>) pageJson.get("gate");

        assertEquals("passed", pageGateJson.get("pageErrors"));
        assertEquals("not-requested", pageGateJson.get("consoleErrors"));
        assertEquals("passed", pageGateJson.get("outcome"));
        assertEquals(0, pageGate.exitCode(),
            "console.error is deliberately not an uncaught-page-exception failure");
        assertFalse(pageJson.containsKey("console"), "gate-only mode must not disclose raw text");
        assertFalse(pageJson.containsKey("errors"), "gate-only mode must not disclose raw text");

        Main.PageDiagnosticsReceipt consoleGate = new Main.PageDiagnosticsReceipt(
            pageGate.console(), 0, pageGate.errors(), 0,
            false, false, true);
        Map<String, Object> consoleGateJson =
            (Map<String, Object>) consoleGate.toJson().get("gate");
        assertEquals("failed", consoleGateJson.get("consoleErrors"));
        assertEquals(List.of("console-errors"), consoleGateJson.get("tripped"));
        assertEquals(4, consoleGate.exitCode());

        Main.PageDiagnosticsReceipt uncaught = new Main.PageDiagnosticsReceipt(
            List.of(), 0, List.of("uncaught: Error: kaput"), 0,
            true, true, false);
        Map<String, Object> uncaughtJson = uncaught.toJson();
        assertEquals(List.of("uncaught: Error: kaput"), uncaughtJson.get("errors"));
        assertEquals(1L, uncaughtJson.get("pageErrorCount"));
        assertEquals(4, uncaught.exitCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void droppedRelevantEvidenceIsInconclusiveNeverClean() {
        Main.PageDiagnosticsReceipt gated = new Main.PageDiagnosticsReceipt(
            List.of("log: retained"), 0,
            List.of("console.error: retained but no uncaught exception"), 1,
            false, true, false);
        Map<String, Object> json = gated.toJson();
        Map<String, Object> gate = (Map<String, Object>) json.get("gate");

        assertEquals("inconclusive", gate.get("pageErrors"));
        assertEquals("inconclusive", gate.get("outcome"));
        assertEquals(List.of("page-errors"), gate.get("inconclusive"));
        assertEquals(5, gated.exitCode());
        assertEquals(Boolean.FALSE, json.get("errorsComplete"));

        Main.PageDiagnosticsReceipt raw = new Main.PageDiagnosticsReceipt(
            List.of("partial..."), 1, List.of(), 0,
            true, false, false);
        assertEquals("inconclusive", raw.outcome());
        assertEquals(5, raw.exitCode(), "an explicitly requested raw capture must not hide drops");
    }

    @Test
    void deterministicReceiptPreservesArrayOrderAndJsonBytesAcrossRepeatedSnapshots() {
        Main.PageDiagnosticsReceipt first = new Main.PageDiagnosticsReceipt(
            List.of("log: first", "warning: second", "log: third"), 2,
            List.of("console.error: fourth", "uncaught: Error: fifth"), 1,
            true, true, true);
        Main.PageDiagnosticsReceipt repeated = new Main.PageDiagnosticsReceipt(
            List.of("log: first", "warning: second", "log: third"), 2,
            List.of("console.error: fourth", "uncaught: Error: fifth"), 1,
            true, true, true);

        assertEquals(first.console(), repeated.console());
        assertEquals(first.errors(), repeated.errors());
        assertEquals(
            MiniJson.stringifyPretty(first.toJson()),
            MiniJson.stringifyPretty(repeated.toJson()),
            "the same bounded per-stream fixture must emit byte-stable diagnostic fields");
    }

    @Test
    @SuppressWarnings("unchecked")
    void realChromePublishesPixelsAndPrivateExplanationBeforeGateExit(
            @TempDir Path directory) throws Exception {
        TestChrome.requireChromeOrLoudSkip("MainPageDiagnosticsTest");
        Path consolePage = directory.resolve("console.html");
        Files.writeString(consolePage, """
            <!doctype html><p>console only</p><script>
              console.log('first');
              console.error('third-party-noise');
              console.log('last');
            </script>
            """);

        Path pageOnlyPng = directory.resolve("page-only.png");
        Path pageOnlyJson = directory.resolve("page-only.json");
        assertEquals(0, Main.run(new String[] {
            consolePage.toString(), "-o", pageOnlyPng.toString(),
            "--json", pageOnlyJson.toString(), "--fail-page-errors", "--settle", "25"}));
        Map<String, Object> pageOnly = parsedObject(pageOnlyJson);
        Map<String, Object> pageOnlyDiagnostics =
            (Map<String, Object>) pageOnly.get("pageDiagnostics");
        assertFalse(pageOnlyDiagnostics.containsKey("console"),
            "a gate alone must not opt into raw page text");

        Path consolePng = directory.resolve("console-gate.png");
        Path consoleJson = directory.resolve("console-gate.json");
        assertEquals(4, Main.run(new String[] {
            consolePage.toString(), "-o", consolePng.toString(),
            "--json", consoleJson.toString(), "--page-diagnostics",
            "--fail-console-errors", "--settle", "25"}));
        assertTrue(Files.size(consolePng) > 200, "gate failure still carries pixels");
        Map<String, Object> consoleReceipt = parsedObject(consoleJson);
        Map<String, Object> consoleDiagnostics =
            (Map<String, Object>) consoleReceipt.get("pageDiagnostics");
        assertEquals(List.of("log: first", "error: third-party-noise", "log: last"),
            consoleDiagnostics.get("console"));
        assertEquals(List.of("console.error: third-party-noise"),
            consoleDiagnostics.get("errors"));
        Map<String, Object> consoleGate =
            (Map<String, Object>) consoleDiagnostics.get("gate");
        assertEquals(List.of("console-errors"), consoleGate.get("tripped"));

        // Crash-window discriminator: discard the process result and use only surviving files.
        Path pageError = directory.resolve("page-error.html");
        Files.writeString(pageError, """
            <!doctype html><p>exception</p><script>
              setTimeout(function () { throw new Error('kaput-after-paint'); }, 0);
            </script>
            """);
        Path errorPng = directory.resolve("error.png");
        Path errorJson = directory.resolve("error.json");
        Main.run(new String[] {
            pageError.toString(), "-o", errorPng.toString(),
            "--json", errorJson.toString(), "--page-diagnostics",
            "--fail-page-errors", "--settle", "100"});
        assertTrue(Files.size(errorPng) > 200);
        Map<String, Object> crashWindowReceipt = parsedObject(errorJson);
        Map<String, Object> crashDiagnostics =
            (Map<String, Object>) crashWindowReceipt.get("pageDiagnostics");
        assertTrue(((List<String>) crashDiagnostics.get("errors")).stream()
            .anyMatch(value -> value.contains("kaput-after-paint")), crashDiagnostics.toString());
        assertEquals(List.of("page-errors"),
            ((Map<String, Object>) crashDiagnostics.get("gate")).get("tripped"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void forcedBoundOverflowPublishesTypedInconclusiveReceipt(
            @TempDir Path directory) throws Exception {
        TestChrome.requireChromeOrLoudSkip("MainPageDiagnosticsTest");
        Path page = directory.resolve("overflow.html");
        Files.writeString(page, """
            <!doctype html><p>overflow</p><script>
              console.log('abcdefghijklmnopqrstuvwxyz-abcdefghijklmnopqrstuvwxyz');
            </script>
            """);
        Path png = directory.resolve("overflow.png");
        Path json = directory.resolve("overflow.json");
        String previous = System.getProperty("brewshot.maxConsoleBytes");
        try {
            System.setProperty("brewshot.maxConsoleBytes", "8");
            assertEquals(5, Main.run(new String[] {
                page.toString(), "-o", png.toString(), "--json", json.toString(),
                "--page-diagnostics", "--settle", "25"}));
        } finally {
            if (previous == null) System.clearProperty("brewshot.maxConsoleBytes");
            else System.setProperty("brewshot.maxConsoleBytes", previous);
        }
        assertTrue(Files.size(png) > 200);
        Map<String, Object> diagnostics =
            (Map<String, Object>) parsedObject(json).get("pageDiagnostics");
        assertEquals(1.0, diagnostics.get("consoleDropped"));
        assertEquals(Boolean.FALSE, diagnostics.get("complete"));
        assertEquals("inconclusive",
            ((Map<String, Object>) diagnostics.get("gate")).get("outcome"));
    }

    @Test
    void manifestWriteFailureWinsOverAnObservedPageGate(
            @TempDir Path directory) throws Exception {
        TestChrome.requireChromeOrLoudSkip("MainPageDiagnosticsTest");
        Path page = directory.resolve("write-failure.html");
        Files.writeString(page, """
            <!doctype html><p>write failure</p><script>
              setTimeout(function () { throw new Error('must-not-become-exit-four'); }, 0);
            </script>
            """);
        Path png = directory.resolve("write-failure.png");
        Path json = directory.resolve("missing-parent").resolve("write-failure.json");

        assertThrows(java.io.IOException.class, () -> Main.run(new String[] {
            page.toString(), "-o", png.toString(), "--json", json.toString(),
            "--page-diagnostics", "--fail-page-errors", "--settle", "100"}));

        assertTrue(Files.size(png) > 200,
            "the screenshot still precedes the manifest failure");
        assertFalse(Files.exists(json));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parsedObject(Path path) throws Exception {
        return (Map<String, Object>) MiniJson.parse(Files.readString(path));
    }
}
