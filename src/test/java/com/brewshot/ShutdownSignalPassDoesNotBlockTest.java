package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Plan ef90957e: the shutdown signal pass was {@code synchronized} on the SAME lease monitor
/// {@code cleanup} holds, so a lease whose close() had stalled blocked the whole pass on ENTRY --
/// and the hook creates its deadline AFTERWARDS, deliberately, so nothing bounded that wait.
///
/// The Javadoc said "signalling is a non-blocking kernel call". True of {@code handle.destroy()},
/// false of the method that had to take a lock to reach it.
final class ShutdownSignalPassDoesNotBlockTest {

    /// No launch: a fake Process, so this test measures the LOCK and nothing else.
    private static final class FakeProcess extends Process {
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { }
        @Override public boolean isAlive() { return false; }
    }

    @Test
    void theSignalPassCompletesWhileACloseHoldsTheLeaseMonitor(@TempDir Path dir) throws Exception {
        BrewShot.ResourceLease lease = BrewShot.registerContainedLaunchLeaseForTests(
            new FakeProcess(), dir.resolve("profile"));

        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // A stalled close(): cleanup() is synchronized on the lease, so holding the lease monitor
        // is exactly the state a hung teardown puts this object in.
        Thread stalledClose = new Thread(() -> {
            synchronized (lease) {
                holding.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "stalled-close");
        stalledClose.setDaemon(true);
        stalledClose.start();
        assertTrue(holding.await(5, TimeUnit.SECONDS), "the stalled close never took the monitor");

        try {
            // THE STATED BOUND. While signalOnly was synchronized this could not return until the
            // holder released, which in the real hook is unbounded because the deadline does not
            // exist yet. A generous bound is deliberate: this must fail on BLOCKING, never on a
            // slow machine.
            Thread signaller = new Thread(lease::signalOnly, "signal-pass");
            signaller.setDaemon(true);
            long start = System.nanoTime();
            signaller.start();
            signaller.join(TimeUnit.SECONDS.toMillis(5));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(!signaller.isAlive(),
                "the signal pass is still blocked after " + elapsedMs + "ms while a close() holds "
                    + "the lease monitor -- this is the defect: signalling waits on teardown, and "
                    + "the shutdown hook creates its deadline only AFTER this pass");
        } finally {
            release.countDown();
            stalledClose.join(TimeUnit.SECONDS.toMillis(5));
        }
    }
}
