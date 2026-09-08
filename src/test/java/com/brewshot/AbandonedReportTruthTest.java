package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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

    /// Correct Process contract for a live process: exitValue THROWS, and the timed waitFor blocks.
    /// Both matter -- Process.waitFor(timeout) is built on that throw, so a fake that returns a
    /// value instead makes every cleanup attempt return instantly and the budget never runs down.
    private static final class UndyingProcess extends Process {
        private volatile boolean alive = true;

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
        @Override public Process destroyForcibly() { return this; }

        void die() { alive = false; }
    }

    /// The three counts the sentence commits to, read by POSITION rather than by phrase.
    private record Report(String text, int remaining, int reached, int neverReached) { }

    private static final Pattern COUNTS = Pattern.compile(
        "with (\\d+) lease\\(s\\) not confirmed reaped.*?of those remaining, (\\d+) were sent SIGKILL"
        + " by this pass and survived it, and (\\d+) were never reached",
        Pattern.DOTALL);

    private static Report runShutdownAndParse(List<UndyingProcess> live) {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        String text;
        try {
            System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            BrewShot.runJvmShutdownCleanupForTests();
        } finally {
            System.setErr(original);
            text = buffer.toString(StandardCharsets.UTF_8);
            // Drain: let them die and reap, so surviving leases do not leak into the other arm.
            for (UndyingProcess p : live) { p.die(); }
            BrewShot.runJvmShutdownCleanupForTests();
        }
        Matcher m = COUNTS.matcher(text);
        assertTrue(m.find(), "the abandoned report did not fire, or its shape changed: " + text);
        Report r = new Report(text,
            Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        assertEquals(r.remaining(), r.reached() + r.neverReached(),
            "the split must account for every remaining lease: " + text);
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

    @Test
    void passesExhaustedSaysSoAndClaimsTheKillItActuallySent(@TempDir Path dir) {
        // Three passes of one 500ms attempt cannot exhaust a 5s budget, so this path ends by
        // running out of PASSES with budget remaining -- what the old sentence called "expired".
        Report r = runShutdownAndParse(registerUndying(1, dir));

        assertTrue(r.text().contains("retry passes were exhausted with budget remaining"),
            "must name the cause it actually had: " + r.text());
        assertFalse(r.text().contains("budget expired"),
            "must not claim the budget expired when it did not: " + r.text());

        // Reached by the pass => destroyForcibly WAS sent to it. The old sentence said the opposite.
        assertEquals(1, r.remaining(), r.text());
        assertEquals(1, r.reached(), "a lease the pass reached must be reported as SIGKILLed: " + r.text());
        assertEquals(0, r.neverReached(), "nothing was left unreached on this path: " + r.text());
    }

    @Test
    void budgetExpiredSaysSoAndSeparatesReachedFromNeverReached(@TempDir Path dir) {
        // Enough leases that 500ms apiece runs the 5s budget out mid-pass, leaving a genuine MIX:
        // some the pass reached and SIGKILLed, some it never got to. The old single sentence could
        // not express that difference and asserted never-killed for both.
        Report r = runShutdownAndParse(registerUndying(16, dir));

        assertTrue(r.text().contains("shutdown budget expired"),
            "must name the cause it actually had: " + r.text());
        assertFalse(r.text().contains("passes were exhausted"),
            "the passes were not exhausted; the budget ran out: " + r.text());

        assertTrue(r.reached() > 0,
            "leases the pass reached must be counted as SIGKILLed, not as never-reached: " + r.text());
        assertTrue(r.neverReached() > 0,
            "the budget expired mid-pass, so some lease was never reached: " + r.text());
    }
}
