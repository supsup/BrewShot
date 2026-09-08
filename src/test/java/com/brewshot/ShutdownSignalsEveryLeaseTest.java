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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
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

    /// needs-fix 591 finding 1. The signal pass unions what the lease has RECORDED with what the
    /// tree shows RIGHT NOW, and NOTHING asserted either half: dropping the live half, dropping the
    /// recorded half, and returning an empty list all stayed green at 323/0/0/0.
    ///
    /// The cause was in the fixture above, and it is worth naming precisely because the comment
    /// there presents it as a virtue. NeverDyingProcess does not override toHandle(), so
    /// Process.toHandle() throws UnsupportedOperationException, the catch swallows it, and the test
    /// never enters the method at all. My own note said that test "measures the LOCK and nothing
    /// else" -- which was exactly true, and was therefore also the gap.
    ///
    /// This fixture makes the halves DISTINGUISHABLE rather than merely present: the recorded child
    /// is recorded at registration and is then absent from the live view, so a union that consults
    /// only one source must miss one child. Two children that both appear in both halves would be
    /// satisfied by either half alone.
    @Test
    void theSignalPassAsksBothTheRecordedAndTheLiveHalfOfTheTree(@TempDir Path temp)
            throws Exception {
        CountingHandle recordedOnly = new CountingHandle(4001);
        CountingHandle liveOnly = new CountingHandle(4002);
        // Registration snapshots the tree, so this is the child the lease REMEMBERS.
        CountingHandle parent = new CountingHandle(4000, List.of(recordedOnly));
        HandleBearingProcess process = new HandleBearingProcess(parent);

        Path profile = Files.createDirectories(temp.resolve("union"));
        BrewShot.registerContainedLaunchLeaseForTests(process, profile, deleteDir());

        // The live view now shows a DIFFERENT child: the recorded one has reparented away and is
        // no longer under the parent, while this one was born since the snapshot.
        parent.replaceDescendants(List.of(liveOnly));

        try {
            BrewShot.runJvmShutdownCleanupForTests();

            assertTrue(recordedOnly.destroyCalls.get() > 0,
                "a descendant the lease RECORDED must still be signalled once it has reparented "
                    + "out of the live view -- an unsignalled reparented child is the whole reason "
                    + "the recorded half exists");
            assertTrue(liveOnly.destroyCalls.get() > 0,
                "a descendant born since the last snapshot must be signalled from the LIVE view -- "
                    + "the recorded half alone is a stale snapshot");
        } finally {
            drain(process, parent, recordedOnly, liveOnly);
        }
    }

    /// needs-fix 591 finding 2. The pass observed the live tree, SIGTERMed it, and threw the
    /// observation away, so a descendant discovered only by the signal pass was never written into
    /// the snapshot the reap force-kills from.
    ///
    /// The scenario needs the parent to DIE OF THE SIGTERM, which is what makes the loss reachable:
    /// while the parent lives, cleanup's own refresh re-observes the child and the loss is
    /// invisible. Here the parent's death empties the live view, so the child survives if and only
    /// if the signal pass recorded it.
    @Test
    void aDescendantSeenOnlyByTheSignalPassIsStillForceKilled(@TempDir Path temp) throws Exception {
        CountingHandle lateBorn = new CountingHandle(4102);
        CountingHandle parent = new CountingHandle(4100, List.of());
        // Dying clears the live view, exactly as a dead parent's descendants() would.
        HandleBearingProcess process = new HandleBearingProcess(parent, true);

        Path profile = Files.createDirectories(temp.resolve("persist"));
        BrewShot.registerContainedLaunchLeaseForTests(process, profile, deleteDir());
        assertEquals(0, lateBorn.destroyForciblyCalls.get(), "fixture: nothing killed yet");

        // Born AFTER registration, so the only pass that can ever see it is the signal pass.
        parent.replaceDescendants(List.of(lateBorn));

        try {
            BrewShot.runJvmShutdownCleanupForTests();

            assertTrue(lateBorn.destroyCalls.get() > 0,
                "fixture guard: the signal pass must actually have SEEN this child, or the "
                    + "force-kill assertion below would be testing nothing");
            assertTrue(lateBorn.destroyForciblyCalls.get() > 0,
                "a descendant discovered by the signal pass must be RECORDED, or the reap cannot "
                    + "force-kill it once the parent's death has emptied the live view -- it is "
                    + "asked to stop and then left running, which is the narrowing this closes");
        } finally {
            drain(process, parent, lateBorn);
        }
    }

    /// A real deleter, so the lease can actually reach RELEASED and leave LIVE. The no-op deleter
    /// used above leaves the profile present, the absence probe never passes, and the lease stays
    /// registered for the rest of the JVM.
    private static BrewShot.ProfileDeleter deleteDir() {
        return dir -> {
            try { Files.deleteIfExists(dir); }
            catch (java.io.IOException ignored) { }
        };
    }

    private static void drain(HandleBearingProcess process, CountingHandle... handles) {
        process.alive.set(false);
        for (CountingHandle handle : handles) { handle.alive.set(false); }
        BrewShot.runShutdownCleanupForTests();
    }

    /// Unlike NeverDyingProcess this one HAS a handle, which is the whole point: without it the
    /// tree observation is never entered.
    private static final class HandleBearingProcess extends Process {
        final AtomicBoolean alive = new AtomicBoolean(true);
        private final CountingHandle handle;
        private final boolean deathClearsTheTree;

        HandleBearingProcess(CountingHandle handle) { this(handle, false); }

        HandleBearingProcess(CountingHandle handle, boolean deathClearsTheTree) {
            this.handle = handle;
            this.deathClearsTheTree = deathClearsTheTree;
        }

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public boolean isAlive() { return alive.get(); }
        @Override public ProcessHandle toHandle() { return handle; }

        @Override public int exitValue() {
            if (alive.get()) { throw new IllegalThreadStateException(); }
            return 0;
        }

        @Override public int waitFor() throws InterruptedException {
            while (alive.get()) { Thread.sleep(5); }
            return 0;
        }

        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (alive.get() && System.nanoTime() < deadline) { Thread.sleep(5); }
            return !alive.get();
        }

        @Override public void destroy() {
            if (deathClearsTheTree) {
                alive.set(false);
                handle.alive.set(false);
                handle.replaceDescendants(List.of()); // the parent died; its tree is gone
            }
        }

        @Override public Process destroyForcibly() { destroy(); return this; }
    }

    /// Counts destroy() and destroyForcibly() SEPARATELY, for the reason the fixture above gives:
    /// merging them would let a reap satisfy an assertion about signalling.
    private static final class CountingHandle implements ProcessHandle {
        final AtomicInteger destroyCalls = new AtomicInteger();
        final AtomicInteger destroyForciblyCalls = new AtomicInteger();
        final AtomicBoolean alive = new AtomicBoolean(true);
        private final long pid;
        private final AtomicReference<List<ProcessHandle>> descendants;
        private final CompletableFuture<ProcessHandle> exited = new CompletableFuture<>();

        CountingHandle(long pid) { this(pid, List.of()); }

        CountingHandle(long pid, List<ProcessHandle> descendants) {
            this.pid = pid;
            this.descendants = new AtomicReference<>(descendants);
        }

        void replaceDescendants(List<ProcessHandle> replacement) { descendants.set(replacement); }

        @Override public long pid() { return pid; }
        @Override public Optional<ProcessHandle> parent() { return Optional.empty(); }
        @Override public Stream<ProcessHandle> children() { return descendants.get().stream(); }
        @Override public Stream<ProcessHandle> descendants() { return descendants.get().stream(); }
        @Override public Info info() { return ProcessHandle.current().info(); }
        @Override public CompletableFuture<ProcessHandle> onExit() { return exited; }
        @Override public boolean supportsNormalTermination() { return true; }
        @Override public boolean isAlive() { return alive.get(); }
        @Override public int compareTo(ProcessHandle other) { return Long.compare(pid, other.pid()); }

        // Signalled, but NOT killed: these children ignore SIGTERM, so the recorded/live
        // distinction stays observable through the whole pass.
        @Override public boolean destroy() { destroyCalls.incrementAndGet(); return true; }

        @Override public boolean destroyForcibly() {
            destroyForciblyCalls.incrementAndGet();
            return true;
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
