package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The F-01 CDP inbox bound as PRODUCTION WIRES IT, not as a test can arrange it.
 *
 * <p>Why this class exists, stated plainly because it is the whole point: the existing
 * {@code BrewShotResourceCapsTest.theCloseSignalSurvivesAFullInbox} builds its own
 * queue at {@code cap + 1} and hands it to an Accumulator with {@code cap}. That proves
 * the Accumulator behaves correctly <em>given</em> a correctly-sized sink — it cannot
 * prove that {@code finishLaunch} actually chooses that size. Those are two decisions
 * in two places, and {@link BrewShot.Accumulator#signalClosed()} discards the boolean
 * from {@code offer}, so a mismatch drops the close sentinel with no exception, no log
 * line, and no failing test. A caller then blocks until its timeout instead of failing
 * fast, which is precisely the symptom the bound was added to prevent.
 *
 * <p>Proven by mutation while writing this: changing the launch-side capacity from
 * {@code inboxCap + 1} to {@code inboxCap} left all 216 existing tests green. This
 * class kills that mutant.
 *
 * <p>No Chrome is launched and no socket is opened — the WebSocketConnector seam hands
 * back the listener that production built, and the connect future never completes.
 */
class CdpInboxWiringTest {

    /**
     * Drive the real launch path far enough to capture the Accumulator it constructed.
     * The connect never completes, so launch fails on its own connect deadline; by then
     * the listener has already been built and handed to the connector.
     */
    private static BrewShot.Accumulator captureProductionAccumulator(Path profile) {
        AtomicReference<WebSocket.Listener> captured = new AtomicReference<>();
        FakeProcess process = new FakeProcess();
        assertThrows(IOException.class, () ->
            BrewShot.finishLaunch(process, profile, "ws://127.0.0.1:1/devtools/browser/fake",
                (uri, listener, timeout) -> {
                    captured.set(listener);
                    return new CompletableFuture<>();   // never completes
                },
                40));
        WebSocket.Listener listener = captured.get();
        assertNotNull(listener, "launch must have built and passed a listener");
        assertTrue(listener instanceof BrewShot.Accumulator,
            "launch must wire the bounded Accumulator, got: " + listener.getClass());
        return (BrewShot.Accumulator) listener;
    }

    @Test
    void launchWiresTheAccumulatorToASinkWithTheReservedSentinelSlot(@TempDir Path temp)
            throws Exception {
        Path profile = Files.createDirectory(temp.resolve("profile-capacity"));
        BrewShot.Accumulator accumulator = captureProductionAccumulator(profile);

        LinkedBlockingQueue<BrewShot.InboxMessage> sink = accumulator.sink();
        int cap = accumulator.inboxCap();

        // The invariant, asserted against the two values production actually chose
        // rather than two values a test picked: exactly ONE slot beyond the cap.
        assertEquals(cap + 1, sink.size() + sink.remainingCapacity(),
            "the launch-side queue must be sized cap + 1 so the close sentinel always "
                + "has a home (cap=" + cap + ")");
    }

    @Test
    void theCloseSentinelSurvivesAFullInboxOnTheProductionWiring(@TempDir Path temp)
            throws Exception {
        Path profile = Files.createDirectory(temp.resolve("profile-sentinel"));
        BrewShot.Accumulator accumulator = captureProductionAccumulator(profile);

        int cap = accumulator.inboxCap();
        LinkedBlockingQueue<BrewShot.InboxMessage> sink = accumulator.sink();

        // Saturate the regular band and push well past it, exactly as a chatty page would.
        for (int i = 0; i < cap + 50; i++) {
            accumulator.accept("m", true);
        }
        assertEquals(cap, sink.size(), "regular messages must stop at the cap");
        assertTrue(accumulator.inboxDropped() > 0, "the overflow must be counted as dropped");

        // The socket closes while the inbox is saturated.
        accumulator.onClose(null, 1000, "bye");

        assertEquals(cap + 1, sink.size(),
            "the reserved slot must admit the close sentinel even at full inbox");
        BrewShot.InboxMessage last = null;
        for (BrewShot.InboxMessage s = sink.poll(); s != null; s = sink.poll()) {
            last = s;
        }
        assertNotNull(last, "the drained sink must not be empty");
        assertTrue(last.socketClosed() && last.raw().contains("brewshotSocketClosed"),
            "a caller blocked on a saturated inbox must still receive the close signal, "
                + "got: " + last);
    }

    @Test
    void theWiredCapHonorsTheConfiguredOverride(@TempDir Path temp) throws Exception {
        // The cap production wires must be the CONFIGURED one, not a constant that
        // happens to match the default — otherwise -D tuning is silently inert.
        String key = "brewshot.maxInboxMessages";
        String prior = System.getProperty(key);
        try {
            System.setProperty(key, "7");
            Path profile = Files.createDirectory(temp.resolve("profile-override"));
            BrewShot.Accumulator accumulator = captureProductionAccumulator(profile);
            assertEquals(7, accumulator.inboxCap(),
                "the launch-side cap must read the configured property");
            LinkedBlockingQueue<BrewShot.InboxMessage> sink = accumulator.sink();
            assertEquals(8, sink.size() + sink.remainingCapacity(),
                "and the sink must still be one slot larger than that configured cap");
        } finally {
            if (prior == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, prior);
            }
        }
    }

    @Test
    void theWiredCumulativeByteBudgetRejectsBeforeTheMessageCountCap(@TempDir Path temp)
            throws Exception {
        String countKey = "brewshot.maxInboxMessages";
        String bytesKey = "brewshot.maxInboxBytes";
        String priorCount = System.getProperty(countKey);
        String priorBytes = System.getProperty(bytesKey);
        try {
            System.setProperty(countKey, "10");
            System.setProperty(bytesKey, "5");
            BrewShot.Accumulator limited = captureProductionAccumulator(
                Files.createDirectory(temp.resolve("profile-byte-cap")));

            limited.accept("éé", true); // 4 UTF-8 bytes
            limited.accept("aa", true); // prospective aggregate 6 > 5
            assertEquals(1, limited.sink().size(),
                "the byte budget must reject while the ten-message count cap still has room");
            assertEquals("éé", limited.sink().poll().raw(),
                "the earlier in-budget message is retained");
            assertEquals(1, limited.inboxDropped(), "the cumulative-byte drop is counted");

            // Equality control on fresh production wiring: 4 + 2 == 6 is admitted.
            System.setProperty(bytesKey, "6");
            BrewShot.Accumulator exact = captureProductionAccumulator(
                Files.createDirectory(temp.resolve("profile-byte-equality")));
            exact.accept("éé", true);
            exact.accept("aa", true);
            assertEquals(2, exact.sink().size(), "the cumulative byte ceiling is inclusive");
            assertEquals(0, exact.inboxDropped());
        } finally {
            restoreProperty(countKey, priorCount);
            restoreProperty(bytesKey, priorBytes);
        }
    }

    @Test
    void theConfiguredMessageCeilingCountsUtf8BytesNotUtf16Units(@TempDir Path temp)
            throws Exception {
        String key = "brewshot.maxCdpMessageBytes";
        String prior = System.getProperty(key);
        try {
            System.setProperty(key, "4");
            BrewShot.Accumulator accumulator = captureProductionAccumulator(
                Files.createDirectory(temp.resolve("profile-message-byte-cap")));

            accumulator.accept("éé", true);  // 4 UTF-8 bytes: equality admitted
            accumulator.accept("ééé", true); // 3 UTF-16 units, 6 UTF-8 bytes: refused
            assertEquals(1, accumulator.sink().size());
            assertEquals("éé", accumulator.sink().poll().raw());
            assertEquals(1, accumulator.dropped(),
                "a UTF-16-unit ceiling would incorrectly admit the six-byte control");
        } finally {
            restoreProperty(key, prior);
        }
    }

    @Test
    void maxInboxMessagesRejectsTheCapPlusOneOverflowBoundary() throws Exception {
        String key = "brewshot.maxInboxMessages";
        String prior = System.getProperty(key);
        try {
            System.setProperty(key, Integer.toString(Integer.MAX_VALUE));
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                BrewShot::maxInboxMessages);
            assertTrue(failure.getMessage().contains(key)
                    && failure.getMessage().contains(Integer.toString(Integer.MAX_VALUE - 1)),
                "the refusal names the property and safe upper bound: " + failure.getMessage());

            System.setProperty(key, Integer.toString(Integer.MAX_VALUE - 1));
            assertEquals(Integer.MAX_VALUE - 1, BrewShot.maxInboxMessages(),
                "the largest cap whose reserved-slot addition cannot overflow is accepted");
        } finally {
            restoreProperty(key, prior);
        }
    }

    @Test
    void bothConsumerPollPathsReturnCumulativeByteCapacity(@TempDir Path temp)
            throws Exception {
        // Nonblocking diagnostic/event drain.
        LinkedBlockingQueue<BrewShot.InboxMessage> drainQueue =
            new LinkedBlockingQueue<>(5);
        BrewShot.InboxBudget drainBudget = new BrewShot.InboxBudget(5);
        BrewShot.Accumulator drainAccumulator =
            new BrewShot.Accumulator(drainQueue, 100, 4, drainBudget);
        drainAccumulator.accept("{}", true);
        assertEquals(2, drainBudget.retainedBytes(), "producer reserved the exact bytes");
        try (BrewShot shot = new BrewShot(new FakeProcess(),
                Files.createDirectory(temp.resolve("profile-release-drain")),
                new SilentWebSocket(), drainQueue, 40L, drainBudget)) {
            shot.console();
            assertEquals(0, drainBudget.retainedBytes(),
                "nonblocking drain returns the dequeued message's reservation");
            drainAccumulator.accept("12345", true);
            assertEquals(1, drainQueue.size(),
                "all five bytes are reusable after the drain rather than a lifetime quota");
        }

        // Blocking command/response poll.
        LinkedBlockingQueue<BrewShot.InboxMessage> commandQueue =
            new LinkedBlockingQueue<>(5);
        BrewShot.InboxBudget commandBudget = new BrewShot.InboxBudget(1_000);
        BrewShot.Accumulator commandAccumulator =
            new BrewShot.Accumulator(commandQueue, 1_000, 4, commandBudget);
        commandAccumulator.accept("{\"id\":1,\"result\":{\"result\":{\"value\":7}}}", true);
        assertTrue(commandBudget.retainedBytes() > 0);
        try (BrewShot shot = new BrewShot(new FakeProcess(),
                Files.createDirectory(temp.resolve("profile-release-command")),
                new SilentWebSocket(), commandQueue, 40L, commandBudget)) {
            assertEquals(7.0, ((Number) shot.eval("1")).doubleValue(), 0.001);
            assertEquals(0, commandBudget.retainedBytes(),
                "command response polling returns the same shared reservation");
        }
    }

    @Test
    void splitSurrogateReservationIsCarriedExactlyThroughTheBlockingConsumer(
            @TempDir Path temp) throws Exception {
        String raw = "{\"id\":1,\"result\":{\"result\":{\"value\":\"\uD83D\uDE00\"}}}";
        int split = raw.indexOf('\uD83D') + 1;
        long exactBytes = BrewShot.utf8Length(raw);
        LinkedBlockingQueue<BrewShot.InboxMessage> queue = new LinkedBlockingQueue<>(5);
        BrewShot.InboxBudget budget = new BrewShot.InboxBudget(exactBytes);
        BrewShot.Accumulator accumulator =
            new BrewShot.Accumulator(queue, exactBytes, 4, budget);

        // This callback boundary used to reserve the two encoder replacements while
        // dequeue re-encoded the completed pair as four bytes, causing an underflow.
        accumulator.accept(raw.substring(0, split), false);
        accumulator.accept(raw.substring(split), true);
        assertEquals(exactBytes, queue.element().reservedBytes(),
            "the queued item carries the exact completed-message reservation");
        assertEquals(exactBytes, budget.retainedBytes());

        try (BrewShot shot = new BrewShot(new FakeProcess(),
                Files.createDirectory(temp.resolve("profile-split-surrogate")),
                new SilentWebSocket(), queue, 40L, budget)) {
            assertEquals("\uD83D\uDE00", shot.eval("1"));
            assertEquals(0, budget.retainedBytes(),
                "consumer releases the carried reservation without re-encoding");
        }
    }

    @Test
    void ordinaryPayloadCannotImpersonateTheTypedCloseMessage(@TempDir Path temp)
            throws Exception {
        LinkedBlockingQueue<BrewShot.InboxMessage> queue = new LinkedBlockingQueue<>(4);
        queue.add(BrewShot.InboxMessage.untracked("{\"brewshotSocketClosed\":true}"));
        queue.add(BrewShot.InboxMessage.untracked(
            "{\"id\":1,\"result\":{\"result\":{\"value\":7}}}"));
        try (BrewShot shot = new BrewShot(new FakeProcess(),
                Files.createDirectory(temp.resolve("profile-sentinel-collision")),
                new SilentWebSocket(), queue, 40L)) {
            assertEquals(7.0, ((Number) shot.eval("1")).doubleValue(), 0.001,
                "terminal state is carried by type, not spoofable payload text");
        }
    }

    @Test
    void completedLaunchSharesBudgetAndExposesBothDropCauses(@TempDir Path temp)
            throws Exception {
        String countKey = "brewshot.maxInboxMessages";
        String bytesKey = "brewshot.maxInboxBytes";
        String priorCount = System.getProperty(countKey);
        String priorBytes = System.getProperty(bytesKey);
        AtomicReference<BrewShot.Accumulator> captured = new AtomicReference<>();
        try {
            System.setProperty(countKey, "2");
            System.setProperty(bytesKey, "200");
            Path profile = Files.createDirectory(temp.resolve("profile-complete-launch"));
            FakeProcess process = new FakeProcess();
            try (BrewShot shot = BrewShot.finishLaunch(process, profile,
                    "ws://127.0.0.1:1/devtools/browser/fake",
                    (uri, listener, timeout) -> {
                        BrewShot.Accumulator accumulator = (BrewShot.Accumulator) listener;
                        captured.set(accumulator);
                        return CompletableFuture.completedFuture(
                            new BootstrapWebSocket(accumulator));
                    }, 1_000)) {
                BrewShot.Accumulator accumulator = captured.get();
                assertNotNull(accumulator);
                assertSame(accumulator.inboxBudget(), shot.inboxBudget(),
                    "the production producer and returned client share one budget");

                accumulator.accept("a".repeat(150), true); // retained
                accumulator.accept("b".repeat(100), true); // byte cap
                accumulator.accept("c", true);             // retained, count now two
                accumulator.accept("d", true);             // count cap

                assertEquals(2, shot.inboxDropped());
                assertEquals(1, shot.inboxCountDropped());
                assertEquals(1, shot.inboxByteDropped());
                assertEquals(shot.inboxDropped(),
                    shot.inboxCountDropped() + shot.inboxByteDropped());
            }
        } finally {
            restoreProperty(countKey, priorCount);
            restoreProperty(bytesKey, priorBytes);
        }
    }

    private static void restoreProperty(String key, String prior) {
        if (prior == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, prior);
        }
    }

    // ---- review brewshot/249: a CALLER must be able to observe the close ---------

    /**
     * Build a real client over a known inbox — no Chrome, no socket. This is the level
     * the review said was missing: proving the {@code Accumulator} can PLACE the poison
     * does not prove a caller can ever SEE it.
     */
    private static BrewShot clientOver(
            LinkedBlockingQueue<BrewShot.InboxMessage> inbox, Path profile) {
        // A SENDABLE stub socket, not null. With null the client cannot send at all, so
        // any failure satisfies the assertions and the test cannot tell "failed because
        // the socket closed" from "failed because there is no socket" — it passed against
        // the known-broken drain until this was fixed.
        return new BrewShot(new FakeProcess(), profile, new SilentWebSocket(), inbox, 40L);
    }

    /** Accepts sends and never replies: the only fast failure can be the closed-state latch. */
    private static class SilentWebSocket implements WebSocket {
        @Override public CompletableFuture<WebSocket> sendText(CharSequence d, boolean last) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendBinary(java.nio.ByteBuffer d, boolean last) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendPing(java.nio.ByteBuffer m) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendPong(java.nio.ByteBuffer m) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendClose(int code, String reason) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public void request(long n) { }
        @Override public String getSubprotocol() { return ""; }
        @Override public boolean isOutputClosed() { return false; }
        @Override public boolean isInputClosed() { return false; }
        @Override public void abort() { }
    }

    /** Auto-replies to the five bootstrap commands so finishLaunch returns a real client. */
    private static final class BootstrapWebSocket extends SilentWebSocket {
        private final WebSocket.Listener listener;

        BootstrapWebSocket(WebSocket.Listener listener) {
            this.listener = listener;
        }

        @Override public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sent = (Map<String, Object>) MiniJson.parse(data.toString());
            int id = ((Number) sent.get("id")).intValue();
            String result = switch (String.valueOf(sent.get("method"))) {
                case "Target.createTarget" -> "{\"targetId\":\"target\"}";
                case "Target.attachToTarget" -> "{\"sessionId\":\"session\"}";
                default -> "{}";
            };
            listener.onText(this, "{\"id\":" + id + ",\"result\":" + result + "}", true);
            return CompletableFuture.completedFuture(this);
        }
    }

    @Test
    void aNonblockingDrainMustNotSwallowTheCloseSentinel(@TempDir Path temp) throws Exception {
        // MARLOW'S REPRODUCTION (brewshot/249). drainInboxNonBlocking polled the
        // sentinel and returned, consuming the queue's only copy. It is reached from
        // console(), consoleDropped(), errors(), errorsDropped(), freshNavigation() and
        // waitForNetworkIdle() — so any of those running after close left every later
        // caller unable to learn the socket had died. The observable symptom was a full
        // command timeout with Chrome still alive, instead of an immediate closed-socket
        // failure. That is precisely the stall the reserved slot exists to prevent.
        Path profile = Files.createDirectory(temp.resolve("profile-drain"));
        LinkedBlockingQueue<BrewShot.InboxMessage> inbox = new LinkedBlockingQueue<>(8);
        BrewShot shot = clientOver(inbox, profile);

        // The Accumulator wired to this exact queue announces the close.
        new BrewShot.Accumulator(inbox, 1_000, 4).onClose(null, 1000, "bye");
        assertEquals(1, inbox.size(), "precondition: the sentinel is in the queue");

        // A nonblocking drain runs first and empties the queue.
        shot.console();
        assertEquals(0, inbox.size(), "precondition: the drain consumed the queue");

        // The caller must STILL learn the socket is closed, and must not wait it out.
        long startedAtNanos = System.nanoTime();
        IllegalStateException failure =
            assertThrows(IllegalStateException.class, () -> shot.eval("1"));
        long elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000L;

        assertTrue(failure.getMessage().toLowerCase().contains("closed")
                || failure.getMessage().toLowerCase().contains("socket"),
            "must fail with a closed-socket reason, got: " + failure.getMessage());
        assertTrue(elapsedMillis < 20L,
            "must fail FAST, not spend the command budget (took " + elapsedMillis + " ms)");
    }

    @Test
    void theTerminalStateSurvivesRepeatedDrainsByDifferentCallers(@TempDir Path temp)
            throws Exception {
        // A queue slot is consumable exactly once, so re-queueing the sentinel would
        // still lose it to the SECOND drain. Latched state is the only representation
        // that survives an arbitrary number of drains by an arbitrary number of callers.
        Path profile = Files.createDirectory(temp.resolve("profile-repeat"));
        LinkedBlockingQueue<BrewShot.InboxMessage> inbox = new LinkedBlockingQueue<>(8);
        BrewShot shot = clientOver(inbox, profile);

        new BrewShot.Accumulator(inbox, 1_000, 4).onClose(null, 1000, "bye");
        shot.console();
        shot.errors();
        shot.consoleDropped();
        shot.errorsDropped();

        // Assert the REASON and the SPEED, not merely that something threw. Under the
        // broken drain this still throws IllegalStateException — a CDP timeout — so an
        // exception-type-only assertion passes against the very bug it targets.
        long startedAtNanos = System.nanoTime();
        IllegalStateException failure =
            assertThrows(IllegalStateException.class, () -> shot.eval("1"));
        long elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000L;
        assertTrue(failure.getMessage().toLowerCase().contains("closed")
                || failure.getMessage().toLowerCase().contains("socket"),
            "four drains later the caller must still get a CLOSED-SOCKET reason, not a "
                + "timeout; got: " + failure.getMessage());
        assertTrue(elapsedMillis < 20L,
            "and must still fail fast (took " + elapsedMillis + " ms)");
    }

    /** A process that is alive until destroyed; no Chrome, no ports, no I/O. */
    private static final class FakeProcess extends Process {
        private final AtomicBoolean alive = new AtomicBoolean(true);

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { alive.set(false); return 0; }
        @Override public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException();
            }
            return 0;
        }
        @Override public void destroy() { alive.set(false); }
        @Override public Process destroyForcibly() { alive.set(false); return this; }
        @Override public boolean isAlive() { return alive.get(); }
        @Override public ProcessHandle toHandle() { return new FakeHandle(alive); }
    }

    private record FakeHandle(AtomicBoolean alive) implements ProcessHandle {
        @Override public long pid() { return 424242L; }
        @Override public Optional<ProcessHandle> parent() { return Optional.empty(); }
        @Override public java.util.stream.Stream<ProcessHandle> children() {
            return java.util.stream.Stream.empty();
        }
        @Override public java.util.stream.Stream<ProcessHandle> descendants() {
            return java.util.stream.Stream.empty();
        }
        @Override public Info info() { return null; }
        @Override public CompletableFuture<ProcessHandle> onExit() {
            return CompletableFuture.completedFuture(this);
        }
        @Override public boolean supportsNormalTermination() { return true; }
        @Override public boolean destroy() { alive.set(false); return true; }
        @Override public boolean destroyForcibly() { alive.set(false); return true; }
        @Override public boolean isAlive() { return alive.get(); }
        @Override public int compareTo(ProcessHandle other) {
            return Long.compare(pid(), other.pid());
        }
    }

    /** Unused today, kept so a future timing assertion has a single helper to use. */
    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
