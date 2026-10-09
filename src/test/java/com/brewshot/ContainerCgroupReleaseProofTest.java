package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The container-cgroup release proof, with every file and the clock injected. Nothing here
 * reads the real /proc or /sys, launches a process or sleeps: the fake sleeper advances the fake
 * clock, so a "5 second deadline" costs no wall time and cannot flake.
 */
class ContainerCgroupReleaseProofTest {

    static final Path SELF = Path.of("/fake/proc/self/cgroup");
    static final Path PROCS = Path.of("/fake/sys/fs/cgroup/cgroup.procs");
    static final Path PROC = Path.of("/fake/proc");

    /**
     * Fake cgroup files. cgroup.procs answers from a queue of successive contents (the last one
     * repeats), so a test can make members leave over time; a null entry is a read failure.
     */
    static final class FakeCgroup {
        final Map<Path, String> files = new HashMap<>();
        final Deque<String> procsSequence = new ArrayDeque<>();
        final AtomicInteger procsReads = new AtomicInteger();
        final List<String> log = new ArrayList<>();
        long nowNanos = 1_000_000_000L;
        long selfPid = 1;

        FakeCgroup selfCgroup(String content) { files.put(SELF, content); return this; }

        FakeCgroup procs(String... contents) {
            procsSequence.clear();
            procsSequence.addAll(List.of(contents));
            return this;
        }

        FakeCgroup comm(long pid, String comm) {
            files.put(PROC.resolve(Long.toString(pid)).resolve("comm"), comm + "\n");
            return this;
        }

        FakeCgroup self(long pid) { selfPid = pid; return this; }

        String read(Path path) throws IOException {
            if (path.equals(PROCS)) {
                procsReads.incrementAndGet();
                String next = procsSequence.size() > 1 ? procsSequence.poll() : procsSequence.peek();
                if (next == null || next.equals("<unreadable>")) {
                    throw new java.nio.file.AccessDeniedException(path.toString());
                }
                return next;
            }
            String content = files.get(path);
            if (content == null) { throw new NoSuchFileException(path.toString()); }
            return content;
        }

        ContainerCgroupReleaseProof proof(long pollMillis, long deadlineMillis) {
            return ContainerCgroupReleaseProof.create(SELF, PROCS, PROC, selfPid, pollMillis,
                deadlineMillis, this::read, () -> nowNanos,
                millis -> nowNanos += TimeUnit.MILLISECONDS.toNanos(millis), log::add);
        }

        long elapsedMillisSince(long startNanos) {
            return TimeUnit.NANOSECONDS.toMillis(nowNanos - startNanos);
        }

        String lastLog() { return log.get(log.size() - 1); }
    }

    // ---- strict detection: the parser over every shape ---------------------------------------

    @Test
    void cgroupV2RootIsUsableAndTheBaselineIsTheFileNotAnAssumption() {
        ContainerCgroupReleaseProof.Detection d =
            ContainerCgroupReleaseProof.detect("0::/\n", "1\n6\n", 6);
        assertTrue(d.isUsable(), d.reason());
        assertEquals(Set.of(1L, 6L), d.baseline());
    }

    @Test
    void cgroupV2RootWithoutTrailingNewlineIsUsable() {
        assertTrue(ContainerCgroupReleaseProof.detect("0::/", "1\n", 1).isUsable());
        assertTrue(ContainerCgroupReleaseProof.detect("0::/", "1", 1).isUsable(),
            "a final newline on cgroup.procs is optional");
    }

    @Test
    void cgroupV1HierarchyIsRefused() {
        String v1 = "12:pids:/docker/abc\n11:memory:/docker/abc\n0::/\n";
        String reason = ContainerCgroupReleaseProof.detect(v1, "1\n", 1).reason();
        assertTrue(reason.contains("more than one line"), reason);
        String singleV1 = "4:memory:/\n";
        reason = ContainerCgroupReleaseProof.detect(singleV1, "1\n", 1).reason();
        assertTrue(reason.contains("not a single cgroup v2 unified line"), reason);
    }

    @Test
    void nonRootCgroupPathIsRefused() {
        for (String path : List.of("0::/user.slice/user-501.slice/session-3.scope\n",
                "0::/docker/0123abcd\n", "0::/init.scope", "0::\n")) {
            ContainerCgroupReleaseProof.Detection d =
                ContainerCgroupReleaseProof.detect(path, "1\n", 1);
            assertFalse(d.isUsable(), path);
            assertTrue(d.reason().contains("non-root cgroup path"), d.reason());
        }
    }

    @Test
    void multipleLinesAreRefusedEvenWhenEachLooksLikeTheRoot() {
        ContainerCgroupReleaseProof.Detection d =
            ContainerCgroupReleaseProof.detect("0::/\n0::/\n", "1\n", 1);
        assertFalse(d.isUsable());
        assertTrue(d.reason().contains("more than one line"), d.reason());
    }

    @Test
    void missingSelfPidIsRefused() {
        ContainerCgroupReleaseProof.Detection d =
            ContainerCgroupReleaseProof.detect("0::/\n", "1\n", 6);
        assertFalse(d.isUsable());
        assertTrue(d.reason().contains("pid 6 is not in cgroup.procs"), d.reason());
        assertFalse(ContainerCgroupReleaseProof.detect("0::/\n", "", 1).isUsable(),
            "an empty membership file cannot contain this JVM");
    }

    @Test
    void unreadableFilesAreRefused() {
        FakeCgroup noSelf = new FakeCgroup().procs("1\n");
        ContainerCgroupReleaseProof proof = noSelf.proof(50, 5_000);
        assertFalse(proof.detection().isUsable());
        assertTrue(proof.detection().reason().startsWith("cannot read " + SELF), proof.detection().reason());

        FakeCgroup noProcs = new FakeCgroup().selfCgroup("0::/\n").procs("<unreadable>");
        proof = noProcs.proof(50, 5_000);
        assertFalse(proof.detection().isUsable());
        assertTrue(proof.detection().reason().startsWith("cannot read " + PROCS), proof.detection().reason());
    }

    @Test
    void garbageIsRefused() {
        for (String procs : List.of("1\nabc\n", "1\n\n6\n", "-3\n1\n", " 1\n", "1 \n",
                "0\n1\n", "1\r\n", "99999999999999999999\n1\n")) {
            ContainerCgroupReleaseProof.Detection d =
                ContainerCgroupReleaseProof.detect("0::/\n", procs, 1);
            assertFalse(d.isUsable(), "procs " + procs.replace("\n", "\\n"));
            assertTrue(d.reason().contains("unparseable"), d.reason());
        }
        for (String self : List.of("", "\n", " 0::/\n", "0::/ \n", "0::/\n\n", "garbage")) {
            assertFalse(ContainerCgroupReleaseProof.detect(self, "1\n", 1).isUsable(),
                "self cgroup '" + self.replace("\n", "\\n") + "'");
        }
    }

    @Test
    void detectionNamesItsResultOnTheDiagnosticChannel() {
        FakeCgroup ok = new FakeCgroup().selfCgroup("0::/\n").procs("1\n6\n").self(6);
        ok.proof(50, 5_000);
        assertTrue(ok.lastLog().contains("usable") && ok.lastLog().contains("[1, 6]"), ok.lastLog());
        FakeCgroup bad = new FakeCgroup().selfCgroup("0::/user.slice\n").procs("1\n");
        bad.proof(50, 5_000);
        assertTrue(bad.lastLog().contains("unusable, release UNPROVEN"), bad.lastLog());
    }

    @Test
    void anUnusableProofRefusesWithoutReadingMembership() {
        FakeCgroup bad = new FakeCgroup().selfCgroup("0::/docker/x\n").procs("1\n");
        ContainerCgroupReleaseProof proof = bad.proof(50, 5_000);
        int readsAtCreation = bad.procsReads.get();
        assertFalse(proof.releaseSafe());
        assertEquals(readsAtCreation, bad.procsReads.get());
        assertTrue(bad.lastLog().contains("release UNPROVEN, proof unusable"), bad.lastLog());
    }

    // ---- bounded wait-for-equality -------------------------------------------------------------

    @Test
    void equalityReachedImmediately() {
        FakeCgroup cg = new FakeCgroup().selfCgroup("0::/\n").procs("1\n");
        ContainerCgroupReleaseProof proof = cg.proof(50, 5_000);
        long start = cg.nowNanos;
        assertTrue(proof.releaseSafe());
        assertEquals(0, cg.elapsedMillisSince(start));
        assertEquals(2, cg.procsReads.get(), "one read for the baseline, one for the check");
        assertTrue(cg.lastLog().contains("returned to baseline [1] after 0 ms (1 read(s))"),
            cg.lastLog());
    }

    @Test
    void equalityReachedAfterMembersLeaveOverSeveralPolls() {
        // Baseline {1, 6} (the --init shape), then Chrome's survivors drain one by one.
        FakeCgroup cg = new FakeCgroup().selfCgroup("0::/\n").self(6)
            .procs("1\n6\n", "1\n6\n40\n41\n42\n", "1\n6\n41\n42\n", "1\n6\n42\n", "1\n6\n");
        ContainerCgroupReleaseProof proof = cg.proof(50, 5_000);
        long start = cg.nowNanos;
        assertTrue(proof.releaseSafe());
        assertEquals(150, cg.elapsedMillisSince(start), "three 50 ms polls before equality");
        assertTrue(cg.lastLog().contains("after 150 ms (4 read(s)); release proven"), cg.lastLog());
    }

    @Test
    void equalityNeverReachedRefusesByDeadlineAndNamesTheExtraPids() {
        FakeCgroup cg = new FakeCgroup().selfCgroup("0::/\n").procs("1\n", "1\n77\n")
            .comm(77, "chrome_crashpad");
        ContainerCgroupReleaseProof proof = cg.proof(50, 5_000);
        long start = cg.nowNanos;
        assertFalse(proof.releaseSafe());
        assertEquals(5_000, cg.elapsedMillisSince(start), "refused BY DEADLINE, not early");
        assertEquals(1 + 101, cg.procsReads.get(), "baseline + reads at 0, 50, ..., 5000 ms");
        String line = cg.lastLog();
        assertTrue(line.contains("did not return to baseline [1] within 5000 ms"), line);
        assertTrue(line.contains("extra pids [77 (chrome_crashpad)]"), line);
        assertTrue(line.contains("release UNPROVEN"), line);
        assertTrue(proof.lastOutcome().startsWith("UNPROVEN by deadline"), proof.lastOutcome());
    }

    @Test
    void anExtraPidWithUnreadableCommIsStillNamed() {
        FakeCgroup cg = new FakeCgroup().selfCgroup("0::/\n").procs("1\n", "1\n88\n");
        assertFalse(cg.proof(50, 200).releaseSafe());
        assertTrue(cg.lastLog().contains("extra pids [88 (?)]"), cg.lastLog());
    }

    @Test
    void baselineIsComputedSoTheInitShapeIsProvenNotRefused() {
        // docker run --init: pid 1 is docker-init and the JVM is pid 6. A proof that ASSUMED
        // {1} would refuse every capture here forever.
        FakeCgroup cg = new FakeCgroup().selfCgroup("0::/\n").self(6).procs("1\n6\n", "1\n6\n");
        ContainerCgroupReleaseProof proof = cg.proof(50, 5_000);
        assertEquals(Set.of(1L, 6L), proof.detection().baseline());
        assertTrue(proof.releaseSafe());
    }

    @Test
    void baselineWithAnExtraMemberAtStartIsTheBaseline() {
        // A process already present before Chrome starts (a docker exec, a sidecar shell) is part
        // of the baseline. While it stays, the capture's own drain is still provable.
        FakeCgroup cg = new FakeCgroup().selfCgroup("0::/\n")
            .procs("1\n90\n", "1\n90\n300\n", "1\n90\n");
        ContainerCgroupReleaseProof proof = cg.proof(50, 5_000);
        assertEquals(Set.of(1L, 90L), proof.detection().baseline());
        assertTrue(proof.releaseSafe());
    }

    @Test
    void aBaselineMemberThatLeavesKeepsTheSetUnequalAndRefuses() {
        // Fail-closed by construction: the proof wants EQUALITY, not "no new pids". A vanished
        // baseline pid could be reused by a survivor, so its absence is not treated as harmless.
        FakeCgroup cg = new FakeCgroup().selfCgroup("0::/\n").procs("1\n90\n", "1\n");
        assertFalse(cg.proof(50, 300).releaseSafe());
        assertTrue(cg.lastLog().contains("missing baseline pids [90]"), cg.lastLog());
    }

    @Test
    void discriminatorAMemberInvisibleToArgvKeepsReleaseRefused() {
        // The measured crashpad/setsid shape: the survivor reparented to pid 1, owns its own
        // process group and carries no profile path in argv, so no enumeration of the launched
        // tree can see it. It is still in the container's cgroup, and that alone refuses.
        FakeCgroup cg = new FakeCgroup().selfCgroup("0::/\n")
            .procs("1\n", "1\n812\n813\n", "1\n812\n")
            .comm(812, "chrome_crashpad");
        ContainerCgroupReleaseProof proof = cg.proof(50, 5_000);
        long start = cg.nowNanos;
        assertFalse(proof.releaseSafe());
        assertEquals(5_000, cg.elapsedMillisSince(start));
        assertTrue(cg.lastLog().contains("extra pids [812 (chrome_crashpad)]"), cg.lastLog());
    }

    @Test
    void theWaitIsCappedByTheCallersRemainingBudget() {
        FakeCgroup cg = new FakeCgroup().selfCgroup("0::/\n").procs("1\n", "1\n5\n");
        ContainerCgroupReleaseProof proof = cg.proof(50, 5_000);
        long start = cg.nowNanos;
        assertFalse(proof.releaseSafe(TimeUnit.MILLISECONDS.toNanos(500)));
        assertEquals(500, cg.elapsedMillisSince(start),
            "a JVM shutdown attempt's 500 ms budget bounds the wait, not the proof's 5 s");
        assertTrue(cg.lastLog().contains("limit 500 ms"), cg.lastLog());
    }

    @Test
    void aZeroBudgetStillTakesOneRead() {
        FakeCgroup cg = new FakeCgroup().selfCgroup("0::/\n").procs("1\n");
        ContainerCgroupReleaseProof proof = cg.proof(50, 5_000);
        assertTrue(proof.releaseSafe(0));
        assertEquals(2, cg.procsReads.get());
    }

    @Test
    void aFailedReadDuringTheWaitIsRetriedAndAPersistentOneRefuses() {
        FakeCgroup flaky = new FakeCgroup().selfCgroup("0::/\n")
            .procs("1\n", "<unreadable>", "1\n");
        assertTrue(flaky.proof(50, 5_000).releaseSafe());

        FakeCgroup broken = new FakeCgroup().selfCgroup("0::/\n").procs("1\n", "<unreadable>");
        assertFalse(broken.proof(50, 300).releaseSafe());
        assertTrue(broken.lastLog().contains("the last read of " + PROCS + " failed"),
            broken.lastLog());
    }

    @Test
    void anInterruptedWaitRefusesAndKeepsTheInterrupt() {
        FakeCgroup cg = new FakeCgroup().selfCgroup("0::/\n").procs("1\n", "1\n5\n");
        ContainerCgroupReleaseProof proof = ContainerCgroupReleaseProof.create(SELF, PROCS, PROC,
            1, 50, 5_000, cg::read, () -> cg.nowNanos,
            millis -> { throw new InterruptedException(); }, cg.log::add);
        try {
            assertFalse(proof.releaseSafe());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * ZOMBIES. Measured in the dogfood image on 2026-10-09: with the worker JVM as pid 1 each
     * capture left 7 zombie children (crashpad and chromium) of pid 1, and NONE of them appeared
     * in cgroup.procs, because cgroup v2 drops an exited task from cgroup.procs when it exits,
     * before its parent reaps it. So the proof must not require reaping, and does not: it reads
     * cgroup.procs only and never looks for, waits on or reaps a child. Here the fake /proc still
     * holds zombie entries while the membership file is back at the baseline, and release is
     * proven. (Zombie ACCUMULATION in long watch runs is what docker run --init is for.)
     */
    @Test
    void zombiesAreNotMembersSoTheProofDoesNotRequireReaping() {
        FakeCgroup cg = new FakeCgroup().selfCgroup("0::/\n").procs("1\n", "1\n");
        for (long zombie = 30; zombie < 37; zombie++) {
            cg.files.put(PROC.resolve(Long.toString(zombie)).resolve("stat"),
                zombie + " (chrome_crashpad) Z 1");
        }
        assertTrue(cg.proof(50, 5_000).releaseSafe());
    }

    // ---- wiring: the CLI default stays UNPROVEN ---------------------------------------------

    @Test
    void withoutTheOptInTheDefaultIsUnprovenAndTheCgroupProofIsNeverCreated() {
        AtomicInteger created = new AtomicInteger();
        java.util.function.Supplier<BrewShot.ProcessTreeReleaseProof> factory = () -> {
            created.incrementAndGet();
            return () -> true;
        };
        for (Map<String, String> env : List.of(Map.<String, String>of(),
                Map.of("BREWSHOT_RELEASE_PROOF", ""),
                Map.of("BREWSHOT_RELEASE_PROOF", "off"),
                Map.of("BREWSHOT_RELEASE_PROOF", "argv-sweep"),
                Map.of("BREWSHOT_RELEASE_PROOF", "CONTAINER-CGROUP"))) {
            BrewShot.ProcessTreeReleaseProof proof = BrewShot.launchReleaseProof(env, factory);
            assertTrue(BrewShot.isUnprovenDefaultForTests(proof), env.toString());
            assertFalse(proof.releaseSafe(), env.toString());
        }
        assertEquals(0, created.get(), "no opt-in, no cgroup proof");
    }

    @Test
    void theOptInCreatesTheCgroupProofOncePerLaunch() {
        AtomicInteger created = new AtomicInteger();
        BrewShot.ProcessTreeReleaseProof made = () -> true;
        BrewShot.ProcessTreeReleaseProof proof = BrewShot.launchReleaseProof(
            Map.of("BREWSHOT_RELEASE_PROOF", "container-cgroup"),
            () -> { created.incrementAndGet(); return made; });
        assertSame(made, proof);
        assertEquals(1, created.get());
    }

    @Test
    void aFactoryThatThrowsDegradesToUnproven() {
        BrewShot.ProcessTreeReleaseProof proof = BrewShot.launchReleaseProof(
            Map.of("BREWSHOT_RELEASE_PROOF", "container-cgroup"),
            () -> { throw new IllegalStateException("boom"); });
        assertTrue(BrewShot.isUnprovenDefaultForTests(proof));
    }

    @Test
    void theDefaultSelfCgroupShapeRefusalAcceptsOnlyTheRoot() {
        assertNull(ContainerCgroupReleaseProof.selfCgroupShapeRefusal("0::/\n"));
        assertNull(ContainerCgroupReleaseProof.selfCgroupShapeRefusal("0::/"));
    }

    // ---- the not-consulted report names the right cause --------------------------------------

    @Test
    void notConsultedPointsAtInitOnlyForTheUnreapedTreeGate() {
        FakeCgroup fake = new FakeCgroup().selfCgroup("0::/\n").procs("1\n");
        ContainerCgroupReleaseProof proof = fake.proof(50, 5_000);

        proof.releaseNotConsulted(BrewShot.RELEASE_GATE_TREE_NOT_REAPED);
        assertTrue(fake.lastLog().contains(BrewShot.RELEASE_GATE_TREE_NOT_REAPED), fake.lastLog());
        assertTrue(fake.lastLog().contains("--init"), "a zombie of a pid-1 JVM is the known cause: " + fake.lastLog());

        proof.releaseNotConsulted(BrewShot.RELEASE_GATE_ORPHAN_SURVIVED);
        assertTrue(fake.lastLog().contains(BrewShot.RELEASE_GATE_ORPHAN_SURVIVED), fake.lastLog());
        assertFalse(fake.lastLog().contains("--init"),
            "an argv survivor of the orphan sweep is not a zombie; --init would not help: " + fake.lastLog());
        assertTrue(fake.lastLog().endsWith("release UNPROVEN, profile retained"), fake.lastLog());
    }
}
