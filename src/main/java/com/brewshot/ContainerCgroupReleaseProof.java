package com.brewshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Release proof by CONTAINER CGROUP CONTAINMENT, created once per Chrome launch.
 *
 * <p>WHY THIS AND NOT AN ENUMERATION. No enumeration of the launched tree is a proof: on Linux
 * the two chrome_crashpad_handler processes reparent to pid 1, take their own process groups and
 * carry no profile path in argv, so descendants(), a pgid sweep and the argv sweep can all miss a
 * live member. A cgroup is different in kind: every task forked inside it stays inside it,
 * whatever it does to its parentage, session or argv.
 *
 * <p>THE PROOF. Inside a Docker container with cgroup v2 and a private cgroup namespace, the
 * container's whole cgroup is the namespace root, and {@code /sys/fs/cgroup/cgroup.procs} lists
 * every process in it, readable without privilege. This object reads that set BEFORE Chrome is
 * started (the baseline; it is computed, never assumed to be {1}) and, at release, polls the
 * same file until the set EQUALS the baseline or a bounded deadline passes. Equality means every
 * process created since the baseline, Chrome and everything it spawned, has left the cgroup.
 * Deadline expiry is UNPROVEN, and the lease keeps the profile.
 *
 * <p>Measured in the dogfood image (2026-10-09): baseline {1}; 13 members during a capture,
 * including both crashpad handlers; exactly {1} again after exit. A setsid survivor kept the
 * after-set different, which is the refusal working.
 *
 * <p>ZOMBIES DO NOT BLOCK THIS PROOF, AND IT DOES NOT REAP. cgroup v2 removes an exited task from
 * cgroup.procs when it exits, before its parent reaps it. Measured in the dogfood on 2026-10-09:
 * with the JVM as pid 1, every capture left 7 zombie children of pid 1 and none of them appeared
 * in cgroup.procs. Equality therefore says "no LIVE member beyond the baseline", which is the
 * property the profile deletion needs.
 *
 * <p>BUT ZOMBIES DO BLOCK THE LEASE GATE IN FRONT OF IT, measured with this branch's image on
 * 2026-10-09: with the JVM as pid 1, Chrome's reparented children become unreaped zombies of the
 * JVM itself, the lease's JDK handles still report them alive, the tree is "not confirmed reaped"
 * and this proof is never consulted, so the profile is retained (fail-closed, and reported through
 * {@link #releaseNotConsulted}). Under {@code docker run --init} the init process reaps them and
 * release is proven. So {@code --init} is what makes release possible, not only what stops zombie
 * accumulation in long watch runs.
 *
 * <p>FAIL-CLOSED SHAPES. Any other process that joins the cgroup during the wait (a
 * {@code docker exec}, a concurrent launch in the same JVM, a survivor) keeps the set unequal and
 * the release UNPROVEN until it leaves. That is the safe direction by construction. Residual, named
 * rather than hidden: a survivor that is assigned the pid of a baseline member that exited in the
 * meantime would make the sets compare equal. That needs the baseline member to exit (refused on
 * its own, since the set would then lack it) AND the pid allocator to wrap round to exactly that
 * number inside one capture.
 *
 * <p>The membership file is read in-process with a plain file read. It must never be read by
 * spawning a process: the reader's own child would be a member of the set it is measuring.
 */
final class ContainerCgroupReleaseProof implements BrewShot.ProcessTreeReleaseProof {

    /** Explicit opt-in. The Docker image's watch entrypoint sets it; the plain CLI does not. */
    static final String OPT_IN_ENV = "BREWSHOT_RELEASE_PROOF";
    static final String OPT_IN_VALUE = "container-cgroup";

    static final Path DEFAULT_SELF_CGROUP = Path.of("/proc/self/cgroup");
    static final Path DEFAULT_CGROUP_PROCS = Path.of("/sys/fs/cgroup/cgroup.procs");
    static final Path DEFAULT_PROC_ROOT = Path.of("/proc");
    static final long DEFAULT_POLL_MILLIS = 50;
    static final long DEFAULT_DEADLINE_MILLIS = 5_000;

    private static final String PREFIX = "brewshot: release proof (container cgroup): ";

    @FunctionalInterface
    interface FileReader {
        String read(Path path) throws IOException;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** The outcome of strict detection: usable with a computed baseline, or a named reason. */
    record Detection(Set<Long> baseline, String reason) {
        static Detection usable(Set<Long> baseline) {
            return new Detection(Collections.unmodifiableSet(new TreeSet<>(baseline)), null);
        }

        static Detection unusable(String reason) {
            return new Detection(null, Objects.requireNonNull(reason));
        }

        boolean isUsable() { return reason == null; }
    }

    private final Detection detection;
    private final Path cgroupProcs;
    private final Path procRoot;
    private final long pollMillis;
    private final long deadlineNanos;
    private final FileReader reader;
    private final LongSupplier nanoClock;
    private final Sleeper sleeper;
    private final Consumer<String> log;
    private volatile String lastOutcome = "not yet consulted";

    private ContainerCgroupReleaseProof(Detection detection, Path cgroupProcs, Path procRoot,
            long pollMillis, long deadlineMillis, FileReader reader, LongSupplier nanoClock,
            Sleeper sleeper, Consumer<String> log) {
        this.detection = detection;
        this.cgroupProcs = cgroupProcs;
        this.procRoot = procRoot;
        this.pollMillis = Math.max(1, pollMillis);
        this.deadlineNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, deadlineMillis));
        this.reader = reader;
        this.nanoClock = nanoClock;
        this.sleeper = sleeper;
        this.log = log;
    }

    /** Production: real paths, the JVM's own pid, real clock, stderr. Call BEFORE Chrome starts. */
    static ContainerCgroupReleaseProof forLaunch() {
        return create(DEFAULT_SELF_CGROUP, DEFAULT_CGROUP_PROCS, DEFAULT_PROC_ROOT,
            ProcessHandle.current().pid(), DEFAULT_POLL_MILLIS, DEFAULT_DEADLINE_MILLIS,
            ContainerCgroupReleaseProof::readFile, System::nanoTime, Thread::sleep,
            System.err::println);
    }

    /** Every input injectable. Detection and the baseline read happen HERE, at creation. */
    static ContainerCgroupReleaseProof create(Path selfCgroup, Path cgroupProcs, Path procRoot,
            long selfPid, long pollMillis, long deadlineMillis, FileReader reader,
            LongSupplier nanoClock, Sleeper sleeper, Consumer<String> log) {
        Detection detection = detect(selfCgroup, cgroupProcs, selfPid, reader);
        ContainerCgroupReleaseProof proof = new ContainerCgroupReleaseProof(detection, cgroupProcs,
            procRoot, pollMillis, deadlineMillis, reader, nanoClock, sleeper, log);
        if (detection.isUsable()) {
            log.accept(PREFIX + "usable (" + selfCgroup + " is exactly 0::/); baseline members "
                + detection.baseline() + " read from " + cgroupProcs + " before Chrome start");
        } else {
            log.accept(PREFIX + "unusable, release UNPROVEN: " + detection.reason());
        }
        return proof;
    }

    Detection detection() { return detection; }

    String lastOutcome() { return lastOutcome; }

    static Detection detect(Path selfCgroup, Path cgroupProcs, long selfPid, FileReader reader) {
        String self;
        try {
            self = reader.read(selfCgroup);
        } catch (IOException | RuntimeException e) {
            return Detection.unusable("cannot read " + selfCgroup + ": " + e);
        }
        // Shape first, so a v1 or non-namespaced host names its shape, not a missing file.
        String shape = selfCgroupShapeRefusal(self);
        if (shape != null) { return Detection.unusable(shape); }
        String procs;
        try {
            procs = reader.read(cgroupProcs);
        } catch (IOException | RuntimeException e) {
            return Detection.unusable("cannot read " + cgroupProcs + ": " + e);
        }
        return detect(self, procs, selfPid);
    }

    /** Pure detection over file contents: the unit-testable half. */
    static Detection detect(String selfCgroupContent, String cgroupProcsContent, long selfPid) {
        String shape = selfCgroupShapeRefusal(selfCgroupContent);
        if (shape != null) { return Detection.unusable(shape); }
        Set<Long> members;
        try {
            members = parseProcs(cgroupProcsContent);
        } catch (IllegalArgumentException e) {
            return Detection.unusable("cgroup.procs is unparseable: " + e.getMessage());
        }
        if (!members.contains(selfPid)) {
            return Detection.unusable("this JVM's pid " + selfPid
                + " is not in cgroup.procs " + members
                + " (the file does not describe this process's cgroup)");
        }
        return Detection.usable(members);
    }

    /** Null when /proc/self/cgroup is EXACTLY "0::/" (one trailing newline allowed). */
    static String selfCgroupShapeRefusal(String content) {
        if (content == null) { return "/proc/self/cgroup is empty"; }
        if (content.equals("0::/") || content.equals("0::/\n")) { return null; }
        String body = content.endsWith("\n") ? content.substring(0, content.length() - 1) : content;
        if (body.isEmpty()) { return "/proc/self/cgroup is empty"; }
        if (body.indexOf('\n') >= 0) {
            return "/proc/self/cgroup has more than one line (a cgroup v1 or hybrid hierarchy): "
                + quote(content);
        }
        if (!body.startsWith("0::")) {
            return "/proc/self/cgroup is not a single cgroup v2 unified line: " + quote(content);
        }
        return "/proc/self/cgroup names a non-root cgroup path " + quote(body.substring(3))
            + " (no private cgroup namespace; the membership file would not be this container's)";
    }

    /** One positive decimal pid per line; a final newline is allowed, nothing else is. */
    static Set<Long> parseProcs(String content) {
        Set<Long> members = new TreeSet<>();
        if (content == null || content.isEmpty()) { return members; }
        String body = content.endsWith("\n") ? content.substring(0, content.length() - 1) : content;
        for (String line : body.split("\n", -1)) {
            if (line.isEmpty() || line.length() > 19 || !line.chars().allMatch(Character::isDigit)) {
                throw new IllegalArgumentException("line " + quote(line) + " is not a pid");
            }
            long pid = Long.parseLong(line);
            if (pid <= 0) {
                throw new IllegalArgumentException("line " + quote(line) + " is not a positive pid");
            }
            members.add(pid);
        }
        return members;
    }

    @Override
    public boolean releaseSafe() {
        return releaseSafe(Long.MAX_VALUE);
    }

    /**
     * Bounded wait-for-equality. The wait is the SMALLER of this proof's own deadline and the
     * caller's remaining cleanup budget, so a JVM shutdown pass is never stretched past its own
     * bound by the proof; a smaller budget can only make the answer UNPROVEN sooner. At least one
     * read is always taken.
     */
    @Override
    public boolean releaseSafe(long waitBudgetNanos) {
        if (!detection.isUsable()) {
            lastOutcome = "UNPROVEN: proof unusable: " + detection.reason();
            log.accept(PREFIX + "release UNPROVEN, proof unusable: " + detection.reason());
            return false;
        }
        Set<Long> baseline = detection.baseline();
        long limitNanos = Math.min(deadlineNanos, Math.max(0, waitBudgetNanos));
        long started = nanoClock.getAsLong();
        int polls = 0;
        Set<Long> last = null;
        String lastReadFailure = null;
        while (true) {
            polls++;
            try {
                last = parseProcs(reader.read(cgroupProcs));
                lastReadFailure = null;
                if (last.equals(baseline)) {
                    long ms = elapsedMillis(started);
                    lastOutcome = "PROVEN after " + ms + " ms";
                    log.accept(PREFIX + "membership returned to baseline " + baseline
                        + " after " + ms + " ms (" + polls + " read(s)); release proven");
                    return true;
                }
            } catch (IOException | RuntimeException e) {
                last = null;
                lastReadFailure = e.toString();
            }
            long elapsed = nanoClock.getAsLong() - started;
            if (elapsed >= limitNanos) { break; }
            long sleepMillis = Math.min(pollMillis,
                Math.max(1, TimeUnit.NANOSECONDS.toMillis(limitNanos - elapsed)));
            try {
                sleeper.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastOutcome = "UNPROVEN: interrupted";
                log.accept(PREFIX + "interrupted after " + elapsedMillis(started)
                    + " ms; release UNPROVEN");
                return false;
            }
        }
        long ms = elapsedMillis(started);
        String why;
        if (last == null) {
            why = "the last read of " + cgroupProcs + " failed: " + lastReadFailure;
        } else {
            why = "extra pids " + describe(difference(last, baseline))
                + ", missing baseline pids " + difference(baseline, last);
        }
        lastOutcome = "UNPROVEN by deadline after " + ms + " ms: " + why;
        log.accept(PREFIX + "membership did not return to baseline " + baseline + " within "
            + ms + " ms (" + polls + " read(s), limit "
            + TimeUnit.NANOSECONDS.toMillis(limitNanos) + " ms); " + why
            + "; release UNPROVEN, profile retained");
        return false;
    }

    @Override
    public void releaseNotConsulted(String why) {
        lastOutcome = "NOT CONSULTED: " + why;
        log.accept(PREFIX + "not consulted, an earlier release gate refused: " + why
            + " (a zombie child of a pid-1 JVM reads as alive there; run the container with"
            + " --init); release UNPROVEN, profile retained");
    }

    private long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0, nanoClock.getAsLong() - started));
    }

    private static Set<Long> difference(Set<Long> a, Set<Long> b) {
        Set<Long> d = new TreeSet<>(a);
        d.removeAll(b);
        return d;
    }

    private String describe(Set<Long> pids) {
        List<String> parts = new ArrayList<>();
        for (long pid : pids) {
            String comm;
            try {
                comm = reader.read(procRoot.resolve(Long.toString(pid)).resolve("comm")).strip();
            } catch (IOException | RuntimeException e) {
                comm = "?";
            }
            parts.add(pid + " (" + comm + ")");
        }
        return parts.toString();
    }

    private static String quote(String s) {
        String shown = s.length() > 200 ? s.substring(0, 200) + "..." : s;
        return "'" + shown.replace("\n", "\\n") + "'";
    }

    private static String readFile(Path path) throws IOException {
        // A plain in-process read. sysfs reports size 0 or 4096; readAllBytes reads to EOF.
        return new String(Files.readAllBytes(path), StandardCharsets.US_ASCII);
    }
}
