package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pure/dummy-process coverage for the bounded DevTools bootstrap witness seam. */
class BrewShotBootstrapTest {

    private static final String PATH = "/devtools/browser/01234567-abcd-4def-8123-0123456789ab";

    @Test
    void macSeatbeltRefusesUnifiedChromeWithFixedActionableText() {
        String binary = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
        String refusal = BrewShot.launchContextRefusal(
            Map.of("CODEX_SANDBOX", "seatbelt", "SECRET", "do-not-print"),
            "Mac OS X", binary);

        assertTrue(refusal.contains("refused to launch unified Chrome"), refusal);
        assertTrue(refusal.contains("normal Terminal"), refusal);
        assertTrue(refusal.contains("No Chrome process or profile was created"), refusal);
        assertFalse(refusal.contains(binary), refusal);
        assertFalse(refusal.contains("do-not-print"), refusal);
    }

    @Test
    void refusalIsNarrowToMacSeatbeltAndAllowsExplicitHeadlessShell() {
        String headlessShell = "/opt/chrome-headless-shell";
        assertNull(BrewShot.launchContextRefusal(
            Map.of(), "Mac OS X", "/Applications/Google Chrome"));
        assertNull(BrewShot.launchContextRefusal(
            Map.of("CODEX_SANDBOX", "seatbelt"), "Linux", "/usr/bin/chromium"));
        assertNull(BrewShot.launchContextRefusal(
            Map.of("CODEX_SANDBOX", " seatbelt ",
                "BREWSHOT_CHROME", headlessShell),
            "Mac OS X", headlessShell));
        assertNotNull(BrewShot.launchContextRefusal(
            Map.of("CODEX_SANDBOX", "seatbelt"), "Mac OS X", headlessShell),
            "a compatible basename is not an escape hatch unless the caller explicitly overrides it");
    }

    @Test
    void stdoutOnlyEndpointIsAcceptedAndContinuouslyDrained(@TempDir Path profile)
            throws Exception {
        BootstrapProcess process = BootstrapProcess.alive(
            "startup\nDevTools listening on ws://127.0.0.1:9222" + PATH
                + "\nafter-endpoint\n",
            "");

        BrewShot.DevToolsBootstrapResult result =
            BrewShot.observeDevToolsEndpoint(process, profile, 500);

        assertEquals(BrewShot.DevToolsBootstrapOutcome.ENDPOINT, result.outcome());
        assertEquals("ws://127.0.0.1:9222" + PATH, result.webSocketUrl());
        assertEquals(
            java.util.Set.of(BrewShot.DevToolsWitnessSource.STDOUT),
            result.sources());
        assertTrue(result.stdoutTail().contains("after-endpoint"), result.stdoutTail());
    }

    @Test
    void stderrOnlyEndpointIsAcceptedAfterAnOverlongUnrelatedLine(
            @TempDir Path profile) throws Exception {
        BootstrapProcess process = BootstrapProcess.alive("",
            "z".repeat(5_000) + "\n"
                + "DevTools listening on ws://127.0.0.1:9223" + PATH + "\n");

        BrewShot.DevToolsBootstrapResult result =
            BrewShot.observeDevToolsEndpoint(process, profile, 500);

        assertEquals(BrewShot.DevToolsBootstrapOutcome.ENDPOINT, result.outcome());
        assertEquals("ws://127.0.0.1:9223" + PATH, result.webSocketUrl());
        assertEquals(
            java.util.Set.of(BrewShot.DevToolsWitnessSource.STDERR),
            result.sources());
    }

    @Test
    void profileOnlyEndpointSurvivesCreateWriteRace(@TempDir Path profile)
            throws Exception {
        BootstrapProcess process = BootstrapProcess.alive("", "");
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(30);
                Files.writeString(profile.resolve("DevToolsActivePort"),
                    "9333\n", StandardCharsets.UTF_8);
                Thread.sleep(40);
                Files.writeString(profile.resolve("DevToolsActivePort"),
                    "9333\n" + PATH + "\n", StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "devtools-active-port-writer");
        writer.start();

        BrewShot.DevToolsBootstrapResult result =
            BrewShot.observeDevToolsEndpoint(process, profile, 500);
        writer.join(1_000);

        assertEquals(BrewShot.DevToolsBootstrapOutcome.ENDPOINT, result.outcome());
        assertEquals("ws://127.0.0.1:9333" + PATH, result.webSocketUrl());
        assertEquals(
            java.util.Set.of(BrewShot.DevToolsWitnessSource.PROFILE),
            result.sources());
    }

    @Test
    void matchingStreamAndProfileWitnessesAreNormalizedAndAccepted(
            @TempDir Path profile) throws Exception {
        Files.writeString(profile.resolve("DevToolsActivePort"),
            "9444\n" + PATH + "\n", StandardCharsets.UTF_8);
        BootstrapProcess process = BootstrapProcess.alive(
            "DevTools listening on ws://localhost:9444" + PATH + "\n", "");

        BrewShot.DevToolsBootstrapResult result =
            BrewShot.observeDevToolsEndpoint(process, profile, 500);

        assertEquals(BrewShot.DevToolsBootstrapOutcome.ENDPOINT, result.outcome());
        assertEquals(java.util.Set.of(
            BrewShot.DevToolsWitnessSource.STDOUT,
            BrewShot.DevToolsWitnessSource.PROFILE), result.sources());
    }

    @Test
    void transientHalfWrittenProfileAfterStreamWitnessIsTolerated(
            @TempDir Path profile) throws Exception {
        try (OpenStreamProcess process = OpenStreamProcess.alive(
                "DevTools listening on ws://127.0.0.1:9445" + PATH + "\n", "")) {
            Thread writer = new Thread(() -> {
                try {
                    Thread.sleep(20);
                    Files.writeString(profile.resolve("DevToolsActivePort"),
                        "9445\n", StandardCharsets.UTF_8);
                    Thread.sleep(40);
                    Files.writeString(profile.resolve("DevToolsActivePort"),
                        "9445\n" + PATH + "\n", StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "devtools-active-port-two-phase-writer");
            writer.start();

            BrewShot.DevToolsBootstrapResult result =
                BrewShot.observeDevToolsEndpoint(process, profile, 500);
            writer.join(1_000);

            assertEquals(BrewShot.DevToolsBootstrapOutcome.ENDPOINT, result.outcome());
            assertEquals(java.util.Set.of(
                BrewShot.DevToolsWitnessSource.STDOUT,
                BrewShot.DevToolsWitnessSource.PROFILE), result.sources());
        }
    }

    @Test
    void delayedDisagreeingProfileWithinWitnessWindowFailsLoud(
            @TempDir Path profile) throws Exception {
        try (OpenStreamProcess process = OpenStreamProcess.alive(
                "DevTools listening on ws://127.0.0.1:9556" + PATH + "\n", "")) {
            Thread writer = new Thread(() -> {
                try {
                    Thread.sleep(30);
                    Files.writeString(profile.resolve("DevToolsActivePort"),
                        "9667\n" + PATH + "\n", StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "devtools-active-port-disagreeing-writer");
            writer.start();

            BrewShot.DevToolsBootstrapResult result =
                BrewShot.observeDevToolsEndpoint(process, profile, 500);
            writer.join(1_000);

            assertEquals(
                BrewShot.DevToolsBootstrapOutcome.DISAGREEING_ENDPOINTS,
                result.outcome());
            assertEquals(java.util.Set.of(
                BrewShot.DevToolsWitnessSource.STDOUT,
                BrewShot.DevToolsWitnessSource.PROFILE), result.sources());
        }
    }

    @RepeatedTest(5)
    void closedStreamsStillWaitForDelayedProfileDisagreement(
            @TempDir Path profile) throws Exception {
        BootstrapProcess process = BootstrapProcess.alive(
            "DevTools listening on ws://127.0.0.1:9556" + PATH + "\n", "");
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(70);
                Files.writeString(profile.resolve("DevToolsActivePort"),
                    "9667\n" + PATH + "\n", StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "closed-stream-devtools-active-port-writer");
        writer.start();

        BrewShot.DevToolsBootstrapResult result =
            BrewShot.observeDevToolsEndpoint(process, profile, 500);
        writer.join(1_000);

        assertFalse(writer.isAlive(), "delayed profile writer must finish");
        assertEquals(
            BrewShot.DevToolsBootstrapOutcome.DISAGREEING_ENDPOINTS,
            result.outcome());
        assertEquals(java.util.Set.of(
            BrewShot.DevToolsWitnessSource.STDOUT,
            BrewShot.DevToolsWitnessSource.PROFILE), result.sources());
    }

    @Test
    void disagreeingWitnessesFailLoud(@TempDir Path profile) throws Exception {
        Files.writeString(profile.resolve("DevToolsActivePort"),
            "9555\n" + PATH + "\n", StandardCharsets.UTF_8);
        BootstrapProcess process = BootstrapProcess.alive(
            "DevTools listening on ws://127.0.0.1:9666" + PATH + "\n", "");

        BrewShot.DevToolsBootstrapResult result =
            BrewShot.observeDevToolsEndpoint(process, profile, 500);

        assertEquals(
            BrewShot.DevToolsBootstrapOutcome.DISAGREEING_ENDPOINTS,
            result.outcome());
        assertNull(result.webSocketUrl());
        assertEquals(java.util.Set.of(
            BrewShot.DevToolsWitnessSource.STDOUT,
            BrewShot.DevToolsWitnessSource.PROFILE), result.sources());
    }

    @Test
    void malformedEndpointAndSanitizedBoundedTailsAreReported(
            @TempDir Path profile) throws Exception {
        String longSecret = "x".repeat(600);
        BootstrapProcess process = BootstrapProcess.exited(17,
            "profile=" + profile + " https://secret.example/token "
                + "PASSWORD=hunter2 BREWSHOT_CHROME_ARGS=" + longSecret + "\n"
                + "Authorization: Bearer top-secret-token\n"
                + "crash report: /Volumes/Private Folder/secret.log\n"
                + "diagnostic --proxy-password hunter2 DevTools listening on "
                + "http://not-websocket.example/devtools/browser/bad\n",
            "crash report: /Users/operator/Library/Logs/secret.log\n"
                + "argv: chrome --password=hunter2 /private/tmp/secret-profile\n");

        BrewShot.DevToolsBootstrapResult result =
            BrewShot.observeDevToolsEndpoint(process, profile, 500);

        assertEquals(
            BrewShot.DevToolsBootstrapOutcome.MALFORMED_ENDPOINT,
            result.outcome());
        assertEquals(17, result.exitCode());
        assertFalse(result.stdoutTail().contains(profile.toString()), result.stdoutTail());
        assertFalse(result.stdoutTail().contains("secret.example"), result.stdoutTail());
        assertFalse(result.stderrTail().contains("/Users/operator"), result.stderrTail());
        assertFalse(result.stdoutTail().contains("hunter2"), result.stdoutTail());
        assertFalse(result.stdoutTail().contains("/Volumes/"), result.stdoutTail());
        assertFalse(result.stdoutTail().contains("Private Folder"), result.stdoutTail());
        assertFalse(result.stdoutTail().contains(longSecret), result.stdoutTail());
        assertTrue(result.stdoutTail().contains("<sensitive startup line redacted>"),
            result.stdoutTail());
        assertTrue(result.stdoutTail().contains("<host path line redacted>"),
            result.stdoutTail());
        assertTrue(result.stderrTail().contains("<command line redacted>"),
            result.stderrTail());
        assertTrue(result.stdoutTail().length() <= 1_000, result.stdoutTail());
    }

    @Test
    void adjacentFlagsCannotExposeCredentialValueInRetainedTail(
            @TempDir Path profile) throws Exception {
        BootstrapProcess process = BootstrapProcess.exited(1,
            "diagnostic --foo --proxy-password hunter2\n", "");

        BrewShot.DevToolsBootstrapResult result =
            BrewShot.observeDevToolsEndpoint(process, profile, 500);

        assertEquals(BrewShot.DevToolsBootstrapOutcome.PROCESS_EXITED,
            result.outcome());
        assertEquals("diagnostic --foo --proxy-password <redacted>",
            result.stdoutTail());
        assertFalse(result.stdoutTail().contains("hunter2"), result.stdoutTail());
    }

    @Test
    void nonLoopbackAndSameStreamDisagreementAreMalformedOrConflicting(
            @TempDir Path profile) throws Exception {
        BrewShot.DevToolsBootstrapResult remote =
            BrewShot.observeDevToolsEndpoint(
                BootstrapProcess.exited(1,
                    "DevTools listening on ws://192.0.2.1:9222" + PATH + "\n", ""),
                profile.resolve("remote"), 200);
        assertEquals(BrewShot.DevToolsBootstrapOutcome.MALFORMED_ENDPOINT,
            remote.outcome());

        BrewShot.DevToolsBootstrapResult conflicting =
            BrewShot.observeDevToolsEndpoint(
                BootstrapProcess.alive(
                    "DevTools listening on ws://127.0.0.1:9222" + PATH + "\n"
                        + "DevTools listening on ws://127.0.0.1:9333" + PATH + "\n",
                    ""), profile.resolve("same-stream"), 500);
        assertEquals(BrewShot.DevToolsBootstrapOutcome.DISAGREEING_ENDPOINTS,
            conflicting.outcome());
        assertEquals(java.util.Set.of(BrewShot.DevToolsWitnessSource.STDOUT),
            conflicting.sources());
    }

    @Test
    void processExitAndAliveTimeoutAreDifferentOutcomes(@TempDir Path temp)
            throws Exception {
        BrewShot.DevToolsBootstrapResult exited = BrewShot.observeDevToolsEndpoint(
            BootstrapProcess.exited(23, "", ""), temp.resolve("exited"), 200);
        long started = System.nanoTime();
        BrewShot.DevToolsBootstrapResult alive = BrewShot.observeDevToolsEndpoint(
            BootstrapProcess.alive("", ""), temp.resolve("alive"), 35);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(BrewShot.DevToolsBootstrapOutcome.PROCESS_EXITED, exited.outcome());
        assertEquals(23, exited.exitCode());
        assertEquals(BrewShot.DevToolsBootstrapOutcome.ALIVE_TIMEOUT, alive.outcome());
        assertEquals(-1, alive.exitCode());
        assertTrue(elapsedMs < 500, "shared deadline must bound the alive case: " + elapsedMs);
    }

    @Test
    void incompleteOrOversizedActivePortFileIsMalformedAtTerminalOutcome(
            @TempDir Path profile) throws Exception {
        Files.writeString(profile.resolve("DevToolsActivePort"), "9222\n");
        BrewShot.DevToolsBootstrapResult incomplete =
            BrewShot.observeDevToolsEndpoint(
                BootstrapProcess.exited(1, "", ""), profile, 200);
        assertEquals(
            BrewShot.DevToolsBootstrapOutcome.MALFORMED_ENDPOINT,
            incomplete.outcome());

        Files.writeString(profile.resolve("DevToolsActivePort"), "x".repeat(4_097));
        BrewShot.DevToolsBootstrapResult oversized =
            BrewShot.observeDevToolsEndpoint(
                BootstrapProcess.exited(1, "", ""), profile, 200);
        assertEquals(
            BrewShot.DevToolsBootstrapOutcome.MALFORMED_ENDPOINT,
            oversized.outcome());
    }

    @Test
    void activePortWitnessRejectsInvalidUtf8AndSymlinks(@TempDir Path temp)
            throws Exception {
        Path invalidProfile = Files.createDirectory(temp.resolve("invalid-utf8"));
        Files.write(invalidProfile.resolve("DevToolsActivePort"),
            new byte[] {(byte) 0xc3, 0x28, '\n'});
        BrewShot.DevToolsBootstrapResult invalidUtf8 =
            BrewShot.observeDevToolsEndpoint(
                BootstrapProcess.exited(1, "", ""), invalidProfile, 200);
        assertEquals(BrewShot.DevToolsBootstrapOutcome.MALFORMED_ENDPOINT,
            invalidUtf8.outcome());

        Path linkedProfile = Files.createDirectory(temp.resolve("linked"));
        Path external = temp.resolve("outside-active-port");
        Files.writeString(external, "9444\n" + PATH + "\n");
        Files.createSymbolicLink(
            linkedProfile.resolve("DevToolsActivePort"), external);
        BrewShot.DevToolsBootstrapResult symlink =
            BrewShot.observeDevToolsEndpoint(
                BootstrapProcess.exited(1, "", ""), linkedProfile, 200);
        assertEquals(BrewShot.DevToolsBootstrapOutcome.MALFORMED_ENDPOINT,
            symlink.outcome());
    }

    @Test
    void userFacingFailureMessagesDistinguishEveryTerminalFailure() {
        BrewShot.DevToolsBootstrapResult exited = new BrewShot.DevToolsBootstrapResult(
            BrewShot.DevToolsBootstrapOutcome.PROCESS_EXITED, null,
            java.util.Set.of(), 17, 12, "", "");
        BrewShot.DevToolsBootstrapResult timeout = new BrewShot.DevToolsBootstrapResult(
            BrewShot.DevToolsBootstrapOutcome.ALIVE_TIMEOUT, null,
            java.util.Set.of(), -1, 34, "", "");
        BrewShot.DevToolsBootstrapResult malformed = new BrewShot.DevToolsBootstrapResult(
            BrewShot.DevToolsBootstrapOutcome.MALFORMED_ENDPOINT, null,
            java.util.Set.of(BrewShot.DevToolsWitnessSource.STDERR), 1, 56,
            "", "redacted");
        BrewShot.DevToolsBootstrapResult disagreeing = new BrewShot.DevToolsBootstrapResult(
            BrewShot.DevToolsBootstrapOutcome.DISAGREEING_ENDPOINTS, null,
            java.util.Set.of(BrewShot.DevToolsWitnessSource.STDOUT,
                BrewShot.DevToolsWitnessSource.PROFILE), -1, 78, "", "");

        assertTrue(BrewShot.bootstrapFailureMessage(exited).contains("exit 17"));
        assertTrue(BrewShot.bootstrapFailureMessage(timeout).contains("remained alive"));
        assertTrue(BrewShot.bootstrapFailureMessage(malformed).contains("malformed"));
        assertTrue(BrewShot.bootstrapFailureMessage(malformed).contains("sanitized stderr tail"));
        assertTrue(BrewShot.bootstrapFailureMessage(disagreeing).contains("disagreeing"));
        assertThrows(IllegalArgumentException.class, () ->
            BrewShot.bootstrapFailureMessage(new BrewShot.DevToolsBootstrapResult(
                BrewShot.DevToolsBootstrapOutcome.ENDPOINT,
                "ws://127.0.0.1:1" + PATH, java.util.Set.of(), -1, 1, "", "")));
    }

    private static final class BootstrapProcess extends Process {
        private final InputStream stdout;
        private final InputStream stderr;
        private final boolean alive;
        private final int exitCode;

        private BootstrapProcess(boolean alive, int exitCode, String stdout, String stderr) {
            this.alive = alive;
            this.exitCode = exitCode;
            this.stdout = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
            this.stderr = new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8));
        }

        static BootstrapProcess alive(String stdout, String stderr) {
            return new BootstrapProcess(true, -1, stdout, stderr);
        }

        static BootstrapProcess exited(int exitCode, String stdout, String stderr) {
            return new BootstrapProcess(false, exitCode, stdout, stderr);
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public int exitValue() {
            if (alive) { throw new IllegalThreadStateException("process is alive"); }
            return exitCode;
        }

        @Override
        public void destroy() { }

        @Override
        public Process destroyForcibly() {
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }

    /** A live dummy process whose stdout/stderr stay open after initial bytes. */
    private static final class OpenStreamProcess extends Process implements AutoCloseable {
        private final PipedInputStream stdout = new PipedInputStream();
        private final PipedInputStream stderr = new PipedInputStream();
        private final PipedOutputStream stdoutWriter;
        private final PipedOutputStream stderrWriter;

        private OpenStreamProcess(String stdoutText, String stderrText) throws Exception {
            stdoutWriter = new PipedOutputStream(stdout);
            stderrWriter = new PipedOutputStream(stderr);
            stdoutWriter.write(stdoutText.getBytes(StandardCharsets.UTF_8));
            stderrWriter.write(stderrText.getBytes(StandardCharsets.UTF_8));
            stdoutWriter.flush();
            stderrWriter.flush();
        }

        static OpenStreamProcess alive(String stdout, String stderr) throws Exception {
            return new OpenStreamProcess(stdout, stderr);
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() throws InterruptedException {
            throw new InterruptedException("dummy process remains alive");
        }

        @Override
        public int exitValue() {
            throw new IllegalThreadStateException("process is alive");
        }

        @Override
        public void destroy() {
            close();
        }

        @Override
        public Process destroyForcibly() {
            close();
            return this;
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public void close() {
            try { stdoutWriter.close(); } catch (Exception ignored) { }
            try { stderrWriter.close(); } catch (Exception ignored) { }
            try { stdout.close(); } catch (Exception ignored) { }
            try { stderr.close(); } catch (Exception ignored) { }
        }
    }
}
