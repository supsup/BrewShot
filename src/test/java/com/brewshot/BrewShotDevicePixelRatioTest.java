package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** True page DPR is distinct from output scale and witnessed after every navigation. */
class BrewShotDevicePixelRatioTest {

    private static final String PAGE = """
        <!doctype html><style>html,body{margin:0}body{width:200px;height:100px}</style>
        <div id="probe">density</div>
        """;

    private static final String DPR_AND_QUERY =
        "window.devicePixelRatio+'|'+matchMedia('(min-resolution: 2dppx)').matches";

    @Test
    void outputScaleNegativeControlFiresBeforeTrueDprAndThePixelsCompose() throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotDevicePixelRatioTest");

        // MUST-FIRE negative control first: the established output scale makes a larger raster,
        // but it does not change the page environment or a resolution media query.
        try (BrewShot shot = BrewShot.launch(200, 100)) {
            shot.html(PAGE);
            assertEquals("1|false", shot.eval(DPR_AND_QUERY));
            assertDimensions(shot.screenshotClip(0, 0, 12, 8, 1.0), 12, 8);
            assertDimensions(shot.screenshotClip(0, 0, 12, 8, 2.0), 24, 16);
            assertEquals("1|false", shot.eval(DPR_AND_QUERY),
                "--scale's library seam must not mutate page-visible DPR");
        }

        // Positive twin: DPR changes the page's observable environment and bitmap density while
        // preserving CSS viewport dimensions. DPR 2 x output scale 1.5 is exactly 3x.
        try (BrewShot shot = BrewShot.launch(200, 100, 2)) {
            shot.html(PAGE);
            assertEquals("2|true|200",
                shot.eval(DPR_AND_QUERY + "+'|'+window.innerWidth"));
            assertEquals(Integer.valueOf(2), shot.appliedDevicePixelRatio());
            assertDimensions(shot.screenshotClip(0, 0, 12, 8, 1.0), 24, 16);
            assertDimensions(shot.screenshotClip(0, 0, 12, 8, 1.5), 36, 24);
        }
    }

    @Test
    void oneTargetOverridePersistsAcrossHtmlAndOpenWithoutASecondApplication(
            @TempDir Path directory) throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotDevicePixelRatioTest");
        Path second = directory.resolve("second.html");
        Files.writeString(second, PAGE.replace("density", "second"));

        try (BrewShot shot = BrewShot.launch(200, 100, 2)) {
            assertEquals(1, shot.deviceMetricsApplicationCountForTests());
            shot.html(PAGE);
            assertEquals(Integer.valueOf(2), shot.appliedDevicePixelRatio());
            assertEquals(1, shot.deviceMetricsApplicationCountForTests(),
                "first navigation must witness the original override, not reapply it");

            shot.open(second.toUri().toString());
            assertEquals(Integer.valueOf(2), shot.appliedDevicePixelRatio());
            assertEquals("2|true", shot.eval(DPR_AND_QUERY));
            assertEquals(1, shot.deviceMetricsApplicationCountForTests(),
                "second navigation deliberately skips reapplication and proves persistence");
        }
    }

    @Test
    void explicitDprOwnsThePropertyAndInvalidValuesRefuseBeforeLaunch() {
        List<String> legacy = BrewShot.chromeLaunchArguments(
            "chrome", Path.of("profile"), 200, 100, null);
        List<String> explicit = BrewShot.chromeLaunchArguments(
            "chrome", Path.of("profile"), 200, 100, 2);
        assertTrue(legacy.contains("--force-device-scale-factor=1"));
        assertFalse(explicit.stream().anyMatch(
            arg -> arg.startsWith("--force-device-scale-factor")),
            "an explicit target override must not compete with a browser-level force flag");

        for (int rejected : new int[] {0, -1, 5, Integer.MAX_VALUE}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> BrewShot.launch(200, 100, rejected));
            assertTrue(failure.getMessage().contains("device pixel ratio"));
        }
    }

    private static void assertDimensions(byte[] png, int width, int height) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(width, image.getWidth());
        assertEquals(height, image.getHeight());
    }
}
