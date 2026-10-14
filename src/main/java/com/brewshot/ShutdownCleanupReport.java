package com.brewshot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * What the JVM shutdown hook found still registered after its reap passes, typed (plan f5f02722).
 *
 * <p>The hook used to print one sentence for every lease that was still registered. But a lease
 * stays registered for two unrelated reasons: a process of it is still alive, or its profile was
 * kept. The second is the ordinary state of every launch whose release is unproven, so the
 * sentence fired on every clean capture and said "may still be running" about a tree that was
 * dead. Each lease is now classified by what is OBSERVED ALIVE when the report is made, and only
 * the leases with a live process are counted as not confirmed reaped.
 *
 * <p>What is printed is a pure function of this value ({@link #stderrLines()}), so the quiet
 * case and the loud case are both testable without a browser and without reading stderr.
 *
 * @param cause   why the reap loop stopped, in the hook's own words
 * @param entries one per lease still registered, in no particular order
 */
record ShutdownCleanupReport(String cause, List<Entry> entries) {

    /** Nothing was left registered, or the caller was not the JVM shutdown hook. */
    static final ShutdownCleanupReport NONE = new ShutdownCleanupReport("", List.of());

    /** How many profile paths one stderr line names before it says "and N more". */
    static final int MAX_PROFILES_IN_A_LINE = 8;

    /**
     * One lease still registered when the report was made.
     *
     * @param outcome           what is left of it now
     * @param forciblySignalled whether a SIGKILL was actually sent to its launched tree, at
     *                          close() or by the reap pass (the flag is sticky across both)
     * @param reached           whether the reap pass got to it before the budget ran out
     */
    record Entry(CleanupOutcome outcome, boolean forciblySignalled, boolean reached) {
        Entry {
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    ShutdownCleanupReport {
        Objects.requireNonNull(cause, "cause");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    /** Leases with a process still observed alive: the only ones "not confirmed reaped". */
    List<Entry> unconfirmed() {
        return withState(CleanupOutcome.State.PROCESS_UNCONFIRMED);
    }

    /** Leases whose release was proven but whose profile is still on disk. A failure. */
    List<Entry> profilesNotRemoved() {
        return withState(CleanupOutcome.State.PROFILE_NOT_REMOVED);
    }

    /** Leases with no live process whose profile was kept by decision. Reported, not printed. */
    List<Entry> profilesKept() {
        return withState(CleanupOutcome.State.PROFILE_KEPT);
    }

    /** Unconfirmed leases a SIGKILL was sent to and that are alive anyway. */
    int killedStillAlive() {
        int count = 0;
        for (Entry entry : unconfirmed()) {
            if (entry.forciblySignalled()) { count++; }
        }
        return count;
    }

    /**
     * Unconfirmed leases the reap pass reached without a SIGKILL being sent to their launched
     * tree: the signal was refused, or the tree is dead and only an argv-matched orphan lives.
     */
    int reachedWithoutKill() {
        int count = 0;
        for (Entry entry : unconfirmed()) {
            if (!entry.forciblySignalled() && entry.reached()) { count++; }
        }
        return count;
    }

    /** Unconfirmed leases the reap pass never got to and that no SIGKILL was sent to. */
    int neverReached() {
        return unconfirmed().size() - killedStillAlive() - reachedWithoutKill();
    }

    /** Every pid still observed alive across the unconfirmed leases, ascending. */
    List<Long> livePids() {
        TreeSet<Long> pids = new TreeSet<>();
        for (Entry entry : unconfirmed()) { pids.addAll(entry.outcome().livePids()); }
        return List.copyOf(pids);
    }

    /** Live members across the unconfirmed leases whose pid could not be read. */
    int liveWithoutPid() {
        int count = 0;
        for (Entry entry : unconfirmed()) { count += entry.outcome().liveWithoutPid(); }
        return count;
    }

    /**
     * The lines the hook prints: none when every remaining lease merely kept its profile.
     *
     * <p>The first line keeps the accounting the earlier report was corrected into over several
     * reviews (which cause ended the loop; killed versus reached versus never reached) and now
     * applies it only to leases that are alive, so "may still be running" is said of nothing that
     * is dead. It then names the pids, because a count cannot be checked with {@code ps}.
     */
    List<String> stderrLines() {
        List<String> lines = new ArrayList<>();
        List<Entry> alive = unconfirmed();
        if (!alive.isEmpty()) {
            List<Long> pids = livePids();
            lines.add("brewshot: " + cause + " with " + alive.size()
                + " lease(s) not confirmed reaped"
                + " (every live lease was sent SIGTERM before waiting began; of those remaining, "
                + killedStillAlive() + " were sent SIGKILL and were still alive afterwards, "
                + reachedWithoutKill() + " were reached without a SIGKILL being sent to their"
                + " launched tree, and " + neverReached() + " were never reached. All "
                + alive.size() + " may still be running.) Still alive: "
                + CleanupOutcome.describeLive(pids, liveWithoutPid())
                + CleanupOutcome.psHint(pids));
        }
        List<Entry> stuck = profilesNotRemoved();
        if (!stuck.isEmpty()) {
            StringBuilder paths = new StringBuilder();
            int shown = Math.min(stuck.size(), MAX_PROFILES_IN_A_LINE);
            for (int i = 0; i < shown; i++) {
                if (i > 0) { paths.append(", "); }
                Path profile = stuck.get(i).outcome().profileDir();
                paths.append(profile == null ? "(unknown path)" : profile.toString());
            }
            if (stuck.size() > shown) {
                paths.append(" and ").append(stuck.size() - shown).append(" more");
            }
            lines.add("brewshot: " + stuck.size() + " profile(s) could not be removed although"
                + " their Chrome is gone and release was proven (no process is running): "
                + paths);
        }
        return lines;
    }

    private List<Entry> withState(CleanupOutcome.State state) {
        List<Entry> matching = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.outcome().state() == state) { matching.add(entry); }
        }
        return matching;
    }
}
