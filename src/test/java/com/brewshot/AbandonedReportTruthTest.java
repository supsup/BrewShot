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
        assertTrue(r.text().contains("already-dead are not."),
            "the report must not claim a dead lease may still be running: " + r.text());
    }
}
