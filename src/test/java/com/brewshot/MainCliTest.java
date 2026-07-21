package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** CLI arg handling — no Chrome needed: every case exits before launch. */
class MainCliTest {

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
}
