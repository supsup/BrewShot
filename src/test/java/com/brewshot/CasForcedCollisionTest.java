package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Plan bc038abe: the CAS that makes the lock-free signal pass safe was unasserted.
///
/// `descendantHandles` is an AtomicReference whose writers merge through `updateAndGet`, and that
/// is the mechanism making the RESTORED persistence safe under the argument for going lock-free.
/// Replacing it with a plain get / merge / set -- precisely the lost update the AtomicReference
/// exists to prevent -- passed the entire suite. The code was correct and nothing would have
/// noticed it becoming incorrect.
///
/// THE COLLISION IS FORCED, NOT RACED, which is why this test carries no flake. `updateAndGet`
/// calls its function BEFORE the compareAndSet and re-runs it on failure, so a competing write
/// performed inside the FIRST invocation is guaranteed to land between the read and the CAS. With
/// the CAS the outer merge is re-applied to the competitor's value and BOTH survive; with a plain
/// set the outer write lands last and the competitor is LOST.
///
/// Measured standalone before it was built here: updateAndGet gave two lambda invocations and both
/// contributions; get/merge/set gave one invocation and dropped the competitor.
final class CasForcedCollisionTest {

    @Test
    void aCompetingUpdateDuringTheMergeIsNotLost(@TempDir Path dir) throws Exception {
        MutableHandle alreadyKnown = new MutableHandle(7001, List.of());
        MutableHandle competitor = new MutableHandle(7002, List.of());
        MutableHandle parent = new MutableHandle(7000, List.of(alreadyKnown));
        HandleBearingProcess process = new HandleBearingProcess(parent);

        Path profile = Files.createDirectories(dir.resolve("cas"));
        BrewShot.ResourceLease lease =
            BrewShot.registerContainedLaunchLeaseForTests(process, profile, d -> { });

        // Registration recorded alreadyKnown. Now arm the collision: on the FIRST merge, a
        // competing refresh records the competitor, landing between this merge's read and its CAS.
        AtomicBoolean armed = new AtomicBoolean(true);
        BrewShot.ResourceLease.casCollisionHookForTests = () -> {
            if (armed.compareAndSet(true, false)) {
                parent.replaceDescendants(List.of(alreadyKnown, competitor));
                lease.refreshOwnershipCheckpoint();
            }
        };
        try {
            lease.refreshOwnershipCheckpoint();   // the outer merge; the hook collides inside it
        } finally {
            BrewShot.ResourceLease.casCollisionHookForTests = null;
        }
        assertTrue(!armed.get(), "fixture guard: the collision hook must actually have fired");

        // THE LIVE VIEW GOES EMPTY. From here the competitor can only be known from what was
        // RECORDED, so signalling it proves the merge kept it rather than that the tree re-observed
        // it. Without this the assertion below would pass on a lost handle.
        parent.replaceDescendants(List.of());

        try {
            BrewShot.runJvmShutdownCleanupForTests();

            assertTrue(alreadyKnown.destroyCalls.get() > 0,
                "control: the handle recorded before the collision must still be signalled");
            assertEquals(1, competitor.destroyCalls.get() > 0 ? 1 : 0,
                "the competing update landed between the read and the CAS, so updateAndGet must "
                    + "re-apply the merge and KEEP it. A plain get/merge/set writes last and drops "
                    + "it, and the handle is then a descendant nothing ever force-kills.");
        } finally {
            alreadyKnown.die();
            competitor.die();
            parent.die();
            process.alive.set(false);
            BrewShot.runJvmShutdownCleanupForTests();
        }
    }

    private static final class MutableHandle implements ProcessHandle {
        final AtomicInteger destroyCalls = new AtomicInteger();
        private final long pid;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicReference<List<ProcessHandle>> descendants;
        private final CompletableFuture<ProcessHandle> exited = new CompletableFuture<>();

        MutableHandle(long pid, List<ProcessHandle> descendants) {
            this.pid = pid;
            this.descendants = new AtomicReference<>(descendants);
        }

        void replaceDescendants(List<ProcessHandle> next) { descendants.set(next); }
        void die() { alive.set(false); }

        @Override public long pid() { return pid; }
        @Override public Optional<ProcessHandle> parent() { return Optional.empty(); }
        @Override public Stream<ProcessHandle> children() { return descendants.get().stream(); }
        @Override public Stream<ProcessHandle> descendants() { return descendants.get().stream(); }
        @Override public Info info() { return ProcessHandle.current().info(); }
        @Override public CompletableFuture<ProcessHandle> onExit() { return exited; }
        @Override public boolean supportsNormalTermination() { return true; }
        @Override public boolean isAlive() { return alive.get(); }
        @Override public int compareTo(ProcessHandle other) { return Long.compare(pid, other.pid()); }
        @Override public boolean destroy() { destroyCalls.incrementAndGet(); return true; }
        @Override public boolean destroyForcibly() { return true; }
    }

    private static final class HandleBearingProcess extends Process {
        final AtomicBoolean alive = new AtomicBoolean(true);
        private final ProcessHandle handle;

        HandleBearingProcess(ProcessHandle handle) { this.handle = handle; }

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
        @Override public boolean waitFor(long t, TimeUnit u) throws InterruptedException {
            long deadline = System.nanoTime() + u.toNanos(t);
            while (alive.get() && System.nanoTime() < deadline) { Thread.sleep(5); }
            return !alive.get();
        }
        @Override public void destroy() { }
        @Override public Process destroyForcibly() { return this; }
    }
}
