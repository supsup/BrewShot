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
     * Gate a browser-dependent suite: if no Chrome/Chromium/Edge is found — or
     * the operator set {@code BREWSHOT_FORBID_CHROME} to forbid launching the
     * one that IS installed — emit a loud SKIPPED banner and abort the test as
     * a JUnit skip. Otherwise return.
     */
    static void requireChromeOrLoudSkip(String suiteName) {
        requireChromeOrLoudSkip(suiteName, BrewShot.available(),
            isTruthy(System.getenv("BREWSHOT_FORBID_CHROME")));
    }

    /** Same truthiness the build's BREWSHOT_REQUIRE_CHROME guard uses. */
    private static boolean isTruthy(String v) {
        return v != null && (v.equals("1")
            || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes"));
    }

    /**
     * Testable seam: {@code chromeAvailable} is injected so the banner path can
     * be driven under test even when this host DOES have a browser installed.
     */
    static void requireChromeOrLoudSkip(String suiteName, boolean chromeAvailable) {
        requireChromeOrLoudSkip(suiteName, chromeAvailable, false);
    }

    /**
     * Full seam. {@code launchForbidden} is the operator kill switch
     * ({@code BREWSHOT_FORBID_CHROME=1}): a host can HAVE a browser that the
     * operator has forbidden the suite to launch (live example: a macOS
     * crash-dialog investigation where every extra Chrome launch pollutes the
     * evidence). Forbidden wins over available — the suite loud-skips exactly
     * like a browser-less host, so the non-run stays visible and greppable.
     * Under {@code BREWSHOT_REQUIRE_CHROME} the build-level no-skip guard still
     * fails such a run: forbid+require is a contradictory operator instruction
     * and the build refuses to report green on it.
     *
     * <p>NOTE the env var must reach the forked test JVM: Gradle forks tests
     * from the daemon, so either export it before the daemon starts or run
     * with {@code --no-daemon}.
     */
    static void requireChromeOrLoudSkip(String suiteName, boolean chromeAvailable,
                                        boolean launchForbidden) {
        if (launchForbidden) {
            String banner = """

                ============================================================
                SKIPPED %s — CHROME LAUNCH FORBIDDEN BY OPERATOR
                ------------------------------------------------------------
                BREWSHOT_FORBID_CHROME is set: this host may well have a
                browser, but the operator forbade launching it, so this
                browser-dependent suite did NOT run. A green build here
                tested NOTHING browser-side.

                Unset BREWSHOT_FORBID_CHROME to run it.
                ============================================================
                """.formatted(suiteName);
            System.err.println(banner);
            Assumptions.abort("SKIPPED " + suiteName
                + " — Chrome launch forbidden by operator (BREWSHOT_FORBID_CHROME)");
        }
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
