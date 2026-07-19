package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

/**
 * The loud-skip helper: when no browser is available it must SCREAM a banner to
 * System.err AND record a JUnit skip (TestAbortedException), so a browser-less
 * CI run is a visible non-run, not a silent green.
 */
class TestChromeTest {

    @Test
    void unavailableChromeEmitsBannerToStderrAndAbortsAsSkip() {
        PrintStream realErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            // drive the forced-false path so this asserts regardless of whether
            // THIS host actually has a browser installed.
            assertThrows(TestAbortedException.class,
                () -> TestChrome.requireChromeOrLoudSkip("MyDemoSuite", false));
        } finally {
            System.setErr(realErr);
        }
        String banner = captured.toString(StandardCharsets.UTF_8);
        assertTrue(banner.contains("SKIPPED MyDemoSuite"),
            "banner must name the skipped suite; got: " + banner);
        assertTrue(banner.contains("NO BROWSER FOUND"), banner);
        assertTrue(banner.contains("BREWSHOT_CHROME"),
            "banner must show how to point BrewShot at a browser; got: " + banner);
    }

    @Test
    void availableChromeReturnsQuietlyWithoutBanner() {
        PrintStream realErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            // forced-true: no skip, no banner.
            assertDoesNotThrow(() -> TestChrome.requireChromeOrLoudSkip("MyDemoSuite", true));
        } finally {
            System.setErr(realErr);
        }
        assertTrue(captured.toString(StandardCharsets.UTF_8).isEmpty(),
            "available Chrome must not print a skip banner");
    }
}
