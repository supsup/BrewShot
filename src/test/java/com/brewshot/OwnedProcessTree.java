package com.brewshot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/// Bounded, ownership-safe custody of a child process a test started, and of everything that
/// child started (plan 728a0dfd review F1). A test that launches a child CLI which in turn
/// launches Chrome must not end with only `destroyForcibly()` on the child: that neither waits
/// for the exit nor reaches the Chrome processes, which outlive a killed parent by being
/// reparented, after which `descendants()` of the dead root no longer names them.
///
/// The protocol:
///  1. While the root runs ({@link #waitFor}) and again before any signal, the root's
///     descendants are SNAPSHOTTED into handles the test keeps. A handle keeps its process's
///     identity (pid plus start time; the JDK refuses to signal a reused pid), so a descendant
///     stays reachable after it has been reparented.
///  2. Ask nicely: SIGTERM ({@link ProcessHandle#destroy}) to the ROOT only, so its own
///     shutdown hook closes Chrome, and wait a bounded grace for the root to exit.
///  3. Force: SIGKILL the root if still alive, then every snapshotted handle still alive, and
///     the live descendants of those (a reparented subtree), for a bounded number of rounds.
///  4. Join each handle with a bounded `onExit().get(timeout)`; whatever is still alive at the
///     deadline is a SURVIVOR, reported by pid and command, never silently dropped.
///
/// Only processes the root started are ever signalled: there is no sweep by name or by
/// pattern. An interrupt during teardown does not cut it short (every wait is bounded anyway);
/// the interrupt status is restored before {@link #terminate} returns.
final class OwnedProcessTree implements AutoCloseable {

    /// What one teardown did. `forced` lists every process that needed SIGKILL; `survivors`
    /// those still alive after the join deadline. Only survivors fail {@link #close}.
    record Teardown(boolean exitedOnItsOwn, boolean termSent, boolean rootForced,
            List<String> forced, List<String> survivors, int ownedSeen, boolean interrupted) {
        String describe() {
            return "exitedOnItsOwn=" + exitedOnItsOwn + " termSent=" + termSent
                + " rootForced=" + rootForced + " owned=" + ownedSeen + " forced=" + forced
                + " survivors=" + survivors + " interrupted=" + interrupted;
        }
    }

    private static final long POLL_MS = 100;
    private static final int FORCE_ROUNDS = 4;

    private final ProcessHandle root;
    private final Duration grace;
    private final Duration join;
    /// Insertion-ordered, keyed by pid@start so a reused pid is a different entry.
    private final Map<String, ProcessHandle> owned = new LinkedHashMap<>();
    private Teardown teardown;

    private OwnedProcessTree(ProcessHandle root, Duration grace, Duration join) {
        this.root = root;
        this.grace = grace;
        this.join = join;
        record();
    }

    /// Take custody of a just-started child. `grace` bounds the SIGTERM wait, `join` the wait
    /// for every process to be gone after SIGKILL.
    static OwnedProcessTree adopt(Process child, Duration grace, Duration join) {
        return new OwnedProcessTree(child.toHandle(), grace, join);
    }

    /// Wait up to `timeout` for the root to exit on its own, snapshotting its descendants every
    /// `POLL_MS` (100 ms) meanwhile so short-lived and later-reparented ones are held too.
    boolean waitFor(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            record();
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return !root.isAlive();
            }
            try {
                root.onExit().get(Math.min(TimeUnit.MILLISECONDS.toNanos(POLL_MS), left),
                    TimeUnit.NANOSECONDS);
                record();
                return true;
            } catch (TimeoutException slice) {
                // keep polling
            } catch (ExecutionException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    /// The handles snapshotted so far (not including the root). For tests of this helper.
    synchronized List<ProcessHandle> ownedSoFar() {
        return List.copyOf(owned.values());
    }

    /// Run the protocol once; later calls return the first result.
    synchronized Teardown terminate() {
        if (teardown != null) {
            return teardown;
        }
        boolean interrupted = Thread.interrupted();   // cleared so bounded waits really wait
        record();
        boolean exitedOnItsOwn = !root.isAlive();
        boolean termSent = false;
        if (!exitedOnItsOwn) {
            termSent = root.destroy();
            long graceDeadline = System.nanoTime() + grace.toNanos();
            while (root.isAlive() && System.nanoTime() < graceDeadline) {
                record();
                long slice = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(POLL_MS);
                interrupted |= awaitExit(root, Math.min(graceDeadline, slice));
            }
            record();
        }

        List<String> forced = new ArrayList<>();
        boolean rootForced = false;
        if (root.isAlive()) {
            rootForced = true;
            forced.add(describe(root) + (root.destroyForcibly() ? "" : " (SIGKILL refused)"));
        }
        Set<String> signalled = new HashSet<>();
        for (int round = 0; round < FORCE_ROUNDS; round++) {
            boolean grew = false;
            for (ProcessHandle h : List.copyOf(owned.values())) {
                if (h.isAlive()) {
                    for (ProcessHandle d : h.descendants().toList()) {
                        grew |= add(d);
                    }
                }
            }
            for (Map.Entry<String, ProcessHandle> e : List.copyOf(owned.entrySet())) {
                ProcessHandle h = e.getValue();
                if (h.isAlive() && signalled.add(e.getKey())) {
                    forced.add(describe(h) + (h.destroyForcibly() ? "" : " (SIGKILL refused)"));
                }
            }
            if (!grew) {
                break;
            }
        }

        long joinDeadline = System.nanoTime() + join.toNanos();
        List<ProcessHandle> all = new ArrayList<>();
        all.add(root);
        all.addAll(owned.values());
        List<String> survivors = new ArrayList<>();
        for (ProcessHandle h : all) {
            interrupted |= awaitExit(h, joinDeadline);
            if (h.isAlive()) {
                survivors.add(describe(h));
            }
        }
        teardown = new Teardown(exitedOnItsOwn, termSent, rootForced, List.copyOf(forced),
            List.copyOf(survivors), owned.size(), interrupted);
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return teardown;
    }

    /// Tear down and FAIL, naming each survivor, if anything owned is still alive. Under
    /// try-with-resources a failure from the body keeps priority and this one is suppressed.
    @Override
    public void close() {
        Teardown t = terminate();
        if (!t.survivors().isEmpty()) {
            throw new AssertionError("owned processes still alive after bounded teardown: "
                + t.survivors() + " (" + t.describe() + ")");
        }
    }

    private synchronized void record() {
        if (root.isAlive()) {
            for (ProcessHandle d : root.descendants().toList()) {
                add(d);
            }
        }
    }

    private boolean add(ProcessHandle h) {
        String key = h.pid() + "@" + h.info().startInstant().map(Object::toString).orElse("?");
        return owned.putIfAbsent(key, h) == null;
    }

    /// Bounded wait for `h` to exit, until `deadlineNanos`. Returns whether an interrupt was
    /// swallowed (the caller restores it); an interrupt never ends the wait early.
    private static boolean awaitExit(ProcessHandle h, long deadlineNanos) {
        boolean interrupted = false;
        while (h.isAlive()) {
            long left = deadlineNanos - System.nanoTime();
            if (left <= 0) {
                break;
            }
            try {
                h.onExit().get(left, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                interrupted = true;   // flag now clear, so the retry blocks instead of spinning
            } catch (TimeoutException e) {
                break;
            } catch (ExecutionException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        return interrupted;
    }

    private static String describe(ProcessHandle h) {
        ProcessHandle.Info info = h.info();
        return "pid " + h.pid() + " "
            + info.commandLine().or(info::command).orElse("<command unreadable>");
    }
}
