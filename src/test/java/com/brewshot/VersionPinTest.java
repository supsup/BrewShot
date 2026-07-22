package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Version drift-guard: {@link BrewShot#VERSION} (what {@code --version} and the JSON
 * manifest report) and {@code build.gradle.kts}'s {@code version} (what names the jar)
 * are two declarations of the same fact with no compiler between them — the 0.9.0 bump
 * caught them drifted (VERSION bumped, jar still 0.8.0-named). A release bump must
 * touch both or fail here.
 */
class VersionPinTest {

    @Test
    void buildGradleVersionMatchesBrewShotVersion() throws Exception {
        String gradle = Files.readString(Path.of("build.gradle.kts"));
        assertTrue(gradle.contains("version = \"" + BrewShot.VERSION + "\""),
            "build.gradle.kts version must equal BrewShot.VERSION (" + BrewShot.VERSION
                + ") — bump both sites in the same commit");
    }
}
