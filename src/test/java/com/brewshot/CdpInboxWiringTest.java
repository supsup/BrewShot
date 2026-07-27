package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
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

        LinkedBlockingQueue<String> sink = accumulator.sink();
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
        LinkedBlockingQueue<String> sink = accumulator.sink();

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
        String last = null;
        for (String s = sink.poll(); s != null; s = sink.poll()) {
            last = s;
        }
        assertNotNull(last, "the drained sink must not be empty");
        assertTrue(last.contains("brewshotSocketClosed"),
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
            LinkedBlockingQueue<String> sink = accumulator.sink();
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
