package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Page-timezone pinning, including the load-bearing proof that host TZ matters unforced. */
class BrewShotTimezoneTest {

    @Test
    void hostTimezoneDiffersUnforcedBeforeOneOverrideMakesBothPagesIdentical() throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotTimezoneTest");
        String expression =
            "Intl.DateTimeFormat().resolvedOptions().timeZone+'|'+new Date(0).getHours()";

        String utcNatural = pageTime(Map.of("TZ", "UTC"), null, expression);
        String tokyoNatural = pageTime(Map.of("TZ", "Asia/Tokyo"), null, expression);
        assertFalse(utcNatural.equals(tokyoNatural),
            "negative control must fire: distinct host TZ values change an unforced page");

        String utcPinned = pageTime(Map.of("TZ", "UTC"), "America/Los_Angeles", expression);
        String tokyoPinned =
            pageTime(Map.of("TZ", "Asia/Tokyo"), "America/Los_Angeles", expression);
        assertEquals(utcPinned, tokyoPinned,
            "one explicit override must erase the host-timezone difference");
        assertEquals("America/Los_Angeles|16", utcPinned);
    }

    @Test
    void twoZonesProduceDifferentLocalHoursAndOverrideSurvivesHtmlAndOpen() throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotTimezoneTest");
        assertEquals("9", pageTime(Map.of(), "Asia/Tokyo", "String(new Date(0).getHours())"));
        assertEquals("16", pageTime(
            Map.of(), "America/Los_Angeles", "String(new Date(0).getHours())"));

        Path page = Files.createTempFile("brewshot-timezone", ".html");
        Files.writeString(page, "<title>second navigation</title>");
        try (BrewShot shot = BrewShot.launch(200, 100)) {
            assertSame(shot, shot.timezone("Asia/Tokyo"));
            shot.html("<title>first navigation</title>");
            assertEquals("Asia/Tokyo", shot.appliedTimezone());
            shot.open(page.toUri().toString());
            assertEquals("Asia/Tokyo", shot.appliedTimezone());
            assertEquals("Asia/Tokyo",
                shot.eval("Intl.DateTimeFormat().resolvedOptions().timeZone"));
        }
    }

    @Test
    void unsupportedIdentifierNamesTheRejectedValueAndWritesNoCliArtifacts() throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotTimezoneTest");
        try (BrewShot shot = BrewShot.launch(100, 100)) {
            IllegalStateException rejected = assertThrows(IllegalStateException.class,
                () -> shot.timezone("Mars/Olympus_Mons"));
            assertTrue(rejected.getMessage().contains("Mars/Olympus_Mons"));
            assertTrue(rejected.getMessage().contains("Emulation.setTimezoneOverride"));
        }

        Path directory = Files.createTempDirectory("brewshot-bad-timezone");
        Path page = directory.resolve("page.html");
        Path output = directory.resolve("shot.png");
        Path receipt = directory.resolve("shot.json");
        Files.writeString(page, "<p>never captured</p>");
        assertThrows(IllegalStateException.class, () -> Main.run(new String[] {
            page.toString(), "-o", output.toString(), "--json", receipt.toString(),
            "--timezone", "Mars/Olympus_Mons"
        }));
        assertFalse(Files.exists(output));
        assertFalse(Files.exists(receipt));
    }

    private static String pageTime(Map<String, String> environment, String timezone,
                                   String expression) throws Exception {
        try (BrewShot shot = BrewShot.launch(200, 100, environment)) {
            if (timezone != null) { shot.timezone(timezone); }
            shot.html("<p>clock</p>");
            return String.valueOf(shot.eval(expression));
        }
    }
}
