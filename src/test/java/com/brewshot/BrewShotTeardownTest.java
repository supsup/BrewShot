package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Descendant-aware teardown (Marlow's report, brewshot room 140): a FAILED
 * bootstrap can leave a Chrome descendant alive — the launcher re-execs or
 * spawns helpers — and a parent-only cleanup then kills just the direct child
 * and deletes the profile dir while the survivor recreates it.
 *
 * <p>These tests drive the REAL production teardown unit
 * ({@link BrewShot.ResourceLease#cleanup(boolean)}, the same pass close(), the
 * launch-failure path, and the shutdown hook all funnel through) against dummy
 * POSIX process trees — {@code /bin/sh} parents with backgrounded children —
 * never a browser. The dummy descendant loops {@code mkdir -p <profile>},
 * standing in for a Chrome helper flushing profile state after cleanup.
 *
 * <p>The leases are the CONTAINED test variant: production launches carry an
 * unproven containment gate and deliberately retain the profile rather than
 * delete it without proof (that gate is pinned by
 * {@code BrewShotLifecycleOwnershipTest}). Supplying the proof here is what
 * lets these tests assert on the delete/recreate race itself, which is the
 * property under test.
 *
 * <p>HONESTY SCOPE of the recreate assertion: what Chrome writes internally
 * post-mortem is not (and cannot honestly be) simulated here. What IS pinned
 * is the cleanup contract that makes the internal write path irrelevant: no
 * member of the launched tree survives teardown, so nothing of ours can
 * recreate the dir. The dummy writer only proves the contract is load-bearing
 * — with a survivor, the dir demonstrably comes back.
 */
class BrewShotTeardownTest {

    /** Writer loop period 50ms; 400 iterations self-terminates the dummy in ~20s
     *  even if an assertion fails before cleanup runs (leak belt). */
    private static final String WRITER_LOOP =
        "n=0; while [ $n -lt 400 ]; do mkdir -p \"$0\"; sleep 0.05; n=$((n+1)); done";

    @TempDir
    Path tmp;

    /** Every process this test starts or observes, force-killed in @AfterEach so
     *  a red run cannot leak the dummy tree past the test. */
    private final List<ProcessHandle> spawned = new ArrayList<>();

    /** Leases registered here, force-released in @AfterEach so a red run cannot
     *  leave one in the JVM-wide LIVE registry for another test to trip over. */
    private final List<BrewShot.ResourceLease> leases = new ArrayList<>();

    @BeforeAll
    static void posixOnly() {
        assumeFalse(System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).startsWith("windows"),
            "dummy fixture is /bin/sh-based");
    }

    @AfterEach
    void reapEverything() {
        for (BrewShot.ResourceLease lease : leases) {
            try { lease.cleanup(false); } catch (RuntimeException ignored) { }
        }
        for (ProcessHandle ph : spawned) {
            ph.descendants().forEach(ProcessHandle::destroyForcibly);
            ph.destroyForcibly();
        }
    }

    // ---- fixture -----------------------------------------------------------

    /**
     * A dummy failed-bootstrap tree: parent {@code sh} backgrounds a writer
     * child that keeps recreating {@code marker} (the stand-in profile dir),
     * prints {@code started}, then hangs — the shape of a bootstrap that
     * spawned helpers and then wedged before DevTools came up.
     */
    private Process spawnHungTree(Path marker) throws IOException {
        Process p = new ProcessBuilder("/bin/sh", "-c",
            WRITER_LOOP + " & echo started; sleep 60", marker.toString())
            .redirectErrorStream(true)
            .start();
        spawned.add(p.toHandle());
        return p;
    }

    /** Register the production lease that owns this dummy process + profile. */
    private BrewShot.ResourceLease lease(Process p, Path marker) {
        BrewShot.ResourceLease lease =
            BrewShot.registerContainedLaunchLeaseForTests(p, marker);
        leases.add(lease);
        return lease;
    }

    /** Block until the process prints {@code started} (writer child is up). */
    private static void awaitStarted(Process p) throws Exception {
        CompletableFuture<String> line = CompletableFuture.supplyAsync(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    p.getInputStream(), StandardCharsets.UTF_8))) {
                return r.readLine();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        String first = line.get(10, TimeUnit.SECONDS);
        assertTrue("started".equals(first), "fixture handshake, got: " + first);
    }

    /** Snapshot the root's descendants once at least {@code min} exist. */
    private List<ProcessHandle> awaitDescendants(ProcessHandle root, int min)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            List<ProcessHandle> d = root.descendants().collect(Collectors.toList());
            if (d.size() >= min) {
                spawned.addAll(d);
                return d;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("fixture never grew " + min + " descendant(s)");
    }

    private static void awaitMarker(Path marker) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(marker)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("writer never created the marker dir");
            }
            Thread.sleep(20);
        }
    }

    private static void assertAllDeadWithin(List<ProcessHandle> handles, long millis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        for (ProcessHandle ph : handles) {
            while (ph.isAlive()) {
                if (System.nanoTime() > deadline) {
                    fail("descendant pid " + ph.pid() + " survived teardown ("
                        + ph.info().commandLine().orElse("?") + ")");
                }
                Thread.sleep(20);
            }
        }
    }

    /** The marker dir must be gone AND stay gone — a survivor recreates it in ≤50ms. */
    private static void assertMarkerStaysDeleted(Path marker) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_000);
        while (System.nanoTime() < deadline) {
            assertFalse(Files.exists(marker),
                "profile dir was RECREATED after teardown — a descendant survived");
            Thread.sleep(25);
        }
    }

    // ---- the report's scenario: failed bootstrap, parent hung --------------

    @Test
    void failedBootstrapTeardownKillsWholeTreeNotJustTheDirectChild() throws Exception {
        Path marker = tmp.resolve("profile");
        Process p = spawnHungTree(marker);
        awaitStarted(p);
        List<ProcessHandle> kids = awaitDescendants(p.toHandle(), 1);
        awaitMarker(marker);

        lease(p, marker).cleanup(false); // the launch-failure path

        assertFalse(p.isAlive(), "direct child must be dead");
        assertAllDeadWithin(kids, 3_000);
    }

    @Test
    void profileDirStaysDeletedAfterFailedBootstrapTeardown() throws Exception {
        Path marker = tmp.resolve("profile");
        Process p = spawnHungTree(marker);
        awaitStarted(p);
        awaitDescendants(p.toHandle(), 1);
        awaitMarker(marker);

        lease(p, marker).cleanup(false);

        assertMarkerStaysDeleted(marker);
    }

    // ---- graceful close() path ---------------------------------------------

    @Test
    void politeCloseTeardownAlsoKillsDescendants() throws Exception {
        Path marker = tmp.resolve("profile");
        Process p = spawnHungTree(marker);
        awaitStarted(p);
        List<ProcessHandle> kids = awaitDescendants(p.toHandle(), 1);
        awaitMarker(marker);

        // close() semantics: SIGTERM + grace first. The dummy sh exits on
        // SIGTERM but its backgrounded writer does not inherit the signal —
        // exactly the survivor shape.
        lease(p, marker).cleanup(true);

        assertFalse(p.isAlive(), "direct child must be dead");
        assertAllDeadWithin(kids, 3_000);
        assertMarkerStaysDeleted(marker);
    }

    @Test
    void sigtermIgnoringDescendantIsKilledByTheForcibleEscalation() throws Exception {
        // Discriminator for the FORCIBLE pass specifically (Commander-added after a
        // mutation check: with the forcible descendant pass disabled, every other
        // fixture stayed green — their dummies die politely to SIGTERM, or carry the
        // profile PATH in argv and get mopped by the orphan sweep. This one traps
        // TERM *and* references the profile only RELATIVELY from a working dir, so
        // neither the polite pass nor the argv-matching sweep can reap it: only the
        // forcible descendant pass can).
        Path marker = tmp.resolve("profile");
        Process p = new ProcessBuilder("/bin/sh", "-c",
            "sh -c 'trap \"\" TERM; n=0; while [ $n -lt 400 ]; do mkdir -p profile; "
                + "sleep 0.05; n=$((n+1)); done' & echo started; sleep 60")
            .directory(tmp.toFile())
            .redirectErrorStream(true)
            .start();
        spawned.add(p.toHandle());
        awaitStarted(p);
        List<ProcessHandle> kids = awaitDescendants(p.toHandle(), 1);
        awaitMarker(marker);

        // polite path: TERM is IGNORED by the child
        lease(p, marker).cleanup(true);

        assertFalse(p.isAlive(), "direct child must be dead");
        assertAllDeadWithin(kids, 3_000);
        assertMarkerStaysDeleted(marker);
    }

    // ---- parent already died: the reparented-orphan half -------------------

    @Test
    void orphanReparentedByDeadParentIsStillSweptByProfilePath() throws Exception {
        Path marker = tmp.resolve("profile");
        // Parent backgrounds the writer, lingers 2s (so we can snapshot the
        // tree), then exits ON ITS OWN — the writer is reparented to init and
        // vanishes from root.descendants(). Only a sweep keyed on the unique
        // profile path (present in the writer's argv, as --user-data-dir=<dir>
        // is in every real Chrome helper's) can still find it.
        Process p = new ProcessBuilder("/bin/sh", "-c",
            WRITER_LOOP + " & echo started; sleep 2", marker.toString())
            .redirectErrorStream(true)
            .start();
        spawned.add(p.toHandle());
        awaitStarted(p);
        List<ProcessHandle> kids = awaitDescendants(p.toHandle(), 1);
        awaitMarker(marker);
        assertTrue(p.waitFor(10, TimeUnit.SECONDS), "fixture parent should exit alone");

        // The lease is registered only AFTER the parent died, so its retained
        // handle snapshot is EMPTY — the reparented writer is invisible to
        // terminateProcess. This is the case the sweep exists for; without it
        // the profile is deleted and the survivor recreates it.
        lease(p, marker).cleanup(false);

        assertAllDeadWithin(kids, 3_000);
        assertMarkerStaysDeleted(marker);
    }
}
