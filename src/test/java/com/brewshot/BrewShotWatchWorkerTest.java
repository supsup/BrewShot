package com.brewshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BrewShotWatchWorkerTest {
    @TempDir Path scratch;

    @Test void mainNormalizationCannotPromoteAManifestValueIntoArguments() throws Exception {
        assertEquals(List.of("https://example.test/", "--gif=4",
                "--gif-element=--out=/tmp/foreign", "--scale=0.5"),
            BrewShotWatchCapture.literalArguments(List.of("https://example.test/",
                "--gif", "4", "--gif-element", "--out=/tmp/foreign", "--scale", "0.5")));
        boolean[] launcherReached = { false };
        Main.sessionLauncherForTests = (width, height, dpr) -> {
            launcherReached[0] = true;
            throw new java.io.IOException("scripted stop before browser launch");
        };
        try {
            var cli = BrewShotWatchCapture.literalArguments(List.of("https://example.test/",
                "--clip-selector", "--out=/a-nonexistent-manifest-value"));
            cli.add("-o"); cli.add(scratch.resolve("literal.png").toString());
            assertThrows(java.io.IOException.class, () -> Main.run(cli.toArray(String[]::new)));
            assertTrue(launcherReached[0], "a value must not replace the positional URL");
            assertFalse(Files.exists(scratch.resolve("literal.png")));
        } finally { Main.sessionLauncherForTests = null; }
    }

    @Test void manifestNumericOptionsAreRejectedByMainBeforeAnyLaunch() throws Exception {
        Path input = scratch.resolve("invalid.brewshot.json");
        Files.writeString(input, """
            {"version":1,"source":{"url":"https://example.test/"},
             "capture":{"format":"png","dpr":5}}
            """);
        var job = BrewShotWatchJob.prepare(input, Files.createDirectory(scratch.resolve("job")));
        var cli = BrewShotWatchCapture.literalArguments(job.arguments());
        cli.add("-o"); cli.add(scratch.resolve("invalid.png").toString());
        Main.sessionLauncherForTests = (width, height, dpr) -> {
            throw new AssertionError("Main must reject the invalid option before launch");
        };
        try {
            assertEquals(2, Main.run(cli.toArray(String[]::new)));
            assertFalse(Files.exists(scratch.resolve("invalid.png")));
        } finally { Main.sessionLauncherForTests = null; }
    }

    @Test void subprocessPreservesExitAndContinuesAfterDeadline() throws Exception {
        assertEquals(7, BrewShotFolderWorker.runJobProcess(
            List.of("/bin/sh", "-c", "exit 7"), 2000));
        long started = System.nanoTime();
        var failure = assertThrows(BrewShotFolderWorker.JobFailure.class,
            () -> BrewShotFolderWorker.runJobProcess(
                List.of("/bin/sh", "-c", "exec sleep 30"), 100));
        assertTrue((System.nanoTime() - started) / 1_000_000 < 17000,
            "deadline plus the bounded shutdown grace must return");
        assertEquals("job-deadline", failure.getMessage());
        assertEquals(0, BrewShotFolderWorker.runJobProcess(
            List.of("/bin/sh", "-c", "exit 0"), 2000));
    }

    @Test void rejectsMislabeledAndTruncatedGif() throws Exception {
        Path bad = scratch.resolve("bad.gif");
        Files.writeString(bad, "this is a PNG mislabeled as GIF");
        assertThrows(BrewShotFolderWorker.JobFailure.class,
            () -> BrewShotFolderWorker.requireGif(bad));
        Files.writeString(bad, "GIF89a");
        assertThrows(BrewShotFolderWorker.JobFailure.class,
            () -> BrewShotFolderWorker.requireGif(bad));
    }
}
