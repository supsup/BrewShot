package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Plan f5f02722: a cleanup outcome is a typed value, quiet when nothing is left running, never
/// quiet when something is, and never called released while anything is left behind.
///
/// The defect this replaces was a warning that fired on every clean capture. A launch whose
/// release is unproven keeps its profile by decision, so its lease is still registered at JVM
/// exit; the shutdown hook counted every registered lease as "not confirmed reaped" and, because
/// the SIGKILL flag is sticky from close(), described a dead tree as "sent SIGKILL ... may still
/// be running". The first test below is that exact shape, built without a browser.
///
/// Everything here drives the REAL lease, the REAL hook and the REAL CLI exit-code function. Only
/// the processes are fakes, and the two tests at the end use a real Chrome through the same gate
/// every other browser test uses.
final class CleanupReportingTest {

    /// The fake pids below are not this test's processes. The report asks /proc whether a live
    /// pid is really a zombie, and on a Linux host one of these numbers may belong to somebody:
    /// pin the answer so every fixture means what it says on every host.
    @BeforeEach
    void pinTheZombieProbe() {
        BrewShot.exitedButUnreapedProbeForTests = pid -> false;
    }

    @AfterEach
    void unpinTheZombieProbe() {
        BrewShot.exitedButUnreapedProbeForTests = null;
        BrewShot.orphanSweepForTests = null;
    }

    /// A process with a READABLE pid, which the fakes in AbandonedReportTruthTest do not have.
    /// Same live-process contract as the fake there: exitValue throws and the timed waitFor
    /// blocks while alive, because Process.waitFor(timeout) and onExit are built on them.
    private static final class PidProcess extends Process {
        private final long pid;
        private final boolean diesWhenKilled;
        private volatile boolean alive;
        final AtomicInteger destroyForciblyCalls = new AtomicInteger();

        PidProcess(long pid, boolean alive, boolean diesWhenKilled) {
            this.pid = pid;
            this.alive = alive;
            this.diesWhenKilled = diesWhenKilled;
        }

        @Override public long pid() { return pid; }
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

        @Override public void destroy() { }

        @Override public Process destroyForcibly() {
            destroyForciblyCalls.incrementAndGet();
            if (diesWhenKilled) { alive = false; }
            return this;
        }

        void die() { alive = false; }
    }

    private record Captured(String text, ShutdownCleanupReport typed) { }

    /// Run the real JVM-shutdown cleanup and return both what it printed and what it returned.
    private static Captured runHook() {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ShutdownCleanupReport typed;
        try {
            System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            typed = BrewShot.runJvmShutdownCleanupForTests();
        } finally {
            System.setErr(original);
        }
        return new Captured(buffer.toString(StandardCharsets.UTF_8), typed);
    }

    private static ShutdownCleanupReport.Entry entryFor(
            List<ShutdownCleanupReport.Entry> entries, Path profile) {
        for (ShutdownCleanupReport.Entry entry : entries) {
            if (profile.equals(entry.outcome().profileDir())) { return entry; }
        }
        return null;
    }

    private static void deleteTree(Path dir) {
        try {
            Files.deleteIfExists(dir);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // -- the clean run ----------------------------------------------------------------------

    @Test
    void aTreeKilledAtCloseAndKeptForAnUnprovenReleaseIsQuiet(@TempDir Path dir) throws Exception {
        // THE REPORTED SHAPE. close() sends the SIGKILL and the tree dies; release is unproven,
        // so the profile is kept and the lease stays registered; then the JVM exits.
        Path profile = Files.createDirectories(dir.resolve("brewshot-kept"));
        PidProcess chrome = new PidProcess(424201, true, true);
        AtomicBoolean releaseProven = new AtomicBoolean(false);
        BrewShot.ResourceLease lease = BrewShot.registerLaunchLeaseWithReleaseProofForTests(
            chrome, profile, CleanupReportingTest::deleteTree, releaseProven::get);
        try {
            lease.cleanup(false);

            assertTrue(chrome.destroyForciblyCalls.get() > 0,
                "fixture guard: the kill must really have been sent, or this is not the "
                    + "sticky-flag shape");
            assertTrue(lease.forciblySignalled(),
                "fixture guard: the lease must remember the SIGKILL, which is what the old "
                    + "report misread as 'killed and still alive'");
            assertFalse(chrome.isAlive(), "fixture guard: the tree must be dead");

            CleanupOutcome outcome = lease.cleanupOutcome();
            assertEquals(CleanupOutcome.State.PROFILE_KEPT, outcome.state(), outcome.toString());
            assertEquals(List.of(), outcome.livePids(), outcome.toString());
            assertFalse(outcome.liveProcessObserved(), outcome.toString());
            assertFalse(outcome.failed(), outcome.toString());
            assertNull(outcome.warning(), "a kept profile is a decision, not a warning");
            // QUIET IS NOT "RELEASED". Something was left behind, and the value says what.
            assertFalse(outcome.profileRemoved(), outcome.toString());
            assertEquals(profile, outcome.profileDir());
            assertTrue(outcome.reason().contains("kept by decision"), outcome.reason());
            assertTrue(Files.exists(profile), "the profile must really still be there");

            Captured hook = runHook();

            ShutdownCleanupReport.Entry mine = entryFor(hook.typed().profilesKept(), profile);
            assertNotNull(mine, "the kept profile must be in the typed report: " + hook.typed());
            assertTrue(mine.forciblySignalled(), hook.typed().toString());
            assertNull(entryFor(hook.typed().unconfirmed(), profile),
                "a dead tree must not be counted as not confirmed reaped: " + hook.typed());
            assertFalse(hook.text().contains("424201"),
                "a dead process must not be named as alive: " + hook.text());
            assertFalse(hook.text().contains(profile.toString()),
                "a profile kept by decision must not be printed: " + hook.text());
            // And when this lease is all there is, the hook prints nothing at all. Guarded on the
            // typed value so a lease leaked by another class cannot fail this test for it.
            if (hook.typed().unconfirmed().isEmpty()
                    && hook.typed().profilesNotRemoved().isEmpty()) {
                assertEquals("", hook.text(), "a clean run must print no lease warning");
            }
            assertEquals(0, Main.exitCodeAfterCleanup(0, outcome, System.err));
        } finally {
            // Drain: this lease can only release once its proof says yes.
            chrome.die();
            releaseProven.set(true);
            lease.cleanup(false);
        }
        CleanupOutcome released = lease.cleanupOutcome();
        assertEquals(CleanupOutcome.State.RELEASED, released.state(), released.toString());
        assertTrue(released.profileRemoved());
        assertNull(released.warning());
        assertFalse(Files.exists(profile), "released must mean the profile is gone");
    }

    // -- the forced unreaped lease ----------------------------------------------------------

    @Test
    void aForcedUnreapedLeaseNamesItsPidInTheHookReport(@TempDir Path dir) throws Exception {
        PidProcess undying = new PidProcess(424202, true, false);
        Path profile = dir.resolve("brewshot-undying");
        BrewShot.ResourceLease lease =
            BrewShot.registerContainedLaunchLeaseForTests(undying, profile, d -> { });
        Captured hook;
        try {
            hook = runHook();
        } finally {
            undying.die();
            BrewShot.runJvmShutdownCleanupForTests();
        }
        assertFalse(lease.isOwned(), "fixture guard: the drain must have released the lease");

        ShutdownCleanupReport.Entry mine = entryFor(hook.typed().unconfirmed(), profile);
        assertNotNull(mine, "a live process must be reported as unconfirmed: " + hook.typed());
        assertEquals(List.of(424202L), mine.outcome().livePids(), hook.typed().toString());
        assertEquals(CleanupOutcome.State.PROCESS_UNCONFIRMED, mine.outcome().state());
        assertTrue(mine.forciblySignalled(), "the pass did send the SIGKILL: " + hook.typed());
        assertTrue(undying.destroyForciblyCalls.get() > 0,
            "the report says a SIGKILL was sent, so one must have been");

        assertTrue(hook.typed().livePids().contains(424202L), hook.typed().toString());
        assertTrue(hook.text().contains("lease(s) not confirmed reaped"), hook.text());
        assertTrue(hook.text().contains("Still alive: pid "), hook.text());
        assertTrue(hook.text().contains("424202"),
            "the surviving pid must be printed so the caller can run ps -p: " + hook.text());
        assertTrue(hook.text().contains("(check with: ps -p "), hook.text());
    }

    @Test
    void anUnconfirmedOutcomeSetsExitSixNamesThePidAndAppearsInTheManifest(@TempDir Path dir)
            throws Exception {
        PidProcess undying = new PidProcess(424203, true, false);
        Path profile = dir.resolve("brewshot-unconfirmed");
        BrewShot.ResourceLease lease =
            BrewShot.registerContainedLaunchLeaseForTests(undying, profile, d -> { });
        CleanupOutcome outcome;
        try {
            // No cleanup pass needed: the outcome is read from what is alive now.
            outcome = lease.cleanupOutcome();
        } finally {
            undying.die();
            BrewShot.runShutdownCleanupForTests();
        }
        assertEquals(CleanupOutcome.State.PROCESS_UNCONFIRMED, outcome.state());
        assertEquals(List.of(424203L), outcome.livePids());
        assertTrue(outcome.failed());

        // EXIT CODE: 6 replaces 0, and only 0.
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8);
        assertEquals(6, Main.EXIT_CLEANUP_UNCONFIRMED);
        assertEquals(Main.EXIT_CLEANUP_UNCONFIRMED, Main.exitCodeAfterCleanup(0, outcome, err));
        String line = errors.toString(StandardCharsets.UTF_8);
        assertTrue(line.startsWith("brewshot: cleanup UNCONFIRMED: pid 424203 "), line);
        assertTrue(line.contains("(check with: ps -p 424203)"), line);
        assertTrue(line.contains(profile.toString()), line);
        assertTrue(line.contains("exit 6"), line);

        // A run that already failed keeps its own code, and is still told.
        errors.reset();
        assertEquals(4, Main.exitCodeAfterCleanup(4, outcome, err));
        String failedLine = errors.toString(StandardCharsets.UTF_8);
        assertTrue(failedLine.contains("pid 424203"), failedLine);
        assertFalse(failedLine.contains("exit 6"),
            "the line must not announce an exit code the run does not return: " + failedLine);

        // JSON: the same fact, as fields.
        Path out = dir.resolve("shot.png");
        Files.write(out, new byte[] {1, 2, 3});
        Path manifest = dir.resolve("shot.json");
        Main.writeManifest(manifest, "page.html", "file", 640, 480, 25,
            null, out, null, null, true, 12, null, null,
            null, null, null, null, null, null, null, null, outcome);
        @SuppressWarnings("unchecked")
        Map<String, Object> root =
            (Map<String, Object>) MiniJson.parse(Files.readString(manifest));
        @SuppressWarnings("unchecked")
        Map<String, Object> cleanup = (Map<String, Object>) root.get("cleanup");
        assertNotNull(cleanup, "the manifest must carry the cleanup outcome: " + root);
        assertEquals("process-unconfirmed", cleanup.get("state"));
        assertEquals(Boolean.TRUE, cleanup.get("liveProcessObserved"));
        assertEquals(Boolean.FALSE, cleanup.get("profileRemoved"));
        assertEquals(profile.toString(), cleanup.get("profile"));
        List<?> pids = (List<?>) cleanup.get("livePids");
        assertEquals(1, pids.size(), cleanup.toString());
        assertEquals(424203L, ((Number) pids.get(0)).longValue());
        assertEquals(0L, ((Number) cleanup.get("liveWithoutPid")).longValue());
        assertEquals(List.of(), cleanup.get("exitedUnreapedPids"));
        assertTrue(String.valueOf(cleanup.get("reason")).contains("alive"), cleanup.toString());
    }

    @Test
    void aManifestWrittenWithoutAnOutcomeKeepsItsEarlierShape(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("shot.png");
        Files.write(out, new byte[] {1, 2, 3});
        Path manifest = dir.resolve("legacy.json");
        Main.writeManifest(manifest, "page.html", "file", 640, 480, 25,
            null, out, null, null, true, 12, null, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> root =
            (Map<String, Object>) MiniJson.parse(Files.readString(manifest));
        assertFalse(root.containsKey("cleanup"),
            "the earlier overloads must not grow a key their callers did not ask for");
    }

    // -- the other failure: a profile that should be gone and is not -------------------------

    @Test
    void aProvenReleaseWhoseProfileSurvivesTheDeleteIsAFailureAndSaysSo(@TempDir Path dir)
            throws Exception {
        Path profile = Files.createDirectories(dir.resolve("brewshot-stuck"));
        PidProcess exited = new PidProcess(424204, false, false);
        BrewShot.ResourceLease lease =
            BrewShot.registerContainedLaunchLeaseForTests(exited, profile, d -> { });
        CleanupOutcome outcome;
        try {
            lease.cleanup(false);
            outcome = lease.cleanupOutcome();
        } finally {
            Files.deleteIfExists(profile);
            lease.cleanup(false);
        }
        assertFalse(lease.isOwned(), "fixture guard: the drain must have released the lease");

        assertEquals(CleanupOutcome.State.PROFILE_NOT_REMOVED, outcome.state());
        assertTrue(outcome.failed(), "left behind against the decision to remove it");
        assertFalse(outcome.profileRemoved());
        assertFalse(outcome.liveProcessObserved());
        assertNotNull(outcome.warning());
        assertTrue(outcome.warning().contains(profile.toString()), outcome.warning());

        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int code = Main.exitCodeAfterCleanup(
            0, outcome, new PrintStream(errors, true, StandardCharsets.UTF_8));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("cleanup incomplete"),
            errors.toString(StandardCharsets.UTF_8));
        // Exit 6 is reserved for a process that may still be running; nothing is running here.
        assertEquals(0, code);
    }

    // -- zombies ----------------------------------------------------------------------------

    @Test
    void anExitedButUnreapedProcessIsListedAndNotCalledAlive(@TempDir Path dir) throws Exception {
        // The JDK reports a zombie as alive, so cleanup keeps the profile. It runs nothing, so
        // the report must not send the caller to look for a live Chrome that is not there.
        PidProcess zombie = new PidProcess(424205, true, false);
        Path profile = dir.resolve("brewshot-zombie");
        BrewShot.ResourceLease lease =
            BrewShot.registerContainedLaunchLeaseForTests(zombie, profile, d -> { });
        CleanupOutcome asZombie;
        CleanupOutcome asLive;
        try {
            BrewShot.exitedButUnreapedProbeForTests = pid -> pid == 424205L;
            asZombie = lease.cleanupOutcome();
            BrewShot.exitedButUnreapedProbeForTests = pid -> false;
            asLive = lease.cleanupOutcome();
        } finally {
            zombie.die();
            BrewShot.runShutdownCleanupForTests();
        }
        assertEquals(CleanupOutcome.State.PROFILE_KEPT, asZombie.state(), asZombie.toString());
        assertEquals(List.of(), asZombie.livePids());
        assertEquals(List.of(424205L), asZombie.exitedUnreapedPids());
        assertNull(asZombie.warning());

        // The control: the same lease with the probe answering "no" IS unconfirmed, so the
        // quiet result above comes from the classification and not from the fixture.
        assertEquals(CleanupOutcome.State.PROCESS_UNCONFIRMED, asLive.state());
        assertEquals(List.of(424205L), asLive.livePids());
        assertEquals(List.of(), asLive.exitedUnreapedPids());
    }

    // -- the orphan arm: a dead tree with an argv-matched survivor ----------------------------

    /// A process the argv sweep matched and signalled, as the handle the sweep returns: alive
    /// until told otherwise, with a readable pid, no tree of its own. Nothing in the report
    /// signals it; the sweep already did, and this one survived that.
    private static final class LiveHandle implements ProcessHandle {
        private final long pid;
        private volatile boolean alive = true;

        LiveHandle(long pid) { this.pid = pid; }

        void die() { alive = false; }

        @Override public long pid() { return pid; }
        @Override public java.util.Optional<ProcessHandle> parent() {
            return java.util.Optional.empty();
        }
        @Override public java.util.stream.Stream<ProcessHandle> children() {
            return java.util.stream.Stream.empty();
        }
        @Override public java.util.stream.Stream<ProcessHandle> descendants() {
            return java.util.stream.Stream.empty();
        }
        @Override public ProcessHandle.Info info() {
            return new ProcessHandle.Info() {
                @Override public java.util.Optional<String> command() {
                    return java.util.Optional.empty();
                }
                @Override public java.util.Optional<String> commandLine() {
                    return java.util.Optional.empty();
                }
                @Override public java.util.Optional<String[]> arguments() {
                    return java.util.Optional.empty();
                }
                @Override public java.util.Optional<java.time.Instant> startInstant() {
                    return java.util.Optional.empty();
                }
                @Override public java.util.Optional<java.time.Duration> totalCpuDuration() {
                    return java.util.Optional.empty();
                }
                @Override public java.util.Optional<String> user() {
                    return java.util.Optional.empty();
                }
            };
        }
        @Override public java.util.concurrent.CompletableFuture<ProcessHandle> onExit() {
            return new java.util.concurrent.CompletableFuture<>();
        }
        @Override public boolean supportsNormalTermination() { return true; }
        @Override public boolean destroy() { return false; }
        @Override public boolean destroyForcibly() { return false; }
        @Override public boolean isAlive() { return alive; }
        @Override public int compareTo(ProcessHandle other) {
            return Long.compare(pid, other.pid());
        }
    }

    @Test
    void aDeadTreeWithAnArgvMatchedSurvivorIsUnconfirmedAndNamesIt(@TempDir Path dir)
            throws Exception {
        // THE ORPHAN ARM (review brewshot/740 R2). Chrome's launcher re-execs and spawns helpers;
        // one reparented before the first tree snapshot is in no handle the lease holds, and only
        // the argv sweep (keyed on --user-data-dir=<profile>) finds it. With that arm removed from
        // cleanupOutcome, a dead tree plus a live orphan read as a quiet profile-kept and exit 0,
        // which is the dangerous direction; the whole suite stayed green over that mutant.
        // The sweep itself SIGKILLs what it matches, so a real survivor of it cannot be arranged:
        // the seam stands in for the sweep and answers with one handle that is still alive.
        Path profile = Files.createDirectories(dir.resolve("brewshot-orphaned"));
        PidProcess chrome = new PidProcess(424206, false, false);   // exited before cleanup ran
        LiveHandle orphan = new LiveHandle(424207);
        List<Path> swept = new ArrayList<>();
        BrewShot.orphanSweepForTests = profileDir -> {
            swept.add(profileDir);
            return List.of(orphan);
        };
        BrewShot.ResourceLease lease = BrewShot.registerContainedLaunchLeaseForTests(
            chrome, profile, CleanupReportingTest::deleteTree);
        CleanupOutcome outcome;
        CleanupOutcome afterTheOrphanExits;
        try {
            lease.cleanup(false);
            assertEquals(List.of(profile), swept,
                "fixture guard: the sweep must have run once, for this profile");
            assertFalse(chrome.isAlive(), "fixture guard: the launched tree must be dead");
            assertTrue(lease.isOwned(), "fixture guard: a surviving orphan must keep the lease");
            assertTrue(Files.exists(profile), "fixture guard: ... and keep its profile");

            outcome = lease.cleanupOutcome();
            // Liveness is read NOW, not remembered from the pass: once the orphan is gone the same
            // lease is a kept profile whose reason says what was alive when cleanup last ran.
            orphan.die();
            afterTheOrphanExits = lease.cleanupOutcome();
        } finally {
            BrewShot.orphanSweepForTests = null;
            orphan.die();
            // Drain: the sweep runs once per lease, so this pass sees a dead tree and no orphan
            // gate, proves release (test proof) and removes the profile.
            lease.cleanup(false);
        }
        assertFalse(lease.isOwned(), "fixture guard: the drain must have released the lease");
        assertFalse(Files.exists(profile), "fixture guard: the drain must have removed the profile");

        assertEquals(CleanupOutcome.State.PROCESS_UNCONFIRMED, outcome.state(), outcome.toString());
        assertEquals(List.of(424207L), outcome.livePids(),
            "the ORPHAN's pid, not the dead launch's: " + outcome);
        assertEquals(0, outcome.liveWithoutPid(), outcome.toString());
        assertEquals(List.of(), outcome.exitedUnreapedPids(), outcome.toString());
        assertTrue(outcome.liveProcessObserved());
        assertTrue(outcome.failed());
        assertTrue(outcome.warning().contains("pid 424207"), outcome.warning());
        assertTrue(outcome.warning().contains("(check with: ps -p 424207)"), outcome.warning());
        assertFalse(outcome.warning().contains("424206"),
            "a dead process must not be named as alive: " + outcome.warning());
        assertTrue(outcome.reason().contains("still observed alive after cleanup"), outcome.reason());
        assertEquals(Main.EXIT_CLEANUP_UNCONFIRMED, Main.exitCodeAfterCleanup(
            0, outcome, new PrintStream(OutputStream.nullOutputStream())));

        assertEquals(CleanupOutcome.State.PROFILE_KEPT, afterTheOrphanExits.state(),
            afterTheOrphanExits.toString());
        assertEquals(List.of(), afterTheOrphanExits.livePids());
        assertNull(afterTheOrphanExits.warning());
        assertTrue(afterTheOrphanExits.reason().contains("still alive when cleanup last ran"),
            afterTheOrphanExits.reason());
    }

    @Test
    void theProcStatStateIsReadAfterTheLastClosingParenthesis() {
        assertTrue(BrewShot.procStatSaysZombie("4242 (chrome) Z 1 4242 4242 0 -1 4227084"));
        assertFalse(BrewShot.procStatSaysZombie("4242 (chrome) S 1 4242 4242 0 -1 4227084"));
        assertFalse(BrewShot.procStatSaysZombie("4242 (chrome) R 1 4242"));
        // A command name may contain spaces, parentheses and a decoy state letter.
        assertTrue(BrewShot.procStatSaysZombie("7 (a) Z (b) Z 1 7"));
        assertFalse(BrewShot.procStatSaysZombie("7 (a) Z (b) S 1 7"));
        assertFalse(BrewShot.procStatSaysZombie("7 (Z) S 1 7"));
        assertFalse(BrewShot.procStatSaysZombie(""));
        assertFalse(BrewShot.procStatSaysZombie("no parenthesis here Z"));
        assertFalse(BrewShot.procStatSaysZombie("7 (truncated)"));
        assertFalse(BrewShot.procStatSaysZombie("7 (truncated) "));
    }

    // -- the value itself --------------------------------------------------------------------

    @Test
    void aStateThatDisagreesWithItsEvidenceCannotBeConstructed() {
        Path profile = Path.of("brewshot-1");
        // "Released" while a pid is alive would be success reported over something left behind.
        assertThrows(IllegalArgumentException.class, () -> new CleanupOutcome(
            CleanupOutcome.State.RELEASED, List.of(7L), 0, List.of(), profile, "x"));
        assertThrows(IllegalArgumentException.class, () -> new CleanupOutcome(
            CleanupOutcome.State.PROFILE_KEPT, List.of(), 1, List.of(), profile, "x"));
        assertThrows(IllegalArgumentException.class, () -> new CleanupOutcome(
            CleanupOutcome.State.PROFILE_NOT_REMOVED, List.of(7L), 0, List.of(), profile, "x"));
        // "Unconfirmed" that names nothing would be a failure with no evidence to check.
        assertThrows(IllegalArgumentException.class, () -> new CleanupOutcome(
            CleanupOutcome.State.PROCESS_UNCONFIRMED, List.of(), 0, List.of(), profile, "x"));
        assertThrows(IllegalArgumentException.class, () -> new CleanupOutcome(
            CleanupOutcome.State.RELEASED, List.of(), -1, List.of(), profile, "x"));

        // Only a failure has a warning, and every failure has one.
        for (CleanupOutcome.State state : CleanupOutcome.State.values()) {
            boolean live = state == CleanupOutcome.State.PROCESS_UNCONFIRMED;
            CleanupOutcome outcome = new CleanupOutcome(
                state, live ? List.of(7L) : List.of(), 0, List.of(), profile, "x");
            assertEquals(outcome.failed(), outcome.warning() != null, state.toString());
            assertEquals(state == CleanupOutcome.State.RELEASED, outcome.profileRemoved(),
                state.toString());
        }
    }

    @Test
    void aLiveProcessWithNoReadablePidIsCountedInTheWarning() {
        CleanupOutcome outcome = new CleanupOutcome(CleanupOutcome.State.PROCESS_UNCONFIRMED,
            List.of(), 2, List.of(), null, "x");
        assertEquals("cleanup UNCONFIRMED: 2 process(es) with no readable pid of this launch"
            + " may still be running", outcome.warning());

        CleanupOutcome mixed = new CleanupOutcome(CleanupOutcome.State.PROCESS_UNCONFIRMED,
            List.of(12L, 34L), 1, List.of(), null, "x");
        assertEquals("cleanup UNCONFIRMED: pid 12, 34 and 1 process(es) with no readable pid"
            + " of this launch may still be running (check with: ps -p 12,34)", mixed.warning());
    }

    @Test
    void aLongPidListIsCappedAndSaysHowManyItLeftOut() {
        List<Long> many = new ArrayList<>();
        for (long pid = 1; pid <= CleanupOutcome.MAX_PIDS_IN_A_LINE + 3; pid++) { many.add(pid); }
        String described = CleanupOutcome.describeLive(many, 0);
        assertTrue(described.endsWith(", " + CleanupOutcome.MAX_PIDS_IN_A_LINE + " and 3 more"),
            described);
        assertFalse(described.contains(", " + (CleanupOutcome.MAX_PIDS_IN_A_LINE + 1)), described);
    }

    // -- the hook's report as a pure function ------------------------------------------------

    private static CleanupOutcome kept(String profile) {
        return new CleanupOutcome(CleanupOutcome.State.PROFILE_KEPT, List.of(), 0, List.of(),
            Path.of(profile), "kept by decision");
    }

    private static CleanupOutcome alive(String profile, long... pids) {
        List<Long> list = new ArrayList<>();
        for (long pid : pids) { list.add(pid); }
        return new CleanupOutcome(CleanupOutcome.State.PROCESS_UNCONFIRMED, list, 0, List.of(),
            Path.of(profile), "alive");
    }

    @Test
    void aReportOfKeptProfilesOnlyPrintsNothing() {
        ShutdownCleanupReport report = new ShutdownCleanupReport(
            "the retry passes were exhausted with budget remaining",
            List.of(
                // The sticky flag and the reached flag are both set: neither makes it loud.
                new ShutdownCleanupReport.Entry(kept("brewshot-1"), true, true),
                new ShutdownCleanupReport.Entry(kept("brewshot-2"), false, false)));
        assertEquals(List.of(), report.stderrLines());
        assertEquals(2, report.profilesKept().size());
        assertEquals(0, report.unconfirmed().size());
        assertEquals(List.of(), ShutdownCleanupReport.NONE.stderrLines());
    }

    @Test
    void aMixedReportCountsOnlyTheLiveLeasesAndNamesEveryPid() {
        ShutdownCleanupReport report = new ShutdownCleanupReport(
            "the shutdown budget expired",
            List.of(
                new ShutdownCleanupReport.Entry(kept("brewshot-1"), true, true),
                new ShutdownCleanupReport.Entry(alive("brewshot-2", 30, 10), true, true),
                new ShutdownCleanupReport.Entry(alive("brewshot-3", 20), false, true),
                new ShutdownCleanupReport.Entry(alive("brewshot-4", 40), false, false),
                new ShutdownCleanupReport.Entry(alive("brewshot-5", 50), true, false)));
        assertEquals(4, report.unconfirmed().size());
        assertEquals(2, report.killedStillAlive());
        assertEquals(1, report.reachedWithoutKill());
        assertEquals(1, report.neverReached());
        assertEquals(List.of(10L, 20L, 30L, 40L, 50L), report.livePids());

        assertEquals(List.of(
            "brewshot: the shutdown budget expired with 4 lease(s) not confirmed reaped"
                + " (every live lease was sent SIGTERM before waiting began; of those remaining, "
                + "2 were sent SIGKILL and were still alive afterwards, "
                + "1 were reached without a SIGKILL being sent to their launched tree, and "
                + "1 were never reached. All 4 may still be running.) Still alive: "
                + "pid 10, 20, 30, 40, 50 (check with: ps -p 10,20,30,40,50)"),
            report.stderrLines());
    }

    @Test
    void aProfileThatCouldNotBeRemovedGetsItsOwnLine() {
        CleanupOutcome stuck = new CleanupOutcome(CleanupOutcome.State.PROFILE_NOT_REMOVED,
            List.of(), 0, List.of(), Path.of("brewshot-9"), "still present");
        ShutdownCleanupReport report = new ShutdownCleanupReport(
            "the retry passes were exhausted with budget remaining",
            List.of(new ShutdownCleanupReport.Entry(stuck, false, true),
                new ShutdownCleanupReport.Entry(kept("brewshot-1"), false, true)));
        assertEquals(List.of(
            "brewshot: 1 profile(s) could not be removed although their Chrome is gone and"
                + " release was proven (no process is running): brewshot-9"),
            report.stderrLines());
    }

    // -- a real Chrome -----------------------------------------------------------------------

    @Test
    void aRealCleanSessionLeavesNoLiveProcessAndTheHookIsQuietAboutIt() throws Exception {
        // THE FORWARD TEST of the mechanism the fix assumes. The fakes above prove the report is
        // quiet about a dead tree; only a real Chrome can show that an ordinary close() leaves
        // the tree dead as the JDK's handles see it. If some host leaves a live handle behind
        // after close, this fails and names the pid, which is the report working.
        TestChrome.requireChromeOrLoudSkip("CleanupReportingTest");
        BrewShot shot = BrewShot.launch();
        Path profile = shot.profileDirForTests();
        CleanupOutcome outcome;
        try {
            assertEquals(CleanupOutcome.State.NOT_CLOSED, shot.cleanupOutcome().state());
            shot.html("<!doctype html><title>clean</title><p>clean run</p>");
        } finally {
            shot.close();
            outcome = shot.cleanupOutcome();
        }
        assertFalse(outcome.liveProcessObserved(),
            "an ordinary close left a process observed alive: " + outcome);
        assertFalse(outcome.failed(), outcome.toString());
        assertNull(outcome.warning(), "a clean close must owe the caller no stderr line");
        assertEquals(profile, outcome.profileDir());
        // Kept by decision on an unproven launch, removed on a proven one. Either is clean;
        // which one must match the disk.
        assertEquals(outcome.profileRemoved(),
            Files.notExists(profile, java.nio.file.LinkOption.NOFOLLOW_LINKS), outcome.toString());

        Captured hook = runHook();
        assertNull(entryFor(hook.typed().unconfirmed(), profile),
            "the hook counted a cleanly closed session as not confirmed reaped: " + hook.text());
        assertNull(entryFor(hook.typed().profilesNotRemoved(), profile), hook.text());
        assertFalse(hook.text().contains(profile.toString()), hook.text());
        if (hook.typed().unconfirmed().isEmpty() && hook.typed().profilesNotRemoved().isEmpty()) {
            assertEquals("", hook.text(), "a clean run must print no lease warning");
        }
    }

    @Test
    void aRealCleanCliRunExitsZeroPrintsNoCleanupLineAndRecordsTheOutcome(@TempDir Path dir)
            throws Exception {
        TestChrome.requireChromeOrLoudSkip("CleanupReportingTest");
        Path page = dir.resolve("page.html");
        Files.writeString(page, "<!doctype html><title>clean</title><p>clean run</p>");
        Path out = dir.resolve("page.png");
        Path manifest = dir.resolve("page.json");

        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        PrintStream original = System.err;
        int code;
        try {
            System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
            code = Main.run(new String[] {
                page.toString(), "-o", out.toString(), "--json", manifest.toString(),
                "--settle", "50"});
        } finally {
            System.setErr(original);
        }
        String text = errors.toString(StandardCharsets.UTF_8);

        assertEquals(0, code, text);
        assertTrue(text.contains("brewshot: wrote " + out), text);
        // Exact phrases, not the bare words: "release" contains "lease", and a temp path or an
        // unrelated launch line may contain either.
        assertFalse(text.contains("brewshot: cleanup "),
            "a clean run must print no cleanup line: " + text);
        assertFalse(text.contains("lease(s)"), "a clean run must print no lease warning: " + text);

        @SuppressWarnings("unchecked")
        Map<String, Object> root =
            (Map<String, Object>) MiniJson.parse(Files.readString(manifest));
        @SuppressWarnings("unchecked")
        Map<String, Object> cleanup = (Map<String, Object>) root.get("cleanup");
        assertNotNull(cleanup, "a capture manifest must always carry the cleanup outcome: " + root);
        assertEquals(Boolean.FALSE, cleanup.get("liveProcessObserved"), cleanup.toString());
        assertEquals(List.of(), cleanup.get("livePids"), cleanup.toString());
        String state = String.valueOf(cleanup.get("state"));
        assertTrue(state.equals("profile-kept") || state.equals("released"), cleanup.toString());
        assertEquals(state.equals("released"), cleanup.get("profileRemoved"), cleanup.toString());
        assertTrue(Files.size(out) > 0);
    }
}
