package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pure process/profile ownership races. These invoke the same cleanup pass as
 * the JVM shutdown hook, but never discover or launch Chrome.
 */
class BrewShotLifecycleOwnershipTest {

    @Test
    void shutdownProbeProfileReflectionContractRemainsStable(@TempDir Path temp)
            throws Exception {
        FakeProcess process = new FakeProcess(1);
        Path profile = profile(temp, "probe-reflection");
        BrewShot shot = new BrewShot(
            process, profile, new FakeWebSocket(),
            new java.util.concurrent.LinkedBlockingQueue<>(), 40);
        try {
            var profileField = BrewShot.class.getDeclaredField("profileDir");
            profileField.setAccessible(true);
            assertEquals(profile, profileField.get(shot),
                "ShutdownHookProbeMain reflects this exact compatibility field");
        } finally {
            shot.close();
        }
    }

    @Test
    void shutdownDuringDiscoveryFindsThePostStartLease(@TempDir Path temp)
            throws Exception {
        FakeProcess process = new FakeProcess(1);
        Path profile = profile(temp, "discovery");
        BrewShot.ResourceLease lease =
            BrewShot.registerLaunchLease(process, profile);

        assertTrue(lease.isOwned(), "the process must be owned before stderr discovery");
        BrewShot.runShutdownCleanupForTests();

        assertFalse(process.isAlive());
        assertFalse(Files.exists(profile));
        assertFalse(lease.isOwned());
        assertFalse(BrewShot.ownsResources(process, profile));
    }

    @Test
    void shutdownDuringNeverCompletingConnectUsesNativeTimeoutAndAbortsLateSocket(
            @TempDir Path temp) throws Exception {
        FakeProcess process = new FakeProcess(1);
        Path profile = profile(temp, "connect");
        NonCancellingFuture connecting = new NonCancellingFuture();
        RecordingBuilder builder = new RecordingBuilder(connecting);
        AtomicBoolean ownedInsideConnector = new AtomicBoolean();

        IOException failure = assertThrows(IOException.class, () ->
            BrewShot.finishLaunch(process, profile,
                "ws://127.0.0.1:1/devtools/browser/fake",
                (uri, listener, timeout) -> {
                    ownedInsideConnector.set(
                        BrewShot.ownsResources(process, profile));
                    BrewShot.runShutdownCleanupForTests();
                    return BrewShot.connectWebSocket(
                        builder, uri, listener, timeout);
                },
                25));

        assertTrue(failure.getMessage().contains("connect timed out"), failure.getMessage());
        assertTrue(ownedInsideConnector.get(),
            "connect must begin with the launch lease registered");
        assertEquals(Duration.ofMillis(25), builder.connectTimeout,
            "the same budget must reach WebSocket.Builder.connectTimeout");
        assertTrue(builder.buildCalled.get());
        assertFalse(process.isAlive());
        assertFalse(Files.exists(profile));
        assertFalse(BrewShot.ownsResources(process, profile));

        FakeWebSocket lateSocket = new FakeWebSocket();
        assertTrue(connecting.complete(lateSocket));
        assertEquals(1, lateSocket.abortCalls.get(),
            "a socket completing after the caller timed out must be aborted");
    }

    @Test
    void shutdownDuringBootstrapCannotReturnAnUnownedClient(@TempDir Path temp)
            throws Exception {
        FakeProcess process = new FakeProcess(1);
        Path profile = profile(temp, "bootstrap");
        AtomicReference<BootstrapWebSocket> socketRef = new AtomicReference<>();
        AtomicBoolean ownedAtBootstrap = new AtomicBoolean();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
            BrewShot.finishLaunch(process, profile,
                "ws://127.0.0.1:1/devtools/browser/fake",
                (uri, listener, timeout) -> {
                    BootstrapWebSocket socket = new BootstrapWebSocket(
                        listener, "Target.attachToTarget", () -> {
                            ownedAtBootstrap.set(
                                BrewShot.ownsResources(process, profile));
                            BrewShot.runShutdownCleanupForTests();
                        });
                    socketRef.set(socket);
                    return CompletableFuture.completedFuture(socket);
                },
                100));

        assertTrue(failure.getMessage().contains("cleaned up while launch was in progress"),
            failure.getMessage());
        assertTrue(ownedAtBootstrap.get());
        assertFalse(process.isAlive());
        assertFalse(Files.exists(profile));
        assertFalse(BrewShot.ownsResources(process, profile));
        assertNotNull(socketRef.get());
        assertEquals(1, socketRef.get().abortCalls.get());
    }

    @Test
    void shutdownDuringGracefulCloseSerializesWithCloseAndKeepsOwnership(
            @TempDir Path temp) throws Exception {
        FakeProcess process = new FakeProcess(1);
        Path profile = profile(temp, "close");
        FakeWebSocket socket = new FakeWebSocket();
        socket.closeFuture = new CompletableFuture<>();
        BrewShot shot = new BrewShot(
            process, profile, socket, new java.util.concurrent.LinkedBlockingQueue<>(), 1_000);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();

        Thread closer = new Thread(() -> {
            try { shot.close(); }
            catch (Throwable t) { closeFailure.set(t); }
        }, "brewshot-close-shutdown-race");
        closer.start();
        assertTrue(socket.closeStarted.await(1, TimeUnit.SECONDS));
        assertTrue(BrewShot.ownsResources(process, profile),
            "sendClose must not deregister process/profile ownership");

        BrewShot.runShutdownCleanupForTests();
        assertFalse(process.isAlive());
        assertFalse(Files.exists(profile));
        assertFalse(BrewShot.ownsResources(process, profile));

        socket.closeFuture.complete(socket);
        closer.join(1_000);
        assertFalse(closer.isAlive());
        assertNull(closeFailure.get());

        shot.close();
        assertEquals(1, socket.closeCalls.get(), "the close frame remains once-only");
    }

    @Test
    void processSurvivingForcedReapRemainsOwnedUntilRetry(@TempDir Path temp)
            throws Exception {
        FakeProcess process = new FakeProcess(2);
        Path profile = profile(temp, "forced-reap");
        BrewShot.ResourceLease lease =
            BrewShot.registerLaunchLease(process, profile);

        BrewShot.runShutdownCleanupForTests();
        assertTrue(process.isAlive(), "the fake ignores the first destroyForcibly");
        assertTrue(Files.exists(profile), "a live process still owns its profile");
        assertTrue(lease.isOwned(), "failed reap must remain durably registered");

        BrewShot.runShutdownCleanupForTests();
        assertFalse(process.isAlive());
        assertFalse(Files.exists(profile));
        assertFalse(lease.isOwned());
        assertEquals(2, process.destroyForciblyCalls.get());
    }

    @Test
    void reapedParentStillSweepsDescendantsBeforeProfileRelease(@TempDir Path temp)
            throws Exception {
        FakeHandle helper = new FakeHandle(42, true, List.of());
        FakeHandle parentHandle = new FakeHandle(41, false, List.of(helper));
        FakeProcess process = new FakeProcess(1, false, parentHandle);
        Path profile = profile(temp, "reaped-parent-helper");
        AtomicBoolean sweptBeforeDelete = new AtomicBoolean();
        BrewShot.ResourceLease lease = BrewShot.registerLaunchLease(
            process, profile, path -> {
                sweptBeforeDelete.set(helper.destroyForciblyCalls.get() == 1);
                deleteProfile(path);
            });

        BrewShot.runShutdownCleanupForTests();

        assertEquals(1, helper.destroyForciblyCalls.get(),
            "a dead parent must not bypass the helper-process force sweep");
        assertTrue(sweptBeforeDelete.get(),
            "helper sweep must precede profile deletion and lease release");
        assertFalse(Files.exists(profile));
        assertFalse(lease.isOwned());
    }

    @Test
    void profileDeleteFailureRemainsOwnedUntilRetry(@TempDir Path temp)
            throws Exception {
        FakeProcess process = new FakeProcess(1);
        Path profile = profile(temp, "delete-retry");
        AtomicInteger deleteCalls = new AtomicInteger();
        CountDownLatch firstDeleteStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstDelete = new CountDownLatch(1);
        BrewShot.ResourceLease lease = BrewShot.registerLaunchLease(
            process, profile, path -> {
                if (deleteCalls.incrementAndGet() == 1) {
                    firstDeleteStarted.countDown();
                    try {
                        if (!releaseFirstDelete.await(1, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("delete-race test timed out");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    throw new IllegalStateException("injected delete failure");
                }
                deleteProfile(path);
            });
        FakeWebSocket socket = new FakeWebSocket();
        BrewShot shot = new BrewShot(
            lease, socket, new java.util.concurrent.LinkedBlockingQueue<>(), 40);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();

        Thread closer = new Thread(() -> {
            try { shot.close(); }
            catch (Throwable t) { closeFailure.set(t); }
        }, "brewshot-delete-failure-close");
        closer.start();
        assertTrue(firstDeleteStarted.await(1, TimeUnit.SECONDS));

        assertFalse(process.isAlive());
        assertTrue(Files.exists(profile));
        assertTrue(BrewShot.ownsResources(process, profile),
            "a surviving profile must keep its durable cleanup owner");

        Thread shutdown = new Thread(() -> {
            try { BrewShot.runShutdownCleanupForTests(); }
            catch (Throwable t) { shutdownFailure.set(t); }
        }, "brewshot-delete-failure-shutdown");
        shutdown.start();
        releaseFirstDelete.countDown();
        closer.join(1_000);
        shutdown.join(1_000);

        assertFalse(closer.isAlive());
        assertFalse(shutdown.isAlive());
        assertNull(closeFailure.get());
        assertNull(shutdownFailure.get());
        assertFalse(Files.exists(profile));
        assertFalse(lease.isOwned());
        assertEquals(2, deleteCalls.get());
        shot.close();
        assertEquals(1, socket.closeCalls.get());
    }

    private static Path profile(Path temp, String name) throws IOException {
        Path profile = Files.createDirectory(temp.resolve(name));
        Files.writeString(profile.resolve("marker"), "owned by fake Chrome");
        return profile;
    }

    private static void deleteProfile(Path profile) {
        try {
            Files.deleteIfExists(profile.resolve("marker"));
            Files.deleteIfExists(profile);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static class FakeWebSocket implements WebSocket {
        private CompletableFuture<WebSocket> closeFuture =
            CompletableFuture.completedFuture(this);
        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final AtomicInteger closeCalls = new AtomicInteger();
        final AtomicInteger abortCalls = new AtomicInteger();

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            return CompletableFuture.completedFuture(this);
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
            closeStarted.countDown();
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

    private static final class BootstrapWebSocket extends FakeWebSocket {
        private final WebSocket.Listener listener;
        private final String shutdownMethod;
        private final Runnable shutdown;
        private final AtomicBoolean shutdownRan = new AtomicBoolean();

        BootstrapWebSocket(WebSocket.Listener listener, String shutdownMethod,
                           Runnable shutdown) {
            this.listener = listener;
            this.shutdownMethod = shutdownMethod;
            this.shutdown = shutdown;
        }

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sent =
                (Map<String, Object>) MiniJson.parse(data.toString());
            int id = ((Number) sent.get("id")).intValue();
            String method = String.valueOf(sent.get("method"));
            if (shutdownMethod.equals(method) && shutdownRan.compareAndSet(false, true)) {
                shutdown.run();
            }

            String result = switch (method) {
                case "Target.createTarget" -> "{\"targetId\":\"target\"}";
                case "Target.attachToTarget" -> "{\"sessionId\":\"session\"}";
                default -> "{}";
            };
            listener.onText(this,
                "{\"id\":" + id + ",\"result\":" + result + "}", true);
            return CompletableFuture.completedFuture(this);
        }
    }

    private static final class NonCancellingFuture
            extends CompletableFuture<WebSocket> {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }
    }

    private static final class RecordingBuilder implements WebSocket.Builder {
        private final CompletableFuture<WebSocket> connecting;
        private final AtomicBoolean buildCalled = new AtomicBoolean();
        private Duration connectTimeout;

        RecordingBuilder(CompletableFuture<WebSocket> connecting) {
            this.connecting = connecting;
        }

        @Override
        public WebSocket.Builder header(String name, String value) {
            return this;
        }

        @Override
        public WebSocket.Builder connectTimeout(Duration timeout) {
            this.connectTimeout = timeout;
            return this;
        }

        @Override
        public WebSocket.Builder subprotocols(String mostPreferred,
                                              String... lesserPreferred) {
            return this;
        }

        @Override
        public CompletableFuture<WebSocket> buildAsync(
                URI uri, WebSocket.Listener listener) {
            buildCalled.set(true);
            return connecting;
        }
    }

    private static final class FakeProcess extends Process {
        private final int forceKillsAfter;
        private final AtomicBoolean alive;
        private final ProcessHandle handle;
        private final AtomicInteger destroyForciblyCalls = new AtomicInteger();

        FakeProcess(int forceKillsAfter) {
            this(forceKillsAfter, true, null);
        }

        FakeProcess(int forceKillsAfter, boolean initiallyAlive,
                    ProcessHandle handle) {
            this.forceKillsAfter = forceKillsAfter;
            this.alive = new AtomicBoolean(initiallyAlive);
            this.handle = handle;
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
        public void destroy() { }

        @Override
        public Process destroyForcibly() {
            if (destroyForciblyCalls.incrementAndGet() >= forceKillsAfter) {
                alive.set(false);
            }
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public ProcessHandle toHandle() {
            return handle != null ? handle : super.toHandle();
        }
    }

    private static final class FakeHandle implements ProcessHandle {
        private final long pid;
        private final AtomicBoolean alive;
        private final List<ProcessHandle> descendants;
        private final AtomicInteger destroyForciblyCalls = new AtomicInteger();

        FakeHandle(long pid, boolean initiallyAlive,
                   List<ProcessHandle> descendants) {
            this.pid = pid;
            this.alive = new AtomicBoolean(initiallyAlive);
            this.descendants = descendants;
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public Optional<ProcessHandle> parent() {
            return Optional.empty();
        }

        @Override
        public Stream<ProcessHandle> children() {
            return Stream.empty();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return descendants.stream();
        }

        @Override
        public Info info() {
            return ProcessHandle.current().info();
        }

        @Override
        public CompletableFuture<ProcessHandle> onExit() {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public boolean supportsNormalTermination() {
            return true;
        }

        @Override
        public boolean destroy() {
            alive.set(false);
            return true;
        }

        @Override
        public boolean destroyForcibly() {
            destroyForciblyCalls.incrementAndGet();
            alive.set(false);
            return true;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public int compareTo(ProcessHandle other) {
            return Long.compare(pid, other.pid());
        }
    }
}
