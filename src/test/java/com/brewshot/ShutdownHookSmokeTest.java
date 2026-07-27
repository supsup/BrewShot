package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The F1-r2 contract, as a test (Fixpoint's live repro shape, brewshot seq 70): SIGTERM a JVM
 * holding a live BrewShot and assert the portable shutdown-hook contract: all observed Chrome
 * processes are reaped, but the profile remains when no process-tree containment owner can
 * prove that a late helper did not reparent after the final snapshot.
 *
 * <p>HONEST SCOPE: this pins the contract (hook removed/no-op -> red, verified by mutant), not
 * the specific flush race — the race did not reproduce synthetically on the dev machine under
 * launch-only, cookie-pressure, or settled-instance probes against the old racey hook, so the
 * live repro in brewshot seq 70 remains the evidence for the kill-descendants + bounded-wait
 * ordering, and this test is the regression floor beneath it.
 */
class ShutdownHookSmokeTest {

    @Test
    void sigtermReapsObservedChromeTreeAndRetainsUnprovenProfileDir() throws Exception {
        TestChrome.requireChromeOrLoudSkip("ShutdownHookSmokeTest");

        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process probe = new ProcessBuilder(javaBin,
            "-cp", System.getProperty("java.class.path"), "com.brewshot.ShutdownHookProbeMain")
            .redirectErrorStream(true)
            .start();

        Path profileDir = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(probe.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("READY ")) {
                    profileDir = Path.of(line.substring("READY ".length()).trim());
                    break;
                }
            }
            assertNotNull(profileDir, "probe never printed READY <profileDir>");
            assertTrue(Files.exists(profileDir), "probe's live profile dir should exist pre-kill");

            probe.destroy(); // SIGTERM — the idiomatic Ctrl+C path the hook's scope comment covers
            assertTrue(probe.waitFor(20, TimeUnit.SECONDS), "probe JVM did not exit after SIGTERM");
        } finally {
            probe.destroyForcibly(); // belt: never leave the probe running on an assertion failure
        }

        try {
            // The hook completes before JVM exit, but give the kernel a beat to
            // reap the known tree before asserting (scheduler lag, not
            // correctness slack).
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline
                    && anyProcessReferences(profileDir)) {
                Thread.sleep(200);
            }

            assertFalse(anyProcessReferences(profileDir),
                "an observed process still references the profile dir");
            assertTrue(Files.exists(profileDir),
                "without a portable containment proof the hook must retain the profile");
        } finally {
            deleteTree(profileDir);
        }
    }

    private static boolean anyProcessReferences(Path profileDir) {
        String needle = profileDir.toString();
        return ProcessHandle.allProcesses()
            .map(p -> p.info().commandLine().orElse(""))
            .anyMatch(cmd -> cmd.contains(needle));
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) { return; }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(
                    Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(
                    Path directory, IOException failure) throws IOException {
                if (failure != null) { throw failure; }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
