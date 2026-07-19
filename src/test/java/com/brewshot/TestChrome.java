package com.brewshot;

import org.junit.jupiter.api.Assumptions;

/**
 * Chrome-gating for the browser tests that SCREAMS instead of silently passing.
 *
 * <p>The old pattern — {@code assumeTrue(BrewShot.available(), "no local Chrome")}
 * — turns a browser-less CI run GREEN while testing nothing: every gated test
 * quietly assume-skips and the suite reports success. That is a silent-coverage
 * hole. {@link #requireChromeOrLoudSkip} still records a JUnit skip (so a
 * genuinely browser-less dev box isn't a hard failure), but first prints a loud,
 * unmissable multi-line banner to {@code System.err} naming the suite that was
 * skipped and how to point BrewShot at a browser — so a "green" CI run with no
 * browser is visibly, greppably a NON-run.
 */
final class TestChrome {

    private TestChrome() { }

    /**
     * Gate a browser-dependent suite: if no Chrome/Chromium/Edge is found, emit
     * a loud SKIPPED banner and abort the test as a JUnit skip. Otherwise return.
     */
    static void requireChromeOrLoudSkip(String suiteName) {
        requireChromeOrLoudSkip(suiteName, BrewShot.available());
    }

    /**
     * Testable seam: {@code chromeAvailable} is injected so the banner path can
     * be driven under test even when this host DOES have a browser installed.
     */
    static void requireChromeOrLoudSkip(String suiteName, boolean chromeAvailable) {
        if (chromeAvailable) { return; }
        String banner = """

            ============================================================
            SKIPPED %s — NO BROWSER FOUND
            ------------------------------------------------------------
            BrewShot could not locate a Chrome / Chromium / Edge binary
            on PATH or at any known install location, so this browser-
            dependent suite did NOT run. A green build here tested NOTHING.

            To run it, install Chrome/Chromium/Edge, or point BrewShot at
            an existing binary:

                BREWSHOT_CHROME=/path/to/chrome ./gradlew test
            ============================================================
            """.formatted(suiteName);
        System.err.println(banner);
        Assumptions.abort("SKIPPED " + suiteName + " — no browser found");
    }
}
