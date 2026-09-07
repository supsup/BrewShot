package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The bounded shutdown must ASK every child to stop, even when it runs out of time to REAP them
/// (plan 07a0b891).
///
/// WHY THIS TEST EXISTS. `cleanupOwnedResources` shared ONE budget between signalling and waiting
/// and returned MID-ITERATION on expiry, so leases after the cut-off were never touched at all --
/// not killed and failed to die, never asked. At `SHUTDOWN_ATTEMPT_TIMEOUT_MS` 500 ms against a
/// `SHUTDOWN_CLEANUP_TIMEOUT_MS` 5 s budget that is ten attempts: with forty leases, roughly ten
/// were asked and thirty were abandoned unsignalled.
///
/// THE ITERATION ORDER IS UNSPECIFIED, AND THAT WAS UNDERSTOOD RATHER THAN TOLERATED. `LIVE` is
/// `Collections.newSetFromMap(new ConcurrentHashMap<>())`, and the shutdown snapshot is
/// `List.copyOf(LIVE)`, so which leases fell before the cut-off was a hash-bucket artifact, not
/// insertion order. That is exactly why this test asserts over ALL leases rather than over a
/// prefix: there is no defensible prefix to name.
///
/// NO PROCESS IS LAUNCHED. The plan forbids process launch and network egress; these are fake
/// `Process` objects, the pattern `BrewShotLifecycleOwnershipTest` already uses. They never die,
/// so each reap attempt burns its capped allowance and the budget expires exactly as it would
/// against real hung children.
///
/// THE ASSERTION IS ABOUT THE SIGNAL, NOT THE EXIT CODE. `cleanupOwnedResources` returns void and
/// the JVM exits the same way whether thirty children were reaped or abandoned; the exit code
/// cannot distinguish the defect from the fix, and a test that watched it would pass either way.
final class ShutdownSignalsEveryLeaseTest {

    /// Comfortably past the budget: 20 leases that never die, at ~500 ms per reap attempt against
    /// a 5 s budget, cannot all be reaped. Under the old code roughly half were never signalled.
    private static final int LEASES = 20;

    @Test
    void everyLeaseIsSignalledEvenWhenTheBudgetTruncatesTheWaiting(@TempDir Path temp)
            throws Exception {
        List<NeverDyingProcess> processes = new ArrayList<>();
        List<BrewShot.ResourceLease> leases = new ArrayList<>();
        for (int i = 0; i < LEASES; i++) {
            NeverDyingProcess process = new NeverDyingProcess();
            Path profile = Files.createDirectories(temp.resolve("profile-" + i));
            processes.add(process);
            // Deleter is a no-op: this test is about signalling, and a real recursive delete would
            // add filesystem time to a budget the test is deliberately trying to exhaust.
            leases.add(BrewShot.registerContainedLaunchLeaseForTests(process, profile, dir -> { }));
        }
        assertEquals(LEASES, leases.size(), "fixture must register every lease before shutdown");

        try {
            BrewShot.runJvmShutdownCleanupForTests();

            // THE PROPERTY. Every lease was asked to stop. Not most, and not the ones that
            // happened to sort early.
            List<Integer> unsignalled = new ArrayList<>();
            for (int i = 0; i < processes.size(); i++) {
                if (processes.get(i).destroyCalls.get() == 0) { unsignalled.add(i); }
            }
            assertEquals(List.of(), unsignalled,
                "every registered lease must be signalled before shutdown stops waiting; these "
                    + "were never asked to stop, which is the defect this plan closes");

            // THE OTHER HALF OF THE PROPERTY: the budget still truncated something. If every lease
            // were also fully reaped, the fixture would not be exercising an expired deadline at
            // all and the assertion above would be passing for the wrong reason.
            assertTrue(BrewShot.ownsResources(processes.get(0), temp.resolve("profile-0"))
                    || anyStillOwned(processes, temp),
                "the fixture must actually exhaust the budget, or it proves nothing about "
                    + "truncation");
        } finally {
            for (NeverDyingProcess process : processes) { process.alive = false; }
            BrewShot.runShutdownCleanupForTests();
        }
    }

    private static boolean anyStillOwned(List<NeverDyingProcess> processes, Path temp) {
        for (int i = 0; i < processes.size(); i++) {
            if (BrewShot.ownsResources(processes.get(i), temp.resolve("profile-" + i))) {
                return true;
            }
        }
        return false;
    }

    /// A child that ignores every signal. `toHandle()` is left to throw the JDK's default
    /// UnsupportedOperationException, which `refreshProcessTreeSnapshot` already swallows, so the
    /// lease carries no handles and the reap path reduces to destroyForcibly + a bounded wait on a
    /// process that never exits -- which is the shape of a genuinely hung child.
    private static final class NeverDyingProcess extends Process {
        /// Counted SEPARATELY: the signal pass sends destroy() (SIGTERM, "please stop"), while
        /// the reap path owns destroyForcibly(). Merging them would let a reap satisfy an
        /// assertion about signalling, which is the very confusion this plan is untangling.
        final AtomicInteger destroyCalls = new AtomicInteger();
        final AtomicInteger destroyForciblyCalls = new AtomicInteger();
        volatile boolean alive = true;

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
        @Override public Process destroyForcibly() {
            destroyForciblyCalls.incrementAndGet();
            return this;
        }
        @Override public void destroy() { destroyCalls.incrementAndGet(); }
    }
}
