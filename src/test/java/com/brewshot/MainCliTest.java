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
    void trailingFlagWantsAValueNotAStackTrace() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> Main.run(new String[] {"page.html", "-o"}));
    }
}
