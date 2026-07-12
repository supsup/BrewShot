package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pure discovery tests over the injected-env seam {@code findChrome(env, windows)}
 * — no real browser needed. Covers the BREWSHOT_CHROME precedence and the new
 * PATH scan.
 */
class BrewShotDiscoveryTest {

    /** Create an executable file named {@code name} in {@code dir} (mkdir -p). */
    private static Path fakeBrowser(Path dir, String name) throws Exception {
        Files.createDirectories(dir);
        Path bin = dir.resolve(name);
        Files.writeString(bin, "#!/bin/sh\n");
        assertTrue(bin.toFile().setExecutable(true), "could not chmod +x " + bin);
        return bin;
    }

    @Test
    void brewshotChromeEnvTakesPrecedenceOverPath(@TempDir Path tmp) throws Exception {
        Path override = fakeBrowser(tmp.resolve("override-dir"), "my-chrome");
        Path onPath = fakeBrowser(tmp.resolve("path-dir"), "chromium");

        Map<String, String> env = new HashMap<>();
        env.put("BREWSHOT_CHROME", override.toString());
        env.put("PATH", onPath.getParent().toString());

        // the env override wins even though a valid PATH match also exists
        assertEquals(override.toString(), BrewShot.findChrome(env, false));
    }

    @Test
    void brewshotChromeEnvIgnoredWhenNotExecutableFallsToPath(@TempDir Path tmp) throws Exception {
        Path onPath = fakeBrowser(tmp, "google-chrome");
        Map<String, String> env = new HashMap<>();
        env.put("BREWSHOT_CHROME", tmp.resolve("does-not-exist").toString());
        env.put("PATH", tmp.toString());
        assertEquals(onPath.toString(), BrewShot.findChrome(env, false));
    }

    @Test
    void pathScanFindsABrowserOnASyntheticPath(@TempDir Path tmp) throws Exception {
        Path bin = fakeBrowser(tmp, "chromium-browser");
        // a realistic multi-entry PATH with the match in the middle
        String path = "/nowhere/one" + File.pathSeparator + tmp
            + File.pathSeparator + "/nowhere/two";
        Map<String, String> env = new HashMap<>();
        env.put("PATH", path);
        assertEquals(bin.toString(), BrewShot.findChrome(env, false));
        // and the pure splitter agrees
        assertEquals(bin.toString(), BrewShot.scanPath(path, false));
    }

    @Test
    void pathScanTriesEachKnownNameAndPrefersEarlierEntries(@TempDir Path tmp) throws Exception {
        // "google-chrome" precedes "msedge" in PATH_NAMES; both present in the
        // SAME dir means the earlier-preference name is returned.
        Path early = fakeBrowser(tmp, "google-chrome");
        fakeBrowser(tmp, "msedge");
        assertEquals(early.toString(), BrewShot.scanPath(tmp.toString(), false));
    }

    @Test
    void pathScanReturnsNullWhenNothingMatches(@TempDir Path tmp) throws Exception {
        fakeBrowser(tmp, "not-a-browser");
        assertNull(BrewShot.scanPath(tmp.toString(), false));
        assertNull(BrewShot.scanPath(null, false));
        assertNull(BrewShot.scanPath("", false));
    }

    @Test
    void windowsFlagEnablesTheExeSuffixProbe(@TempDir Path tmp) throws Exception {
        Path exe = fakeBrowser(tmp, "chrome.exe");
        // non-windows: bare "chrome" (no .exe) is looked for -> no match
        assertNull(BrewShot.scanPath(tmp.toString(), false));
        // windows: the .exe suffix is tried -> match
        assertEquals(exe.toString(), BrewShot.scanPath(tmp.toString(), true));
    }

    @Test
    void emptyPathYieldsNoPathMatch() {
        Map<String, String> env = new HashMap<>();
        // pure PATH/ENV path returns null on an empty env; known absolute
        // locations are environment-dependent so are intentionally not asserted.
        assertNull(BrewShot.scanPath(env.get("PATH"), false));
    }
}
