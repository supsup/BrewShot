package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Review brewshot/740 R1. A whole-suite mutant of plan f5f02722 survived 447 tests: Main.run
/// returning the bare capture code (so no exit 6 and no cleanup line, ever) failed nothing,
/// because the one test that drives Main.run needs a Chrome and exercises only the quiet path,
/// while the unconfirmed path was asserted on Main.exitCodeAfterCleanup directly.
///
/// This class drives Main.run from argv to exit code with no Chrome on the host.
/// Main.sessionLauncherForTests hands the CLI a REAL BrewShot over a scripted CDP socket, and the
/// launched process is a fake that stays alive through close(). Everything downstream of the
/// launch is the production path: the navigation and its witnesses, the capture, the artifact
/// write, close(), the lease's cleanup pass, the manifest, the "wrote" line and the exit code.
final class MainCliUnconfirmedCleanupTest {

    private static final long CHROME_PID = 424207L;

    /// A 1x1 PNG. The capture bound reads real dimensions from it and the artifact on disk is a
    /// real image, so the "the output was written" half of exit 6 is checked against bytes.
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5E"
            + "rkJggg==");

    /// The fake pid is not this test's process. On a Linux host the report would ask /proc
    /// whether it is a zombie; pin the answer so the fixture means the same on every host.
    @BeforeEach
    void pinTheZombieProbe() {
        BrewShot.exitedButUnreapedProbeForTests = pid -> false;
    }

    @AfterEach
    void releaseTheSeams() {
        BrewShot.exitedButUnreapedProbeForTests = null;
        Main.sessionLauncherForTests = null;
    }

    private record Run(int code, String stderr, boolean chromeAliveAfterRun, int killsSent) { }

    /// Run the CLI over the scripted session, capture stderr, then drain the lease so the
    /// JVM-wide registry is left as it was found.
    private static Run runCli(FakeChrome chrome, ScriptedChrome script, Path profile, String... args)
            throws Exception {
        Main.sessionLauncherForTests = (width, height, devicePixelRatio) -> {
            assertEquals(1280, width, "the CLI default viewport must reach the launcher");
            assertEquals(900, height);
            return script.session(chrome, profile);
        };
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        PrintStream original = System.err;
        int code;
        boolean aliveAfterRun;
        try {
            System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
            code = Main.run(args);
            aliveAfterRun = chrome.isAlive();
        } finally {
            System.setErr(original);
            Main.sessionLauncherForTests = null;
            chrome.die();
            BrewShot.runShutdownCleanupForTests();
        }
        return new Run(code, errors.toString(StandardCharsets.UTF_8), aliveAfterRun,
            chrome.destroyForciblyCalls.get());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cleanupField(Path manifest) throws Exception {
        Map<String, Object> root = (Map<String, Object>) MiniJson.parse(Files.readString(manifest));
        Map<String, Object> cleanup = (Map<String, Object>) root.get("cleanup");
        assertNotNull(cleanup, "a capture manifest must carry the cleanup outcome: " + root);
        return cleanup;
    }

    @Test
    void aSurvivingProcessReachesTheCallerAsExitSixOneLineAndTheManifestField(@TempDir Path dir)
            throws Exception {
        Path page = dir.resolve("page.html");
        Files.writeString(page, "<!doctype html><title>unconfirmed</title><p>a survivor</p>");
        Path out = dir.resolve("page.png");
        Path manifest = dir.resolve("page.json");
        Path profile = Files.createDirectories(dir.resolve("brewshot-survivor"));
        FakeChrome chrome = new FakeChrome(CHROME_PID, false);
        ScriptedChrome script = new ScriptedChrome();

        Run run = runCli(chrome, script, profile,
            page.toString(), "-o", out.toString(), "--json", manifest.toString(), "--settle", "1");

        // FIXTURE GUARDS: the session was really driven and really torn down, and the process
        // really outlived the kill. Without these a green result could be the quiet path.
        assertEquals(1, script.navigations.get(), "the page must have been navigated");
        assertEquals(1, script.captures.get(), "the page must have been captured");
        assertTrue(script.closed.get(), "close() must have reached the transport");
        assertTrue(run.killsSent() > 0, "close() must really have sent the SIGKILL");
        assertTrue(run.chromeAliveAfterRun(),
            "the launched process must still be alive after the run, or this is the quiet path");

        // WHAT THE CALLER READS, 1: the exit code. 6, in place of the 0 the capture earned.
        assertEquals(6, Main.EXIT_CLEANUP_UNCONFIRMED);
        assertEquals(Main.EXIT_CLEANUP_UNCONFIRMED, run.code(), run.stderr());

        // 2: the artifact was written, as the code promises, and it is the captured bytes.
        assertArrayEquals(ONE_PIXEL_PNG, Files.readAllBytes(out));

        // 3: stderr. The "wrote" line first, then exactly one cleanup line naming the pid, the
        // ps command, the kept profile and the exit code it announces.
        String text = run.stderr();
        int wrote = text.indexOf("brewshot: wrote " + out);
        assertTrue(wrote >= 0, text);
        String marker = "brewshot: cleanup UNCONFIRMED: pid " + CHROME_PID + " ";
        int cleanupLine = text.indexOf(marker);
        assertTrue(cleanupLine >= 0, "the cleanup line must name the pid: " + text);
        assertTrue(cleanupLine > wrote, "the artifact is announced before the warning: " + text);
        assertEquals(cleanupLine, text.lastIndexOf("brewshot: cleanup "),
            "exactly one cleanup line: " + text);
        String line = text.substring(cleanupLine, text.indexOf('\n', cleanupLine)).stripTrailing();
        assertTrue(line.contains("(check with: ps -p " + CHROME_PID + ")"), line);
        assertTrue(line.contains("profile kept: " + profile), line);
        assertTrue(line.endsWith("(exit " + Main.EXIT_CLEANUP_UNCONFIRMED
            + "; the output was written)"), line);

        // 4: the manifest, from the same snapshot as the exit code.
        Map<String, Object> cleanup = cleanupField(manifest);
        assertEquals("process-unconfirmed", cleanup.get("state"), cleanup.toString());
        assertEquals(Boolean.TRUE, cleanup.get("liveProcessObserved"), cleanup.toString());
        assertEquals(Boolean.FALSE, cleanup.get("profileRemoved"), cleanup.toString());
        assertEquals(profile.toString(), cleanup.get("profile"), cleanup.toString());
        List<?> pids = (List<?>) cleanup.get("livePids");
        assertEquals(1, pids.size(), cleanup.toString());
        assertEquals(CHROME_PID, ((Number) pids.get(0)).longValue());
        assertEquals(0L, ((Number) cleanup.get("liveWithoutPid")).longValue());
        assertEquals(List.of(), cleanup.get("exitedUnreapedPids"));
        assertTrue(String.valueOf(cleanup.get("reason")).contains("alive"), cleanup.toString());
    }

    @Test
    void aSessionWhoseProcessExitsOnRequestIsQuietAndExitsZeroThroughTheSameSeam(@TempDir Path dir)
            throws Exception {
        // THE CONTROL. Same seam, same script, same CLI path; the only difference is that the
        // process goes away when asked. If this were 6 too, the test above would be measuring
        // the fixture and not the survivor.
        Path page = dir.resolve("page.html");
        Files.writeString(page, "<!doctype html><title>clean</title><p>clean run</p>");
        Path out = dir.resolve("page.png");
        Path manifest = dir.resolve("page.json");
        Path profile = Files.createDirectories(dir.resolve("brewshot-polite"));
        FakeChrome chrome = new FakeChrome(CHROME_PID + 1, true);
        ScriptedChrome script = new ScriptedChrome();

        Run run = runCli(chrome, script, profile,
            page.toString(), "-o", out.toString(), "--json", manifest.toString(), "--settle", "1");

        assertEquals(1, script.captures.get());
        assertFalse(run.chromeAliveAfterRun(), "fixture guard: the polite process must be gone");
        assertEquals(0, run.code(), run.stderr());
        assertArrayEquals(ONE_PIXEL_PNG, Files.readAllBytes(out));
        assertTrue(run.stderr().contains("brewshot: wrote " + out), run.stderr());
        assertFalse(run.stderr().contains("brewshot: cleanup "),
            "a clean run must print no cleanup line: " + run.stderr());
        Map<String, Object> cleanup = cleanupField(manifest);
        // The test lease carries a proven release, so a reaped tree removes its profile.
        assertEquals("released", cleanup.get("state"), cleanup.toString());
        assertEquals(Boolean.FALSE, cleanup.get("liveProcessObserved"), cleanup.toString());
        assertEquals(List.of(), cleanup.get("livePids"), cleanup.toString());
        assertFalse(Files.exists(profile), "released must mean the profile is gone");
    }

    // -- the fakes ------------------------------------------------------------------------------

    /// The launched Chrome as the lease sees it: alive, with a readable pid, and either gone as
    /// soon as it is asked to stop (the control) or still alive after SIGTERM and SIGKILL (the
    /// survivor). Its exit cannot be awaited: onExit fails at once, which takes awaitHandles
    /// through its "stop waiting, read liveness afterwards" arm instead of the 5 s teardown
    /// budget. The report under test reads liveness AFTER the wait, whichever way the wait
    /// ended; the full-budget wait on an undying process is CleanupReportingTest's fixture.
    static final class FakeChrome extends Process {
        private final long pid;
        private final boolean exitsWhenAsked;
        private volatile boolean alive = true;
        final AtomicInteger destroyForciblyCalls = new AtomicInteger();

        FakeChrome(long pid, boolean exitsWhenAsked) {
            this.pid = pid;
            this.exitsWhenAsked = exitsWhenAsked;
        }

        void die() { alive = false; }

        @Override public long pid() { return pid; }
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public boolean isAlive() { return alive; }

        @Override public int exitValue() {
            if (alive) { throw new IllegalThreadStateException(); }
            return 0;
        }

        @Override public int waitFor() throws InterruptedException {
            while (alive) { Thread.sleep(5); }
            return 0;
        }

        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (alive && System.nanoTime() < deadline) { Thread.sleep(5); }
            return !alive;
        }

        @Override public CompletableFuture<Process> onExit() {
            return alive
                ? CompletableFuture.failedFuture(
                    new IllegalStateException("the exit of this fake cannot be awaited"))
                : CompletableFuture.completedFuture(this);
        }

        @Override public void destroy() {
            if (exitsWhenAsked) { alive = false; }
        }

        @Override public Process destroyForcibly() {
            destroyForciblyCalls.incrementAndGet();
            if (exitsWhenAsked) { alive = false; }
            return this;
        }
    }

    /// A DevTools endpoint in a few lines. Each command is answered by id on the Accumulator the
    /// session reads from; a navigation also fires the load event; a capture returns one pixel.
    /// Same shape as CdpInboxWiringTest's BootstrapWebSocket, with the still-shot methods added.
    static final class ScriptedChrome implements WebSocket {
        final AtomicInteger navigations = new AtomicInteger();
        final AtomicInteger captures = new AtomicInteger();
        final AtomicBoolean closed = new AtomicBoolean();
        /// Every command as sent, in order: MainAuthFileTest reads what reached the browser.
        final java.util.concurrent.ConcurrentLinkedQueue<String> sentCommands =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
        private final LinkedBlockingQueue<BrewShot.InboxMessage> inbox =
            new LinkedBlockingQueue<>(65);
        private final BrewShot.Accumulator listener =
            new BrewShot.Accumulator(inbox, 1 << 20, 64);

        /// The real client over this socket, registered with the test release proof (proven), so
        /// a reaped tree releases and an unreaped one does not: both outcomes are reachable.
        BrewShot session(Process chrome, Path profile) {
            return new BrewShot(chrome, profile, this, inbox, 1_000L);
        }

        @Override public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            sentCommands.add(data.toString());
            @SuppressWarnings("unchecked")
            Map<String, Object> sent = (Map<String, Object>) MiniJson.parse(data.toString());
            int id = ((Number) sent.get("id")).intValue();
            String method = String.valueOf(sent.get("method"));
            String result = switch (method) {
                case "Page.navigate" -> {
                    navigations.incrementAndGet();
                    yield "{\"frameId\":\"main\"}";
                }
                case "Runtime.evaluate" -> {
                    // The still-shot path evaluates one thing: the viewport witness after load.
                    // This session never sent a metrics override (BrewShot.launch does that, and
                    // the seam replaces launch), so the witness expects the unset 0x0, and the
                    // page reports exactly that.
                    yield "{\"result\":{\"type\":\"string\",\"value\":\"0x0\"}}";
                }
                case "Page.captureScreenshot" -> {
                    captures.incrementAndGet();
                    yield "{\"data\":\"" + Base64.getEncoder().encodeToString(ONE_PIXEL_PNG) + "\"}";
                }
                default -> "{}";
            };
            listener.onText(this, "{\"id\":" + id + ",\"result\":" + result + "}", true);
            if (method.equals("Page.navigate")) {
                listener.onText(this,
                    "{\"method\":\"Page.loadEventFired\",\"params\":{\"timestamp\":1}}", true);
            }
            return CompletableFuture.completedFuture(this);
        }

        @Override public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            closed.set(true);
            return CompletableFuture.completedFuture(this);
        }
        @Override public void request(long n) { }
        @Override public String getSubprotocol() { return ""; }
        @Override public boolean isOutputClosed() { return closed.get(); }
        @Override public boolean isInputClosed() { return false; }
        @Override public void abort() { closed.set(true); }
    }
}
