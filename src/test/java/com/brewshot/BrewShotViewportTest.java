package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The CSS viewport is what launch asked for, on every launch, after every navigation (plan
 * 3f4bc3c7). Before this contract a default 640x360 launch on Chrome 155 painted a 640x273 page:
 * new headless sizes the OUTER window from {@code --window-size} and the page lost the toolbar
 * band; only the {@code --dpr} path, which already sent the device-metrics override, was exact.
 */
class BrewShotViewportTest {

    private static final String PAGE = """
        <!doctype html><style>html,body{margin:0}body{width:200px;height:100px}</style>
        <div id="probe">viewport</div>
        """;

    private static final String INNER = "window.innerWidth+'x'+window.innerHeight";

    @Test
    void defaultLaunchGetsTheExactViewportAndWitnessesItAfterEveryNavigation(
            @TempDir Path directory) throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotViewportTest");
        Path second = directory.resolve("second.html");
        Files.writeString(second, PAGE.replace("viewport", "second"));

        // 640x360 is the shape that was 87 px short; a default launch keeps DPR with the
        // browser-level force-1 flag, so the DPR witness stays off (null) while the viewport
        // witness is on.
        try (BrewShot shot = BrewShot.launch(640, 360)) {
            assertNull(shot.appliedViewport(), "nothing is witnessed before a navigation");
            assertEquals(1, shot.deviceMetricsApplicationCountForTests(),
                "the viewport is pinned once, at launch");
            shot.html(PAGE);
            assertEquals("640x360", shot.eval(INNER));
            assertEquals("640x360", shot.appliedViewport());
            assertEquals(1.0, ((Number) shot.eval("window.devicePixelRatio")).doubleValue(),
                "DPR stays with the browser-level force-1 flag on a default launch");
            assertNull(shot.appliedDevicePixelRatio(),
                "a default launch asked for no DPR, so none is witnessed");

            shot.open(second.toUri().toString());
            assertEquals("640x360", shot.eval(INNER));
            assertEquals("640x360", shot.appliedViewport());
            assertEquals(1, shot.deviceMetricsApplicationCountForTests(),
                "the second navigation witnesses persistence, it does not reapply");
        }
    }

    @Test
    void explicitDprLaunchKeepsTheExactViewportToo() throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotViewportTest");
        try (BrewShot shot = BrewShot.launch(640, 360, 2)) {
            shot.html(PAGE);
            assertEquals("640x360", shot.eval(INNER));
            assertEquals("640x360", shot.appliedViewport());
            assertEquals(Integer.valueOf(2), shot.appliedDevicePixelRatio());
            assertEquals(1, shot.deviceMetricsApplicationCountForTests(),
                "DPR and viewport ride one override, applied once");
        }
    }

    @Test
    void aPageThatRedefinesItsInnerSizeIsRefusedBeforeCapture(@TempDir Path directory)
            throws Exception {
        TestChrome.requireChromeOrLoudSkip("BrewShotViewportTest");
        Path honest = directory.resolve("honest.html");
        Files.writeString(honest, PAGE.replace("viewport", "honest"));
        // Review 696 (Confluence): a mutant whose witness never refuses survived the first
        // suite. The refusal needs no fake: a page can redefine window.innerWidth itself, and
        // then it is the page, not the override, that is refused. That also documents the
        // limit: a page can refuse its own capture.
        String lying = "<!doctype html><script>"
            + "Object.defineProperty(window,'innerWidth',{get:function(){return 1;}});"
            + "</script><body>lying</body>";
        try (BrewShot shot = BrewShot.launch(640, 360)) {
            IllegalStateException refusal =
                assertThrows(IllegalStateException.class, () -> shot.html(lying));
            assertTrue(refusal.getMessage().contains("viewport override mismatch"),
                refusal.getMessage());
            assertTrue(refusal.getMessage().contains("requested 640x360, page reported 1x360"),
                refusal.getMessage());
            assertNull(shot.appliedViewport(),
                "null-before-throw: no stale success is observable after a refusal");

            // The override is intact: a real navigation (a new window object, so the lie is
            // gone) to an honest page is witnessed again. html() would NOT do: it replaces the
            // document but keeps the window, lie included, so the refusal stands there too.
            shot.open(honest.toUri().toString());
            assertEquals("640x360", shot.appliedViewport());
        }
    }

    @Test
    void theOuterWindowFlagIsStillPassedForBothLaunchShapes() {
        // --window-size stays: it is the outer window, harmless and useful for the headed
        // profile; the viewport contract lives in the CDP override, not in this flag.
        for (Integer dpr : new Integer[] {null, 2}) {
            List<String> args = BrewShot.chromeLaunchArguments(
                "chrome", Path.of("profile"), 640, 360, dpr);
            assertTrue(args.contains("--window-size=640,360"), String.valueOf(args));
        }
    }
}
