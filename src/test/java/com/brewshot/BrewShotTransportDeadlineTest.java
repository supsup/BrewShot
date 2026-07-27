package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pure transport discriminators for plan 0cd89c1b. No test in this class
 * discovers or launches Chrome.
 */
class BrewShotTransportDeadlineTest {

    @Test
    void publicTimeoutAndHeapContractsRejectInvalidValuesAndKeepDocumentedZeroNoOps(
            @TempDir Path temp) throws Exception {
        FakeWebSocket socket = new FakeWebSocket();
        BrewShot shot = new BrewShot(
            new FakeProcess(true), profile(temp, "validation"), socket,
            new LinkedBlockingQueue<>(), 40);
        try {
            assertThrows(IllegalArgumentException.class, () -> shot.navTimeout(0));
            assertThrows(IllegalArgumentException.class, () -> shot.navTimeout(-1));
            assertThrows(IllegalArgumentException.class, () -> shot.commandTimeout(0));
            assertThrows(IllegalArgumentException.class, () -> shot.commandTimeout(-1));
            assertThrows(IllegalArgumentException.class, () -> shot.recordingHeapBudget(0));
            assertThrows(IllegalArgumentException.class, () -> shot.recordingHeapBudget(-1));
            assertThrows(IllegalArgumentException.class, () -> shot.waitFor("true", 0));
            assertThrows(IllegalArgumentException.class,
                () -> shot.waitForNetworkIdle(-1, 1));
            assertThrows(IllegalArgumentException.class,
                () -> shot.waitForNetworkIdle(1, -1));
            assertThrows(IllegalArgumentException.class, () -> shot.settle(-1));

            assertSame(shot, shot.navTimeout(1));
            assertSame(shot, shot.commandTimeout(1));
            assertSame(shot, shot.recordingHeapBudget(1));
            shot.waitForNetworkIdle(0, 0);
            shot.settle(0);
            assertEquals(0, socket.sendCalls.get(),
                "documented zero-duration no-ops must not touch CDP");
        } finally {
            shot.close();
        }
    }

    @Test
    void everyLibraryRecorderRejectsBadCountsAndDelaysBeforeProtocolTraffic(
            @TempDir Path temp) throws Exception {
        FakeWebSocket socket = new FakeWebSocket();
        BrewShot shot = new BrewShot(
            new FakeProcess(true), profile(temp, "recorder-validation"), socket,
            new LinkedBlockingQueue<>(), 40);
        Path out = temp.resolve("never-written.gif");
        try {
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGif(0, 0, 1, 1, 0, 1, 1, out));
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGif(0, 0, 1, 1, 1, 0, 1, out));
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGif(0, 0, 1, 1, 1, 1, 0, out));
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGifElement("#x", 0, 1, 1, 1, out));
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGifElement("#x", 1, 0, 1, 1, out));
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGifScroll(0, 0, 1, 1, out));
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGifScroll(1, -1, 1, 1, out));
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGifScroll(1, 0, 0, 1, out));
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGifFullPage(0, 1, 1, out));
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGifFullPage(1, 0, 1, out));
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGifRegion(0, 1, 0, 1, 1, out));
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGifRegion(0, 1, 1, 0, 1, out));
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGifStream(0, 1, out));
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGifStream(1, 0, out));
            assertEquals(0, socket.sendCalls.get(),
                "invalid recorder arguments must fail before page eval/capture");
        } finally {
            shot.close();
        }
    }

    @Test
    void neverCompletingSendUsesCommandBudgetAndCancelsTransport(@TempDir Path temp)
            throws Exception {
        LinkedBlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        FakeWebSocket socket = new FakeWebSocket();
        CompletableFuture<WebSocket> stuckSend = new CompletableFuture<>();
        socket.onSend = ignored -> stuckSend;
        FakeProcess process = new FakeProcess(true);
        BrewShot shot = new BrewShot(process, profile(temp, "send"), socket, inbox, 40);
        shot.commandTimeout(40);

        long started = System.nanoTime();
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> shot.eval("1"));
            long elapsedMs = elapsedMillis(started);

            assertTrue(failure.getMessage().contains("CDP timeout sending Runtime.evaluate"),
                failure.getMessage());
            assertTrue(elapsedMs >= 20 && elapsedMs < 750,
                "40ms send budget completed in " + elapsedMs + "ms");
            assertTrue(stuckSend.isCancelled(), "timed-out send future must be cancelled");
            assertEquals(1, socket.abortCalls.get(),
                "a late send cannot be allowed onto a later command");
        } finally {
            shot.close();
        }
    }

    @Test
    void sendTimeIsSpentFromTheSameDeadlineAsTheResponse(@TempDir Path temp)
            throws Exception {
        LinkedBlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        FakeWebSocket socket = new FakeWebSocket();
        // Send consumes 180ms of a 250ms command budget. No response follows.
        // A reset-after-send implementation takes about 430ms; one deadline
        // takes about 250ms.
        socket.onSend = ignored -> new DelayedSuccessfulFuture(socket, 180);
        BrewShot shot = new BrewShot(
            new FakeProcess(true), profile(temp, "shared"), socket, inbox, 40);
        shot.commandTimeout(250);

        long started = System.nanoTime();
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> shot.eval("1"));
            long elapsedMs = elapsedMillis(started);

            assertTrue(failure.getMessage().contains("CDP timeout"), failure.getMessage());
            assertTrue(elapsedMs >= 200 && elapsedMs < 360,
                "send + response should share 250ms, completed in " + elapsedMs + "ms");
        } finally {
            shot.close();
        }
    }

    @Test
    void successfulCommandSurvivesASaturatedTimeout(@TempDir Path temp) throws Exception {
        LinkedBlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        FakeWebSocket socket = new FakeWebSocket();
        socket.onSend = text -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> sent = (Map<String, Object>) MiniJson.parse(text);
            int id = ((Number) sent.get("id")).intValue();
            inbox.add("{\"id\":" + id
                + ",\"result\":{\"result\":{\"value\":\"still-ok\"}}}");
            return CompletableFuture.completedFuture(socket);
        };
        BrewShot shot = new BrewShot(
            new FakeProcess(true), profile(temp, "success"), socket, inbox, 40);
        shot.commandTimeout(Long.MAX_VALUE);

        try {
            assertEquals("still-ok", shot.eval("'still-ok'"));
            assertEquals(1, socket.sendCalls.get());
        } finally {
            shot.close();
        }
    }

    @Test
    void waitForUsesSaturatedMonotonicDeadlineAtLongMax(
            @TempDir Path temp) throws Exception {
        LinkedBlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        FakeWebSocket socket = new FakeWebSocket();
        AtomicInteger polls = new AtomicInteger();
        socket.onSend = text -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> sent = (Map<String, Object>) MiniJson.parse(text);
            int id = ((Number) sent.get("id")).intValue();
            boolean ready = polls.incrementAndGet() >= 2;
            inbox.add("{\"id\":" + id
                + ",\"result\":{\"result\":{\"value\":" + ready + "}}}");
            return CompletableFuture.completedFuture(socket);
        };
        BrewShot shot = new BrewShot(
            new FakeProcess(true), profile(temp, "wait-for-max"), socket, inbox, 40);

        long started = System.nanoTime();
        try {
            shot.waitFor("ready", Long.MAX_VALUE);
            assertEquals(2, polls.get(),
                "Long.MAX_VALUE must not wrap into a timeout after the first false poll");
            assertTrue(elapsedMillis(started) < 1_000,
                "the successful discriminator must not spend the enormous budget");
        } finally {
            shot.close();
        }
    }

    @Test
    void networkIdleUsesSaturatedMonotonicDeadlineAtLongMax(
            @TempDir Path temp) throws Exception {
        LinkedBlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        inbox.add("{\"method\":\"Network.requestWillBeSent\","
            + "\"params\":{\"requestId\":\"request-1\"}}");
        inbox.add("{\"method\":\"Network.loadingFinished\","
            + "\"params\":{\"requestId\":\"request-1\"}}");
        BrewShot shot = new BrewShot(
            new FakeProcess(true), profile(temp, "network-idle-max"),
            new FakeWebSocket(), inbox, 40);

        long started = System.nanoTime();
        try {
            shot.waitForNetworkIdle(30, Long.MAX_VALUE);
            long elapsedMs = elapsedMillis(started);
            assertTrue(elapsedMs >= 20 && elapsedMs < 1_000,
                "network quiet window should complete promptly, not wrap or wait forever: "
                    + elapsedMs + "ms");
        } finally {
            shot.close();
        }
    }

    @Test
    void neverCompletingConnectTimesOutAndCleansLaunchResources(@TempDir Path temp)
            throws Exception {
        CompletableFuture<WebSocket> stuckConnect = new CompletableFuture<>();
        FakeProcess process = new FakeProcess(false);
        Path profile = profile(temp, "connect");

        long started = System.nanoTime();
        IOException failure = assertThrows(IOException.class, () ->
            BrewShot.finishLaunch(process, profile, "ws://127.0.0.1:1/devtools/browser/fake",
                (uri, listener, timeout) -> stuckConnect, 40));
        long elapsedMs = elapsedMillis(started);

        assertTrue(failure.getMessage().contains("connect timed out"), failure.getMessage());
        assertTrue(elapsedMs >= 20 && elapsedMs < 750,
            "40ms connect budget completed in " + elapsedMs + "ms");
        assertTrue(stuckConnect.isCancelled(), "timed-out connect future must be cancelled");
        assertFalse(process.isAlive(), "failed launch must forcibly reap its process");
        assertTrue(process.destroyForciblyCalls.get() >= 1);
        assertFalse(Files.exists(profile), "failed launch must delete its temp profile");
    }

    @Test
    void neverCompletingCloseIsBoundedCleansUpAndIsIdempotent(@TempDir Path temp)
            throws Exception {
        FakeWebSocket socket = new FakeWebSocket();
        CompletableFuture<WebSocket> stuckClose = new CompletableFuture<>();
        socket.closeFuture = stuckClose;
        FakeProcess process = new FakeProcess(false);
        Path profile = profile(temp, "close");
        BrewShot shot = new BrewShot(
            process, profile, socket, new LinkedBlockingQueue<>(), 40);

        long started = System.nanoTime();
        shot.close();
        long elapsedMs = elapsedMillis(started);

        assertTrue(elapsedMs >= 20 && elapsedMs < 750,
            "40ms close grace completed in " + elapsedMs + "ms");
        assertTrue(stuckClose.isCancelled(), "timed-out close future must be cancelled");
        assertEquals(1, socket.abortCalls.get());
        assertFalse(process.isAlive(), "close must reap the browser process");
        assertEquals(1, process.destroyCalls.get());
        assertTrue(process.destroyForciblyCalls.get() >= 1,
            "an uncooperative process must be forcibly reaped");
        assertFalse(Files.exists(profile), "close must delete its temp profile");

        shot.close();
        assertEquals(1, socket.closeCalls.get(), "close must send at most one close frame");
        assertEquals(1, process.destroyCalls.get(), "close must tear down the process once");
    }

    @Test
    void closeKeepsResourcesRegisteredUntilCleanupCompletes(@TempDir Path temp)
            throws Exception {
        FakeWebSocket socket = new FakeWebSocket();
        FakeProcess process = new FakeProcess(true);
        Path profile = profile(temp, "owned-close");
        AtomicBoolean ownedWhileCloseFrameWasSent = new AtomicBoolean();
        socket.onClose = () -> ownedWhileCloseFrameWasSent.set(
            BrewShot.ownsResources(process, profile));
        BrewShot shot = new BrewShot(
            process, profile, socket, new LinkedBlockingQueue<>(), 40);

        shot.close();

        assertTrue(ownedWhileCloseFrameWasSent.get(),
            "shutdown cleanup must retain ownership until process/profile cleanup completes");
    }

    @Test
    void interruptedCloseRestoresTheInterruptAfterCleanup(@TempDir Path temp)
            throws Exception {
        FakeWebSocket socket = new FakeWebSocket();
        socket.closeFuture = new CompletableFuture<>();
        FakeProcess process = new FakeProcess(true);
        Path profile = profile(temp, "interrupt");
        BrewShot shot = new BrewShot(
            process, profile, socket, new LinkedBlockingQueue<>(), 1_000);
        AtomicBoolean interruptRestored = new AtomicBoolean();
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();

        Thread closer = new Thread(() -> {
            try {
                Thread.currentThread().interrupt();
                shot.close();
                interruptRestored.set(Thread.currentThread().isInterrupted());
            } catch (Throwable t) {
                threadFailure.set(t);
            }
        }, "brewshot-interrupted-close-test");
        closer.start();
        closer.join(2_000);

        assertFalse(closer.isAlive(), "interrupted close must remain bounded");
        assertNull(threadFailure.get());
        assertTrue(interruptRestored.get(), "close must restore the caller's interrupt flag");
        assertFalse(process.isAlive());
        assertFalse(Files.exists(profile));
    }

    private static Path profile(Path temp, String name) throws IOException {
        Path profile = Files.createDirectory(temp.resolve(name));
        Files.writeString(profile.resolve("marker"), "owned by fake Chrome");
        return profile;
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static final class DelayedSuccessfulFuture
            extends CompletableFuture<WebSocket> {
        private final WebSocket value;
        private final long delayMs;

        DelayedSuccessfulFuture(WebSocket value, long delayMs) {
            this.value = value;
            this.delayMs = delayMs;
        }

        @Override
        public WebSocket get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            long allowedNanos = unit.toNanos(timeout);
            long delayNanos = TimeUnit.MILLISECONDS.toNanos(delayMs);
            TimeUnit.NANOSECONDS.sleep(Math.min(allowedNanos, delayNanos));
            if (allowedNanos < delayNanos) { throw new TimeoutException(); }
            complete(value);
            return value;
        }
    }

    private static final class FakeWebSocket implements WebSocket {
        private Function<String, CompletableFuture<WebSocket>> onSend =
            ignored -> CompletableFuture.completedFuture(this);
        private CompletableFuture<WebSocket> closeFuture =
            CompletableFuture.completedFuture(this);
        private Runnable onClose = () -> { };
        private final AtomicInteger sendCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicInteger abortCalls = new AtomicInteger();

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            sendCalls.incrementAndGet();
            return onSend.apply(data.toString());
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            closeCalls.incrementAndGet();
            onClose.run();
            return closeFuture;
        }

        @Override
        public void request(long n) { }

        @Override
        public String getSubprotocol() { return ""; }

        @Override
        public boolean isOutputClosed() { return false; }

        @Override
        public boolean isInputClosed() { return false; }

        @Override
        public void abort() {
            abortCalls.incrementAndGet();
        }
    }

    private static final class FakeProcess extends Process {
        private final boolean exitsOnDestroy;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final ProcessHandle handle = new FakeHandle(alive);
        private final AtomicInteger destroyCalls = new AtomicInteger();
        private final AtomicInteger destroyForciblyCalls = new AtomicInteger();

        FakeProcess(boolean exitsOnDestroy) {
            this.exitsOnDestroy = exitsOnDestroy;
        }

        @Override
        public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }

        @Override
        public InputStream getInputStream() { return InputStream.nullInputStream(); }

        @Override
        public InputStream getErrorStream() { return InputStream.nullInputStream(); }

        @Override
        public int waitFor() throws InterruptedException {
            while (alive.get()) { Thread.sleep(1); }
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) { throw new IllegalThreadStateException("still alive"); }
            return 0;
        }

        @Override
        public void destroy() {
            destroyCalls.incrementAndGet();
            if (exitsOnDestroy) { alive.set(false); }
        }

        @Override
        public Process destroyForcibly() {
            destroyForciblyCalls.incrementAndGet();
            alive.set(false);
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public ProcessHandle toHandle() {
            return handle;
        }
    }

    /**
     * Transport tests are not process-wait tests: an immediately completed
     * onExit future makes the cleanup code proceed to its force fallback
     * without spending the production 3s graceful allowance.
     */
    private static final class FakeHandle implements ProcessHandle {
        private final AtomicBoolean alive;

        FakeHandle(AtomicBoolean alive) {
            this.alive = alive;
        }

        @Override
        public long pid() { return 808_080; }

        @Override
        public Optional<ProcessHandle> parent() { return Optional.empty(); }

        @Override
        public Stream<ProcessHandle> children() { return Stream.empty(); }

        @Override
        public Stream<ProcessHandle> descendants() { return Stream.empty(); }

        @Override
        public Info info() { return ProcessHandle.current().info(); }

        @Override
        public CompletableFuture<ProcessHandle> onExit() {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public boolean supportsNormalTermination() { return true; }

        @Override
        public boolean destroy() {
            alive.set(false);
            return true;
        }

        @Override
        public boolean destroyForcibly() {
            alive.set(false);
            return true;
        }

        @Override
        public boolean isAlive() { return alive.get(); }

        @Override
        public int compareTo(ProcessHandle other) {
            return Long.compare(pid(), other.pid());
        }
    }
}
