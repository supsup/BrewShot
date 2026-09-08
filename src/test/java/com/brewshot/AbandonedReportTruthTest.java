package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Plan e9a58b2b: reportAbandoned printed ONE sentence from TWO call sites and could not tell them
/// apart. It announced "the shutdown budget expired" on the passes-exhausted path where the budget
/// had time left, and it asserted the leases were NOT forcibly killed while the pass it reports on
/// reaches terminateProcess with gracefulFirst=false, whose forcible branch is gated only by the
/// deadline.
///
/// These drive the REAL hook and read the REAL stderr. A test of the sentence BUILDER alone would
/// not notice both call sites passing the same cause -- the sentence would stay well-formed and
/// stay wrong. And the counts are read POSITIONALLY, because the phrases around them are satisfied
/// by any number: an earlier version of this test asserted the phrases and let two mutants through.
final class AbandonedReportTruthTest {

    /// SHUTDOWN_CLEANUP_TIMEOUT_MS, mirrored so the clock assertions name the budget they test.
    private static final long BUDGET_MILLIS = 5_000L;

    /// Correct Process contract for a live process: exitValue THROWS, and the timed waitFor blocks.
    /// Both matter -- Process.waitFor(timeout) is built on that throw, so a fake that returns a
    /// value instead makes every cleanup attempt return instantly and the budget never runs down.
    private static final class UndyingProcess extends Process {
        private volatile boolean alive;
        final java.util.concurrent.atomic.AtomicInteger destroyForciblyCalls =
            new java.util.concurrent.atomic.AtomicInteger();

        UndyingProcess() { this(true); }

        UndyingProcess(boolean initiallyAlive) { this.alive = initiallyAlive; }

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
            return this;
        }

        void die() { alive = false; }
    }

    /// The three counts the sentence commits to, read by POSITION rather than by phrase.
    private record Report(String text, int remaining, int killed, int reachedNotSignalled,
            int neverReached, long elapsedMillis) { }

    private static final Pattern COUNTS = Pattern.compile(
        "with (\\d+) lease\\(s\\) not confirmed reaped.*?of those remaining, (\\d+) were sent"
        + " SIGKILL by this pass and were still alive after it, (\\d+) were reached but needed no"
        + " kill.*?and (\\d+) were never reached",
        Pattern.DOTALL);

    private static Report runShutdownAndParse(List<UndyingProcess> live) {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        String text;
        long elapsed;
        long began = System.nanoTime();
        try {
            System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            BrewShot.runJvmShutdownCleanupForTests();
        } finally {
            elapsed = (System.nanoTime() - began) / 1_000_000L;
            System.setErr(original);
            text = buffer.toString(StandardCharsets.UTF_8);
            for (UndyingProcess p : live) { p.die(); }
            BrewShot.runJvmShutdownCleanupForTests();
        }
        Matcher m = COUNTS.matcher(text);
        assertTrue(m.find(), "the abandoned report did not fire, or its shape changed: " + text);
        Report r = new Report(text,
            Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
            Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), elapsed);
        assertEquals(r.remaining(), r.killed() + r.reachedNotSignalled() + r.neverReached(),
            "the three-way split must account for every remaining lease: " + text);
        return r;
    }

    private static List<UndyingProcess> registerUndying(int count, Path dir) {
        List<UndyingProcess> live = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UndyingProcess p = new UndyingProcess();
            BrewShot.registerContainedLaunchLeaseForTests(p, dir.resolve("profile" + i), d -> { });
            live.add(p);
        }
        return live;
    }

    /// THE CAUSE IS ASSERTED BY WALL CLOCK, not by the spelling of the sentence (needs-fix 605).
    ///
    /// Asserting only the phrase would pass on a report that names the right cause for the wrong
    /// reason, which is exactly what regression 1 was: the loop's for-condition has TWO exits and
    /// both fell through to PASSES_EXHAUSTED, so a budget that ran out at a pass boundary reported
    /// "with budget remaining" after spending the whole 5000ms. A clock cannot be talked into that.
    private static void assertBudgetWasNotExhausted(Report r) {
        assertTrue(r.elapsedMillis() < BUDGET_MILLIS,
            "this path claims budget REMAINING, so the run must have finished inside the "
                + BUDGET_MILLIS + "ms budget; it took " + r.elapsedMillis() + "ms: " + r.text());
    }

    private static void assertBudgetWasExhausted(Report r) {
        assertTrue(r.elapsedMillis() >= BUDGET_MILLIS,
            "this path claims the budget EXPIRED, so the run must have consumed the whole "
                + BUDGET_MILLIS + "ms; it took " + r.elapsedMillis() + "ms: " + r.text());
    }

    @Test
    void passesExhaustedSaysSoAndClaimsTheKillItActuallySent(@TempDir Path dir) throws Exception {
        List<UndyingProcess> live = registerUndying(1, dir);
        Report r = runShutdownAndParse(live);

        assertTrue(r.text().contains("retry passes were exhausted with budget remaining"),
            "must name the cause it actually had: " + r.text());
        assertBudgetWasNotExhausted(r);

        assertEquals(1, r.remaining(), r.text());
        assertEquals(1, r.killed(), "a lease the pass SIGKILLed must be counted as killed: " + r.text());
        assertEquals(0, r.reachedNotSignalled(), r.text());
        assertEquals(0, r.neverReached(), r.text());

        // THE KILL ITSELF, not the sentence about it. Regression 2 was a report claiming SIGKILL
        // with destroyForcibly calls of zero, and nothing here could see the difference.
        assertTrue(live.get(0).destroyForciblyCalls.get() > 0,
            "the report claims a SIGKILL, so destroyForcibly must actually have been invoked");

        // The running clause repeats the counts; pin them there too.
        assertTrue(r.text().contains("The 1 killed and the 0 unreached may still be running; "
                + "the 0 already-dead are not."),
            "the running claim must carry the same counts as the split: " + r.text());
    }

    @Test
    void budgetExpiredSaysSoAndSeparatesReachedFromNeverReached(@TempDir Path dir) throws Exception {
        List<UndyingProcess> live = registerUndying(16, dir);
        Report r = runShutdownAndParse(live);

        assertTrue(r.text().contains("shutdown budget expired"),
            "must name the cause it actually had: " + r.text());
        assertBudgetWasExhausted(r);

        assertTrue(r.killed() > 0,
            "leases the pass reached and signalled must be counted as killed: " + r.text());
        assertTrue(r.neverReached() > 0,
            "the budget expired mid-pass, so some lease was never reached: " + r.text());

        int actuallySignalled = 0;
        for (UndyingProcess p : live) {
            if (p.destroyForciblyCalls.get() > 0) { actuallySignalled++; }
        }
        assertEquals(r.killed(), actuallySignalled,
            "the killed count must equal the number of processes destroyForcibly was actually "
                + "invoked on, not the number the loop happened to visit: " + r.text());
    }

    @Test
    void aBudgetSpentExactlyAtAPassBoundaryReportsTheBudget(@TempDir Path dir) throws Exception {
        // REGRESSION 1 (needs-fix 605), and it needs its own fixture because the OTHER budget arm
        // never reaches this exit. The loop is
        //
        //     for (pass = 0; pass < maxPasses && !deadline.expired(); pass++)
        //
        // TWO exit conjuncts, and both used to fall through to one PASSES_EXHAUSTED call. With 16
        // leases the deadline is noticed by the check INSIDE the per-lease loop, which was always
        // correct. Only a budget that runs out exactly AT a pass boundary leaves by the
        // for-condition, and that is the path that lied.
        //
        // TEN leases is that fixture, and it is arithmetic rather than luck: each undying lease
        // consumes one SHUTDOWN_ATTEMPT_TIMEOUT_MS attempt of 500ms, so ten of them consume the
        // whole 5000ms budget, and the last attempt is capped at whatever remains. Pass one
        // therefore ends as the budget does, and the for-condition is what stops the loop. The
        // reviewer found it by scanning n and measuring: n=9 exited inside the loop, n=10 at the
        // boundary and reported "passes exhausted with budget remaining" after 5049ms.
        //
        // I confirmed this arm is load-bearing rather than assuming it: with the fix reverted and
        // only the other three arms present, the mutant SURVIVED.
        List<UndyingProcess> live = registerUndying(10, dir);
        Report r = runShutdownAndParse(live);

        assertBudgetWasExhausted(r);
        assertTrue(r.text().contains("shutdown budget expired"),
            "the whole budget was spent, so the report must name the BUDGET and not the pass cap, "
                + "whichever conjunct of the for-condition ended the loop: " + r.text());
        assertFalse(r.text().contains("with budget remaining"),
            "no budget remained: the run took " + r.elapsedMillis() + "ms of " + BUDGET_MILLIS
                + "ms: " + r.text());
    }

    @Test
    void anAlreadyDeadLeaseIsNotReportedAsKilledOrAsUnreached(@TempDir Path dir) throws Exception {
        // REGRESSION 2 (needs-fix 605), and it fired in the reviewer's own clean build for 73
        // leaked fakes. A lease whose process died before shutdown, retained only because its
        // profile could not be deleted, was reported as "sent SIGKILL and survived, all may still
        // be running" with destroyForcibly calls of ZERO. Three false clauses at once.
        //
        // terminateProcess guards its forcible branch with !allProcessesDead, not only with the
        // deadline, so the pass REACHES this lease and correctly sends it nothing. Reporting that
        // as never-reached would be just as false as reporting it killed, which is why the split
        // has a third category rather than two.
        UndyingProcess dead = new UndyingProcess(false);
        Path profile = Files.createDirectories(dir.resolve("undeletable"));
        BrewShot.registerContainedLaunchLeaseForTests(dead, profile, d -> { });

        Report r = runShutdownAndParse(List.of(dead));

        assertEquals(1, r.remaining(), r.text());
        assertEquals(0, r.killed(),
            "nothing was sent to an already-dead tree, so the report must not claim a SIGKILL: "
                + r.text());
        assertEquals(1, r.reachedNotSignalled(),
            "the pass DID reach it and correctly sent nothing; that is its own category, not "
                + "never-reached: " + r.text());
        assertEquals(0, r.neverReached(), r.text());

        assertEquals(0, dead.destroyForciblyCalls.get(),
            "fixture guard: an already-dead process must not have been signalled at all");

        // BOUND POSITIONALLY, not by the presence of the phrase (needs-fix 608, minor). This used
        // to assert contains("already-dead are not."), which is UNCONDITIONAL template text: it
        // passes on any report the builder produces, including one whose counting is entirely
        // broken. It was the only spelling-keyed assertion left in a file that is otherwise
        // behaviour-keyed.
        //
        // Deleting it was the offered fix. Binding it is better, because the running-claim clause
        // is a SECOND use of all three counts and nothing else in this test reaches it -- the
        // regex above parses only the first three positions. A mutant hard-coding the numbers in
        // this clause alone would have survived, which is exactly the two-uses-one-assertion shape
        // I closed in the sirentide banner and left open here.
        assertTrue(r.text().contains("The 0 killed and the 0 unreached may still be running; "
                + "the 1 already-dead are not."),
            "the running claim must carry the SAME counts as the split, at every position it "
                + "repeats them: " + r.text());
    }

    /// A handle that is ALIVE and whose destroyForcibly REFUSES, which is the JDK's own way of
    /// saying the signal was not sent. Counts its calls so the test can prove the site was reached.
    private static final class RefusingHandle implements ProcessHandle {
        final java.util.concurrent.atomic.AtomicInteger destroyForciblyCalls =
            new java.util.concurrent.atomic.AtomicInteger();
        private final long pid;
        private final java.util.concurrent.atomic.AtomicBoolean alive;
        private final List<ProcessHandle> descendants;
        private final java.util.concurrent.CompletableFuture<ProcessHandle> exited =
            new java.util.concurrent.CompletableFuture<>();

        RefusingHandle(long pid, boolean alive, List<ProcessHandle> descendants) {
            this.pid = pid;
            this.alive = new java.util.concurrent.atomic.AtomicBoolean(alive);
            this.descendants = descendants;
        }

        @Override public long pid() { return pid; }
        @Override public java.util.Optional<ProcessHandle> parent() { return java.util.Optional.empty(); }
        @Override public java.util.stream.Stream<ProcessHandle> children() { return descendants.stream(); }
        @Override public java.util.stream.Stream<ProcessHandle> descendants() { return descendants.stream(); }
        @Override public Info info() { return ProcessHandle.current().info(); }
        @Override public java.util.concurrent.CompletableFuture<ProcessHandle> onExit() { return exited; }
        @Override public boolean supportsNormalTermination() { return true; }
        @Override public boolean isAlive() { return alive.get(); }
        @Override public int compareTo(ProcessHandle other) { return Long.compare(pid, other.pid()); }

        void die() { alive.set(false); }
        @Override public boolean destroy() { return false; }

        /// FALSE: the JDK says the signal was NOT sent.
        @Override public boolean destroyForcibly() {
            destroyForciblyCalls.incrementAndGet();
            return false;
        }
    }

    /// A dead process that HAS a handle, so terminateProcess reaches the descendant sites at all.
    /// UndyingProcess cannot: it leaves toHandle() throwing, so every handle site is skipped.
    private static final class HandleBearingProcess extends Process {
        private final ProcessHandle handle;

        HandleBearingProcess(ProcessHandle handle) { this.handle = handle; }

        @Override public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
        @Override public java.io.InputStream getInputStream() { return java.io.InputStream.nullInputStream(); }
        @Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
        @Override public boolean isAlive() { return false; }
        @Override public ProcessHandle toHandle() { return handle; }
        @Override public int exitValue() { return 0; }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long t, java.util.concurrent.TimeUnit u) { return true; }
        @Override public void destroy() { }
        @Override public Process destroyForcibly() { return this; }
    }

    @Test
    void aRefusedSignalIsNotReportedAsAKill(@TempDir Path dir) throws Exception {
        // needs-fix 608: "sent" still meant "attempted", one layer below where I fixed it.
        //
        // ProcessHandle.destroyForcibly() RETURNS a boolean saying whether the signal was actually
        // sent, and the code discarded it, setting the flag because the call did not THROW. So the
        // flag meant ATTEMPTED while the report labelled it SENT SIGKILL -- the same substitution
        // of a near-fact for the fact as regression 2, one level down. I verified the signature
        // against the JDK rather than taking it: ProcessHandle.destroyForcibly returns boolean,
        // Process.destroyForcibly returns Process, which is why one of the three sites stays
        // attempt-based and says so.
        //
        // THIS FIXTURE EXISTS BECAUSE THE FIX WAS UNHELD. I reverted the two sites to
        // attempted-means-sent and the whole class SURVIVED, because every other fake here leaves
        // toHandle() throwing and so never reaches a handle site at all. An accuracy fix with no
        // test is the thing I had just finished writing an essay about.
        //
        // The tree: process DEAD, parent handle DEAD, one descendant ALIVE whose destroyForcibly
        // refuses. So allProcessesDead is false and the forcible branch runs; the descendant site
        // is reached and answers NO; the process and parent sites are both skipped. Nothing was
        // sent, and the report must not claim otherwise.
        RefusingHandle descendant = new RefusingHandle(9101, true, List.of());
        RefusingHandle parent = new RefusingHandle(9100, false, List.of(descendant));
        HandleBearingProcess process = new HandleBearingProcess(parent);

        Path profile = Files.createDirectories(dir.resolve("refused"));
        BrewShot.registerContainedLaunchLeaseForTests(process, profile, d -> { });

        Report r;
        try {
            r = runShutdownAndParse(List.of());
        } finally {
            // DRAIN. This lease can never release on its own -- its descendant is alive and its
            // destroyForcibly always refuses -- so without this it stays in LIVE for the rest of
            // the JVM and every later arm reads "2 lease(s)". It did, on the first run: two sibling
            // arms failed with a count that belonged to this fixture.
            descendant.die();
            BrewShot.runJvmShutdownCleanupForTests();
        }

        assertTrue(descendant.destroyForciblyCalls.get() > 0,
            "fixture guard: the descendant site must actually have been REACHED, or this test "
                + "proves nothing about what it does with the answer");
        assertEquals(1, r.remaining(), r.text());
        assertEquals(0, r.killed(),
            "destroyForcibly returned FALSE, so no signal was sent and the report must not claim "
                + "a SIGKILL: " + r.text());
        assertEquals(1, r.reachedNotSignalled(),
            "the pass reached it and sent nothing, which is the middle category: " + r.text());
    }

    @Test
    void aRefusedSignalToTheParentHandleIsNotReportedAsAKill(@TempDir Path dir) throws Exception {
        // THE MIRROR ARM (needs-fix 610), and the finding behind it is my own lesson applied to
        // only one of the two sites I changed.
        //
        // I reported that reverting BOTH changed sites together went red, which is true and
        // misleading: a mutant proves only the site it mutates. Reverted individually, the
        // descendant-loop site went RED and the parentHandle site went GREEN, because the fixture
        // above builds a tree whose parent handle is DEAD -- so `else if (parentHandle != null &&
        // handleAlive(parentHandle))` is never entered, and no other fake in the tree reaches any
        // handle site at all.
        //
        // This tree is the mirror: process DEAD, NO descendants, parent handle ALIVE and REFUSING.
        // allProcessesDead is false because the parent lives, so the forcible branch runs; the
        // descendant loop has nothing to do; isAlive(process) is false so the else-if is taken; and
        // the parent answers NO. Nothing was sent, and the report must not claim otherwise.
        RefusingHandle parent = new RefusingHandle(9201, true, List.of());
        HandleBearingProcess process = new HandleBearingProcess(parent);

        Path profile = Files.createDirectories(dir.resolve("refused-parent"));
        BrewShot.registerContainedLaunchLeaseForTests(process, profile, d -> { });

        Report r;
        try {
            r = runShutdownAndParse(List.of());
        } finally {
            parent.die();
            BrewShot.runJvmShutdownCleanupForTests();
        }

        assertTrue(parent.destroyForciblyCalls.get() > 0,
            "fixture guard: the PARENT-HANDLE site must actually have been reached, which is the "
                + "whole point of this arm -- the sibling fixture never enters it");
        assertEquals(1, r.remaining(), r.text());
        assertEquals(0, r.killed(),
            "parentHandle.destroyForcibly returned FALSE, so no signal was sent and the report "
                + "must not claim a SIGKILL: " + r.text());
        assertEquals(1, r.reachedNotSignalled(),
            "reached and correctly sent nothing, which is the middle category: " + r.text());
    }
}
