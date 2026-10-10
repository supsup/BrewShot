package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The stale-profile reaper deletes another profile only when every check holds,
 * and keeps it when any check fails or cannot be answered (plan 3a45f8fe).
 * The probes are seams here; one test runs the real ProcessHandle liveness.
 */
class StaleProfileReaperTest {

    private static final String HOST = "build-host.local";
    private static final Instant NOW = Instant.parse("2026-10-10T02:00:00Z");
    private static final Duration AGE = Duration.ofHours(1);
    private static final long OWN_PID = 111;
    private static final long DEAD_PID = 222;
    private static final long LIVE_PID = 333;

    private static final StaleProfileReaper.Liveness LIVENESS = pid -> pid == DEAD_PID;
    private static final StaleProfileReaper.ArgvProbe NO_ARGV = profile -> false;
    private static final StaleProfileReaper.OwnerUid SAME_UID = path -> 1000;

    @TempDir Path tmp;

    @Test
    void anOldProfileWhoseLockOwnerIsDeadIsReaped() throws IOException {
        Path own = own();
        Path stale = profile("brewshot-200", HOST + "-" + DEAD_PID, NOW.minus(Duration.ofHours(2)));
        Files.createDirectories(stale.resolve("Default/Cache"));
        Files.writeString(stale.resolve("Default/Cache/data"), "x");
        stale.toFile().setLastModified(NOW.minus(Duration.ofHours(2)).toEpochMilli());

        StaleProfileReaper.Outcome outcome = run(own, LIVENESS, NO_ARGV, SAME_UID);

        assertEquals(List.of(stale), outcome.reaped());
        assertTrue(Files.notExists(stale, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isDirectory(own), "our own profile is never touched");
    }

    @Test
    void aProfileWhoseLockOwnerIsAliveIsKept() throws IOException {
        Path stale = profile("brewshot-201", HOST + "-" + LIVE_PID, old());

        StaleProfileReaper.Outcome outcome = run(own(), LIVENESS, NO_ARGV, SAME_UID);

        assertKept(outcome, stale, "lock owner alive or unknown");
    }

    @Test
    void aMissingLockIsKept() throws IOException {
        Path stale = dir("brewshot-202", old());

        assertKept(run(own(), LIVENESS, NO_ARGV, SAME_UID), stale, "no readable lock");
    }

    @Test
    void aLockThatIsARegularFileIsKept() throws IOException {
        Path stale = dir("brewshot-203", null);
        Files.writeString(stale.resolve(StaleProfileReaper.LOCK), HOST + "-" + DEAD_PID);
        touch(stale, old());

        assertKept(run(own(), LIVENESS, NO_ARGV, SAME_UID), stale, "no readable lock");
    }

    @Test
    void aLockFromAnotherHostIsKept() throws IOException {
        Path stale = profile("brewshot-204", "other-host-" + DEAD_PID, old());

        assertKept(run(own(), LIVENESS, NO_ARGV, SAME_UID), stale, "lock names another host");
    }

    @Test
    void aMalformedLockIsKept() throws IOException {
        Path stale = profile("brewshot-205", "garbage", old());
        Path noPid = profile("brewshot-206", HOST + "-", old());

        StaleProfileReaper.Outcome outcome = run(own(), LIVENESS, NO_ARGV, SAME_UID);

        assertKept(outcome, stale, "lock is not <host>-<pid>");
        assertKept(outcome, noPid, "lock is not <host>-<pid>");
    }

    @Test
    void aYoungProfileIsKept() throws IOException {
        Path stale = profile("brewshot-207", HOST + "-" + DEAD_PID, NOW.minus(Duration.ofMinutes(10)));

        assertKept(run(own(), LIVENESS, NO_ARGV, SAME_UID), stale, "younger than the minimum age");
    }

    @Test
    void aProfileOwnedByAnotherUidIsKept() throws IOException {
        Path own = own();
        Path stale = profile("brewshot-208", HOST + "-" + DEAD_PID, old());
        StaleProfileReaper.OwnerUid foreign = path -> path.equals(own) ? 1000 : 2000;

        assertKept(run(own, LIVENESS, NO_ARGV, foreign), stale, "owned by another uid");
    }

    @Test
    void anUnreadableOwnerIsKept() throws IOException {
        Path own = own();
        Path stale = profile("brewshot-209", HOST + "-" + DEAD_PID, old());
        StaleProfileReaper.OwnerUid unreadable = path -> {
            if (path.equals(own)) { return 1000; }
            throw new IOException("no uid");
        };

        assertKept(run(own, LIVENESS, NO_ARGV, unreadable), stale, "owner unreadable");
    }

    @Test
    void aProfileNamedInSomeProcessArgvIsKept() throws IOException {
        Path stale = profile("brewshot-210", HOST + "-" + DEAD_PID, old());
        StaleProfileReaper.ArgvProbe named = profile -> profile.equals(stale);

        assertKept(run(own(), LIVENESS, named, SAME_UID), stale,
            "a running process names this profile");
    }

    @Test
    void anUnanswerableProbeIsKept() throws IOException {
        Path own = own();
        Path stale = profile("brewshot-211", HOST + "-" + DEAD_PID, old());
        StaleProfileReaper.Liveness throwing = pid -> { throw new IllegalStateException(); };
        StaleProfileReaper.ArgvProbe throwingArgv = profile -> { throw new IllegalStateException(); };

        assertKept(run(own, throwing, NO_ARGV, SAME_UID), stale, "lock owner alive or unknown");
        assertKept(run(own, LIVENESS, throwingArgv, SAME_UID), stale,
            "a running process names this profile");
    }

    @Test
    void whenOurOwnLockIsUnreadableNothingIsReaped() throws IOException {
        Path own = dir("brewshot-100", null);
        Path stale = profile("brewshot-212", HOST + "-" + DEAD_PID, old());

        StaleProfileReaper.Outcome outcome = run(own, LIVENESS, NO_ARGV, SAME_UID);

        assertTrue(outcome.reaped().isEmpty());
        assertTrue(Files.isDirectory(stale));
    }

    @Test
    void ourOwnProfileIsNeverACandidateEvenWithADeadLock() throws IOException {
        Path own = profile("brewshot-100", HOST + "-" + DEAD_PID, old());

        StaleProfileReaper.Outcome outcome = run(own, LIVENESS, NO_ARGV, SAME_UID);

        assertTrue(outcome.reaped().isEmpty());
        assertFalse(outcome.kept().containsKey(own), "our own profile is not even examined");
        assertTrue(Files.isDirectory(own));
    }

    @Test
    void onlyExactGeneratedNamesAreCandidates() throws IOException {
        Path own = own();
        Path letters = profile("brewshot-abc", HOST + "-" + DEAD_PID, old());
        Path other = profile("chrome-300", HOST + "-" + DEAD_PID, old());
        Path suffixed = profile("brewshot-301-keep", HOST + "-" + DEAD_PID, old());

        StaleProfileReaper.Outcome outcome = run(own, LIVENESS, NO_ARGV, SAME_UID);

        assertTrue(outcome.reaped().isEmpty());
        assertTrue(outcome.kept().isEmpty(), "non-matching names are not examined at all");
        for (Path p : List.of(letters, other, suffixed)) { assertTrue(Files.isDirectory(p)); }
    }

    @Test
    void aSymlinkNamedLikeAProfileIsKeptAndItsTargetUntouched() throws IOException {
        Path own = own();
        Path elsewhere = profile("elsewhere", HOST + "-" + DEAD_PID, old());
        Path link = Files.createSymbolicLink(tmp.resolve("brewshot-213"), elsewhere);

        StaleProfileReaper.Outcome outcome = run(own, LIVENESS, NO_ARGV, SAME_UID);

        assertKept(outcome, link, "not a plain directory");
        assertTrue(Files.isSymbolicLink(link));
        assertTrue(Files.isSymbolicLink(elsewhere.resolve(StaleProfileReaper.LOCK)));
    }

    @Test
    void deletionRemovesLinksInsideWithoutFollowingThem() throws IOException {
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("precious"), "keep me");
        Path stale = dir("brewshot-214", null);
        Files.createSymbolicLink(stale.resolve("link-to-outside"), outside);
        Files.createSymbolicLink(stale.resolve(StaleProfileReaper.LOCK),
            Path.of(HOST + "-" + DEAD_PID));
        touch(stale, old());

        StaleProfileReaper.Outcome outcome = run(own(), LIVENESS, NO_ARGV, SAME_UID);

        assertEquals(List.of(stale), outcome.reaped());
        assertEquals("keep me", Files.readString(outside.resolve("precious")));
    }

    @Test
    void theLockIsDeletedLastSoAPartialDeleteIsRetried() throws IOException {
        // A delete that cannot finish must leave the lock behind: a lockless
        // remnant would be kept by every later run, forever.
        Path stale = profile("brewshot-215", HOST + "-" + DEAD_PID, old());
        Path stuck = Files.createDirectories(stale.resolve("stuck"));
        Files.writeString(stuck.resolve("file"), "x");
        assumeTrue(stuck.toFile().setWritable(false), "cannot make a directory read-only here");
        try {
            assumeTrue(!Files.isWritable(stuck), "running with privileges that ignore modes");
            touch(stale, old());

            StaleProfileReaper.Outcome outcome = run(own(), LIVENESS, NO_ARGV, SAME_UID);

            assertKept(outcome, stale, "delete incomplete");
            assertTrue(Files.isSymbolicLink(stale.resolve(StaleProfileReaper.LOCK)),
                "the lock must survive a partial delete");
        } finally {
            stuck.toFile().setWritable(true);
        }
    }

    @Test
    void thePerRunLimitBoundsDeletions() throws IOException {
        Path own = own();
        int total = StaleProfileReaper.MAX_REAPED_PER_RUN + 3;
        for (int i = 0; i < total; i++) {
            profile("brewshot-" + (1000 + i), HOST + "-" + DEAD_PID, old());
        }

        StaleProfileReaper.Outcome outcome = run(own, LIVENESS, NO_ARGV, SAME_UID);

        assertEquals(StaleProfileReaper.MAX_REAPED_PER_RUN, outcome.reaped().size());
        assertEquals(3, outcome.kept().values().stream()
            .filter("per-run limit reached"::equals).count());
    }

    @Test
    void realLivenessKeepsALiveOwnerAndReapsAnExitedOne() throws Exception {
        // The real probes: this JVM is alive; a child we started and waited for is not.
        Process exited = new ProcessBuilder("true").start();
        assertEquals(0, exited.waitFor());
        long deadPid = exited.pid();
        assumeTrue(ProcessHandle.of(deadPid).isEmpty(), "the pid was reused already");
        long livePid = ProcessHandle.current().pid();

        Path own = own();
        Path dead = profile("brewshot-400", HOST + "-" + deadPid, old());
        Path live = profile("brewshot-401", HOST + "-" + livePid, old());

        StaleProfileReaper.Outcome outcome = StaleProfileReaper.reap(own, NOW, AGE,
            StaleProfileReaper::processProvablyDead, new StaleProfileReaper.LiveArgvProbe(),
            StaleProfileReaper::unixUid);

        assertEquals(List.of(dead), outcome.reaped());
        assertEquals("lock owner alive or unknown", outcome.kept().get(live));
        assertEquals(Set.of(live), outcome.kept().keySet());
    }

    @Test
    void aRealLaunchRemovesAStaleSiblingProfile() throws Exception {
        // End to end: launch() calls the reaper. The first launch tells us the host
        // string Chrome writes into its own lock; a planted sibling with that host,
        // an exited pid and an old mtime must be gone after the second launch.
        assumeTrue(BrewShot.available(), "no Chrome on this host");
        String host;
        try (BrewShot first = BrewShot.launch()) {
            String target = Files.readSymbolicLink(
                first.profileDirForTests().resolve(StaleProfileReaper.LOCK)).toString();
            host = target.substring(0, target.lastIndexOf('-'));
        }
        Process exited = new ProcessBuilder("true").start();
        assertEquals(0, exited.waitFor());
        assumeTrue(ProcessHandle.of(exited.pid()).isEmpty(), "the pid was reused already");

        Path stale = Files.createTempDirectory("brewshot-");
        try {
            Files.createDirectories(stale.resolve("Default"));
            Files.createSymbolicLink(stale.resolve(StaleProfileReaper.LOCK),
                Path.of(host + "-" + exited.pid()));
            touch(stale, Instant.now().minus(Duration.ofHours(2)));

            try (BrewShot second = BrewShot.launch()) {
                assertTrue(Files.notExists(stale, LinkOption.NOFOLLOW_LINKS),
                    "launch did not remove a stale sibling profile");
                assertTrue(Files.isDirectory(second.profileDirForTests()));
            }
        } finally {
            if (Files.exists(stale, LinkOption.NOFOLLOW_LINKS)) {
                StaleProfileReaper.deleteLockLast(stale);
            }
        }
    }

    // -- fixtures ------------------------------------------------------------

    private StaleProfileReaper.Outcome run(Path own, StaleProfileReaper.Liveness liveness,
                                           StaleProfileReaper.ArgvProbe argv,
                                           StaleProfileReaper.OwnerUid owner) {
        return StaleProfileReaper.reap(own, NOW, AGE, liveness, argv, owner);
    }

    private Path own() throws IOException {
        return profile("brewshot-100", HOST + "-" + OWN_PID, NOW);
    }

    private static Instant old() {
        return NOW.minus(Duration.ofHours(3));
    }

    /** A profile directory holding a Chrome-shaped lock symlink, with its mtime set last. */
    private Path profile(String name, String lockTarget, Instant mtime) throws IOException {
        Path p = dir(name, null);
        Files.createSymbolicLink(p.resolve(StaleProfileReaper.LOCK), Path.of(lockTarget));
        touch(p, mtime);
        return p;
    }

    private Path dir(String name, Instant mtime) throws IOException {
        Path p = Files.createDirectories(tmp.resolve(name));
        if (mtime != null) { touch(p, mtime); }
        return p;
    }

    private static void touch(Path p, Instant mtime) throws IOException {
        Files.setLastModifiedTime(p, FileTime.from(mtime));
    }

    private static void assertKept(StaleProfileReaper.Outcome outcome, Path p, String reason) {
        assertFalse(outcome.reaped().contains(p), p + " was reaped");
        assertEquals(reason, outcome.kept().get(p), "reason for keeping " + p);
        assertTrue(Files.exists(p, LinkOption.NOFOLLOW_LINKS), p + " must still exist");
    }
}
