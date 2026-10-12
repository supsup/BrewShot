package com.brewshot;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What one launch's cleanup left behind, as a value rather than a sentence (plan f5f02722).
 *
 * <p>Before this type the only report of a cleanup was a stderr line printed by the JVM shutdown
 * hook, and that line fired on every ordinary capture: a launch whose release is unproven keeps
 * its profile by decision, so its lease is still registered at exit and the hook counted it as
 * "not confirmed reaped ... may still be running" although no process was left. A warning that
 * fires on every clean run hides the one run where a Chrome really did survive.
 *
 * <p>The four settled states separate the facts that sentence ran together:
 * <ul>
 *   <li>{@link State#RELEASED} - the process tree was reaped, release was proven and the profile
 *       is gone. Nothing is left.</li>
 *   <li>{@link State#PROFILE_KEPT} - no process of the launch is observed alive; the profile
 *       directory was SKIPPED, and {@link #reason()} says why. Quiet, because it is the documented
 *       default, but it is not {@code RELEASED} and is never reported as such.</li>
 *   <li>{@link State#PROFILE_NOT_REMOVED} - no process is observed alive and release WAS proven,
 *       yet the profile directory is still there after the delete. A failure; never quiet.</li>
 *   <li>{@link State#PROCESS_UNCONFIRMED} - at least one process of the launch is still observed
 *       alive. {@link #livePids()} names every one whose pid can be read, so the caller can run
 *       {@code ps -p}. A failure; never quiet, and the CLI gives it its own exit code.</li>
 * </ul>
 *
 * <p>"Observed" is deliberate. Liveness is read from the JDK handles the lease retained and from
 * the handles its argv sweep found; that is the same evidence cleanup itself acts on, and it is
 * not a containment proof. An empty {@link #livePids()} therefore means "nothing this launch
 * knows of is alive", which is why {@code PROFILE_KEPT} still keeps the profile.
 *
 * <p>An exited process that its parent has not reaped (a zombie) is NOT counted as alive here,
 * although the JDK reports it so and cleanup therefore keeps the profile. It runs nothing and
 * holds no file; in a container whose pid 1 reaps nothing, every capture leaves several, and
 * calling them live would turn every clean capture there into a failure. They are listed in
 * {@link #exitedUnreapedPids()} instead. Only Linux can be asked (through {@code /proc}); on
 * any other system, and whenever {@code /proc} cannot be read, a process the JDK calls alive is
 * reported alive.
 *
 * @param state              which of the cases above holds
 * @param livePids           pids still observed alive, ascending, no duplicates
 * @param liveWithoutPid     live members whose pid could not be read (never dropped silently)
 * @param exitedUnreapedPids pids that have exited and are only waiting to be reaped, ascending
 * @param profileDir         the launch's generated profile directory, or null when unknown
 * @param reason             one plain clause saying why the state is what it is
 */
public record CleanupOutcome(State state, List<Long> livePids, int liveWithoutPid,
        List<Long> exitedUnreapedPids, Path profileDir, String reason) {

    /** The case a cleanup ended in. {@link #jsonValue()} is the stable machine spelling. */
    public enum State {
        /** close() has not been called on the session, so there is nothing to report yet. */
        NOT_CLOSED("not-closed"),
        /** Process tree reaped, release proven, profile removed. */
        RELEASED("released"),
        /** No live process observed; the profile was skipped (see the reason). */
        PROFILE_KEPT("profile-kept"),
        /** No live process observed, release proven, but the profile is still present. */
        PROFILE_NOT_REMOVED("profile-not-removed"),
        /** At least one process of the launch is still observed alive. */
        PROCESS_UNCONFIRMED("process-unconfirmed");

        private final String jsonValue;

        State(String jsonValue) {
            this.jsonValue = jsonValue;
        }

        /** The spelling used in the {@code --json} manifest; never the enum constant name. */
        public String jsonValue() {
            return jsonValue;
        }
    }

    /** How many pids a single stderr line names before it says "and N more". */
    static final int MAX_PIDS_IN_A_LINE = 32;

    public CleanupOutcome {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(reason, "reason");
        livePids = List.copyOf(Objects.requireNonNull(livePids, "livePids"));
        exitedUnreapedPids =
            List.copyOf(Objects.requireNonNull(exitedUnreapedPids, "exitedUnreapedPids"));
        if (liveWithoutPid < 0) {
            throw new IllegalArgumentException("liveWithoutPid must not be negative");
        }
        // The state and the evidence must agree in BOTH directions. A "released" value carrying a
        // live pid, or an "unconfirmed" one naming nothing, would be a well-formed lie, and the
        // CLI exit code is derived from the state alone.
        boolean live = !livePids.isEmpty() || liveWithoutPid > 0;
        if (live != (state == State.PROCESS_UNCONFIRMED)) {
            throw new IllegalArgumentException("cleanup state " + state
                + " disagrees with its evidence: " + livePids.size() + " live pid(s), "
                + liveWithoutPid + " live without a pid");
        }
    }

    /** Whether any process of the launch is still observed alive. */
    public boolean liveProcessObserved() {
        return state == State.PROCESS_UNCONFIRMED;
    }

    /** Whether the profile directory was removed. True only for {@link State#RELEASED}. */
    public boolean profileRemoved() {
        return state == State.RELEASED;
    }

    /**
     * Whether something went wrong, as opposed to being skipped by decision. A failed outcome is
     * never silent: {@link #warning()} is non-null exactly when this is true.
     */
    public boolean failed() {
        return state == State.PROCESS_UNCONFIRMED || state == State.PROFILE_NOT_REMOVED;
    }

    /**
     * The one stderr line a failed outcome owes its caller, without the {@code brewshot: }
     * prefix, or null when there is nothing to say. Null for {@code RELEASED} and for
     * {@code PROFILE_KEPT}: the first left nothing, the second kept a profile by documented
     * decision and reports that in the typed fields instead of on every run's stderr.
     */
    public String warning() {
        return switch (state) {
            case PROCESS_UNCONFIRMED -> "cleanup UNCONFIRMED: " + describeLive(livePids,
                    liveWithoutPid)
                + " of this launch may still be running"
                + psHint(livePids)
                + (profileDir == null ? "" : "; profile kept: " + profileDir);
            case PROFILE_NOT_REMOVED -> "cleanup incomplete: Chrome is gone and release was"
                + " proven, but the profile directory could not be removed"
                + (profileDir == null ? "" : ": " + profileDir);
            case NOT_CLOSED, RELEASED, PROFILE_KEPT -> null;
        };
    }

    /** The manifest object. Key order is fixed; every key is always present. */
    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("state", state.jsonValue());
        json.put("liveProcessObserved", liveProcessObserved());
        json.put("livePids", livePids);
        json.put("liveWithoutPid", liveWithoutPid);
        json.put("exitedUnreapedPids", exitedUnreapedPids);
        json.put("profile", profileDir == null ? null : profileDir.toString());
        json.put("profileRemoved", profileRemoved());
        json.put("reason", reason);
        return json;
    }

    /** "pid 12, 34" / "pid 12 and 2 process(es) with no readable pid" / "3 process(es) ...". */
    static String describeLive(List<Long> pids, int withoutPid) {
        StringBuilder out = new StringBuilder();
        if (!pids.isEmpty()) {
            out.append("pid ").append(joinPids(pids, ", "));
            int unnamed = pids.size() - Math.min(pids.size(), MAX_PIDS_IN_A_LINE);
            if (unnamed > 0) { out.append(" and ").append(unnamed).append(" more"); }
            if (withoutPid > 0) {
                out.append(" and ").append(withoutPid).append(" process(es) with no readable pid");
            }
        } else {
            out.append(withoutPid).append(" process(es) with no readable pid");
        }
        return out.toString();
    }

    /** " (check with: ps -p 12,34)", or nothing when no pid can be named. */
    static String psHint(List<Long> pids) {
        if (pids.isEmpty()) { return ""; }
        return " (check with: ps -p " + joinPids(pids, ",") + ")";
    }

    private static String joinPids(List<Long> pids, String separator) {
        StringBuilder out = new StringBuilder();
        int shown = Math.min(pids.size(), MAX_PIDS_IN_A_LINE);
        for (int i = 0; i < shown; i++) {
            if (i > 0) { out.append(separator); }
            out.append(pids.get(i).longValue());
        }
        return out.toString();
    }
}
