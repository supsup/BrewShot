package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// Controls for OwnedProcessTree, the custody helper BrewShotAuthTest uses for its child CLI and
/// that child's Chrome (plan 728a0dfd review F1). No Chrome: the surrogate is a `/bin/sh` root
/// that starts a nested `sh` which starts a `sleep`, i.e. a root with a CHILD and a GRANDCHILD
/// that never exit on their own. SIGTERM ends a plain `sh` but not what it started, so the
/// nested pair is orphaned and reparented exactly the way Chrome is when its parent JVM dies.
///
/// Every control checks liveness through handles the TEST took itself, independently of the
/// helper's own report, so a helper that misreports cannot pass.
final class OwnedProcessTreeTest {

    private static final Duration GRACE = Duration.ofSeconds(2);
    private static final Duration JOIN = Duration.ofSeconds(5);
    /// Upper bound on one whole teardown: grace + join + generous scheduling slack.
    private static final long TEARDOWN_BOUND_MS = GRACE.plus(JOIN).toMillis() + 5_000;

    /// Every handle any test here took, killed after each test so a red control never leaks.
    private final List<ProcessHandle> started = new ArrayList<>();

    @AfterEach
    void killWhatThisTestStarted() {
        for (ProcessHandle h : started) {
            h.destroyForcibly();
        }
        Thread.interrupted();
    }

    /// Root `sh` -> nested `sh` -> `sleep 300`; with `ignoreTerm` all three ignore SIGTERM (an
    /// ignored disposition is inherited across fork and exec).
    private Process hungTree(boolean ignoreTerm) throws Exception {
        String trap = ignoreTerm ? "trap '' TERM; " : "";
        Process root = new ProcessBuilder("/bin/sh", "-c",
            trap + "/bin/sh -c 'sleep 300 & wait' & wait").start();
        started.add(root.toHandle());
        return root;
    }

    /// FIXTURE GUARD: wait (bounded) until the root really has its child and grandchild, and
    /// return the test's OWN handles to them, taken before anything is signalled.
    private List<ProcessHandle> grownTree(Process root) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            List<ProcessHandle> d = root.toHandle().descendants().toList();
            if (d.size() >= 2) {
                started.addAll(d);
                return d;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("FIXTURE GUARD: the surrogate never grew a child and a "
            + "grandchild: " + root.toHandle().descendants().toList());
    }

    private static void assertAllGone(Process root, List<ProcessHandle> mine, String why) {
        assertFalse(root.isAlive(), why + ": the root is still alive");
        for (ProcessHandle h : mine) {
            assertFalse(h.isAlive(), why + ": owned descendant pid " + h.pid() + " is still alive");
        }
    }

    // Regressions caught: kill only the root (the reparented nested sh + sleep stay alive ->
    // assertAllGone red); snapshot descendants only AFTER signalling (the dead root names none,
    // same red); no join (survivors may be empty while assertAllGone runs too early -> red).
    @Test
    void aHungTreeWhoseRootObeysTermHasItsOrphanedDescendantsKilledAndJoined() throws Exception {
        Process root = hungTree(false);
        List<ProcessHandle> mine = grownTree(root);
        OwnedProcessTree tree = OwnedProcessTree.adopt(root, GRACE, JOIN);

        assertFalse(tree.waitFor(Duration.ofMillis(300)), "FIXTURE GUARD: the surrogate is hung");
        long t0 = System.nanoTime();
        OwnedProcessTree.Teardown t = tree.terminate();
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertAllGone(root, mine, t.describe());
        assertTrue(t.survivors().isEmpty(), t.describe());
        assertTrue(t.termSent(), "the root must be asked nicely first: " + t.describe());
        assertFalse(t.rootForced(), "a root that obeys SIGTERM needs no SIGKILL: " + t.describe());
        assertTrue(t.ownedSeen() >= 2, t.describe());
        // The descendants outlived their root's SIGTERM, so they can only be gone because the
        // helper force-killed them from its snapshot.
        for (ProcessHandle h : mine) {
            assertTrue(t.forced().stream().anyMatch(f -> f.startsWith("pid " + h.pid() + " ")),
                "pid " + h.pid() + " should have been force-killed: " + t.describe());
        }
        assertTrue(tookMs < TEARDOWN_BOUND_MS, "teardown not bounded: " + tookMs + "ms");
        tree.close();   // no survivors -> does not throw
    }

    // Regressions caught: drop the SIGKILL phase (everything ignores TERM -> all alive, red);
    // an unbounded grace wait (tookMs bound red, or the test hangs).
    @Test
    void aTreeThatIgnoresTermIsForceKilledWholeAfterABoundedGrace() throws Exception {
        Process root = hungTree(true);
        List<ProcessHandle> mine = grownTree(root);
        OwnedProcessTree tree = OwnedProcessTree.adopt(root, GRACE, JOIN);

        long t0 = System.nanoTime();
        OwnedProcessTree.Teardown t = tree.terminate();
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertAllGone(root, mine, t.describe());
        assertTrue(t.survivors().isEmpty(), t.describe());
        assertTrue(t.termSent() && t.rootForced(), t.describe());
        assertTrue(tookMs >= GRACE.toMillis() - 100,
            "the grace was skipped, so the root never had a chance to clean up: " + tookMs + "ms");
        assertTrue(tookMs < TEARDOWN_BOUND_MS, "teardown not bounded: " + tookMs + "ms");
    }

    // Regressions caught: signalling a root that already exited (exit code 0 -> 143/137, red);
    // counting a descendant that exited by itself as forced or surviving (red).
    @Test
    void aHealthyChildThatExitsNormallyIsLeftAloneAndReportsNoSurvivor() throws Exception {
        Process root = new ProcessBuilder("/bin/sh", "-c", "sleep 1 & wait; exit 0").start();
        started.add(root.toHandle());
        OwnedProcessTree.Teardown t;
        try (OwnedProcessTree tree = OwnedProcessTree.adopt(root, GRACE, JOIN)) {
            assertTrue(tree.waitFor(Duration.ofSeconds(20)), "FIXTURE GUARD: the child must exit");
            assertTrue(tree.ownedSoFar().size() >= 1,
                "FIXTURE GUARD: its short-lived `sleep` child was snapshotted while it ran");
            t = tree.terminate();
        }
        assertEquals(0, root.exitValue(), "the helper signalled a child that had already exited");
        assertTrue(t.exitedOnItsOwn(), t.describe());
        assertFalse(t.termSent(), t.describe());
        assertFalse(t.rootForced(), t.describe());
        assertTrue(t.forced().isEmpty(), t.describe());
        assertTrue(t.survivors().isEmpty(), t.describe());
    }

    // The real usage shape: try-with-resources around waitFor, and the waiting thread is
    // interrupted. Regressions caught: waitFor swallowing the interrupt and waiting on (the
    // InterruptedException assertion or the join bound goes red); close() skipped or cut short
    // after the interrupt (tree alive, red); close() reporting a false survivor (suppressed red).
    // Restoring a PENDING interrupt is the next control's job.
    @Test
    void anInterruptedWaitThrowsAndTheWholeTreeIsStillTornDown() throws Exception {
        Process root = hungTree(false);
        List<ProcessHandle> mine = grownTree(root);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        AtomicReference<Boolean> interruptRestored = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try (OwnedProcessTree tree = OwnedProcessTree.adopt(root, GRACE, JOIN)) {
                tree.waitFor(Duration.ofSeconds(60));
                outcome.set(new AssertionError("waitFor returned without the interrupt"));
            } catch (Throwable thrown) {
                outcome.set(thrown);
            }
            interruptRestored.set(Thread.currentThread().isInterrupted());
        }, "owned-tree-waiter");
        waiter.start();
        Thread.sleep(300);
        waiter.interrupt();
        waiter.join(TEARDOWN_BOUND_MS);
        assertFalse(waiter.isAlive(), "teardown after an interrupt was not bounded");

        assertTrue(outcome.get() instanceof InterruptedException,
            "expected the interrupt to surface from waitFor: " + outcome.get());
        assertEquals(0, outcome.get().getSuppressed().length,
            "close() reported survivors: " + List.of(outcome.get().getSuppressed()));
        assertAllGone(root, mine, "after an interrupted wait");
        assertFalse(Boolean.TRUE.equals(interruptRestored.get()),
            "waitFor already consumed the interrupt by throwing; close() must not invent one");
    }

    // An interrupt that arrives BEFORE teardown must neither skip the bounded waits nor be lost.
    // Regressions caught: bailing out on the pending interrupt (the grace assertion or the
    // liveness check goes red); dropping the interrupt status (restored red). Not caught: a busy
    // spin that still honours the deadlines (correct result, wasted CPU).
    @Test
    void aPendingInterruptDoesNotShortenTeardownAndIsRestored() throws Exception {
        Process root = hungTree(true);
        List<ProcessHandle> mine = grownTree(root);
        OwnedProcessTree tree = OwnedProcessTree.adopt(root, GRACE, JOIN);

        Thread.currentThread().interrupt();
        long t0 = System.nanoTime();
        OwnedProcessTree.Teardown t = tree.terminate();
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        boolean restored = Thread.interrupted();   // read and clear for JUnit

        assertTrue(restored, "the interrupt status was lost");
        assertTrue(t.interrupted(), t.describe());
        assertAllGone(root, mine, t.describe());
        assertTrue(t.survivors().isEmpty(), t.describe());
        assertTrue(tookMs >= GRACE.toMillis() - 100,
            "a pending interrupt cut the grace short: " + tookMs + "ms");
        assertTrue(tookMs < TEARDOWN_BOUND_MS, "teardown not bounded: " + tookMs + "ms");
    }
}
