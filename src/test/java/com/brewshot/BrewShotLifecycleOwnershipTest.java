package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pure process/profile ownership races. These invoke the same cleanup pass as
 * the JVM shutdown hook, but never discover or launch Chrome.
 */
class BrewShotLifecycleOwnershipTest {

    @Test
    void shutdownProbeProfileReflectionContractRemainsStable(@TempDir Path temp)
            throws Exception {
        FakeProcess process = new FakeProcess(1);
        Path profile = profile(temp, "probe-reflection");
        BrewShot shot = new BrewShot(
            process, profile, new FakeWebSocket(),
            new java.util.concurrent.LinkedBlockingQueue<>(), 40);
        try {
            var profileField = BrewShot.class.getDeclaredField("profileDir");
            profileField.setAccessible(true);
            assertEquals(profile, profileField.get(shot),
                "ShutdownHookProbeMain reflects this exact compatibility field");
        } finally {
            shot.close();
        }
    }

    @Test
    void shutdownDuringDiscoveryFindsThePostStartLease(@TempDir Path temp)
            throws Exception {
        FakeProcess process = new FakeProcess(1);
        Path profile = profile(temp, "discovery");
        BrewShot.ResourceLease lease =
            BrewShot.registerContainedLaunchLeaseForTests(process, profile);

        assertTrue(lease.isOwned(), "the process must be owned before stderr discovery");
        BrewShot.runShutdownCleanupForTests();

        assertFalse(process.isAlive());
        assertFalse(Files.exists(profile));
        assertFalse(lease.isOwned());
        assertFalse(BrewShot.ownsResources(process, profile));
    }

    @Test
    void admittedStartRegistersBeforeShutdownCanSnapshot(@TempDir Path temp)
            throws Exception {
        FakeProcess process = new FakeProcess(1);
        Path profile = profile(temp, "admitted-start");
        CountDownLatch afterStartBeforeRegistration = new CountDownLatch(1);
        CountDownLatch allowRegistration = new CountDownLatch(1);
        CountDownLatch shutdownAttempted = new CountDownLatch(1);
        CountDownLatch shutdownReturned = new CountDownLatch(1);
        AtomicReference<BrewShot.ResourceLease> leaseRef = new AtomicReference<>();
        AtomicReference<Throwable> launchFailure = new AtomicReference<>();
        AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();

        Thread launch = new Thread(() -> {
            try {
                leaseRef.set(BrewShot.startContainedOwnedProcessForTests(
                    profile,
                    () -> process,
                    BrewShotLifecycleOwnershipTest::deleteProfile,
                    () -> {
                        afterStartBeforeRegistration.countDown();
                        awaitUnchecked(allowRegistration);
                    }));
            } catch (Throwable t) {
                launchFailure.set(t);
            }
        }, "brewshot-admitted-start");
        Thread shutdown = new Thread(() -> {
            shutdownAttempted.countDown();
            try { BrewShot.runJvmShutdownCleanupForTests(); }
            catch (Throwable t) { shutdownFailure.set(t); }
            finally { shutdownReturned.countDown(); }
        }, "brewshot-admitted-start-shutdown");

        launch.start();
        assertTrue(afterStartBeforeRegistration.await(1, TimeUnit.SECONDS));
        shutdown.start();
        assertTrue(shutdownAttempted.await(1, TimeUnit.SECONDS));
        assertTrue(awaitThreadState(shutdown, Thread.State.BLOCKED),
            "shutdown must be contending on the admission monitor");
        assertFalse(shutdownReturned.await(100, TimeUnit.MILLISECONDS),
            "shutdown must wait for admitted start + registration under the shared fence");

        allowRegistration.countDown();
        launch.join(1_000);
        shutdown.join(2_000);

        assertFalse(launch.isAlive());
        assertFalse(shutdown.isAlive());
        assertNull(launchFailure.get());
        assertNull(shutdownFailure.get());
        assertNotNull(leaseRef.get());
        assertFalse(process.isAlive());
        assertFalse(Files.exists(profile));
        assertFalse(leaseRef.get().isOwned(),
            "the waiting shutdown invocation must see and reconcile the admitted lease");
    }

    @Test
    void shutdownWinningAdmissionRejectsWithoutStarting(@TempDir Path temp)
            throws Exception {
        FakeProcess ownedProcess = new FakeProcess(1);
        Path ownedProfile = profile(temp, "shutdown-owner");
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CountDownLatch allowCleanup = new CountDownLatch(1);
        BrewShot.registerContainedLaunchLeaseForTests(
            ownedProcess, ownedProfile, path -> {
            cleanupEntered.countDown();
            awaitUnchecked(allowCleanup);
            deleteProfile(path);
        });
        AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();
        Thread shutdown = new Thread(() -> {
            try { BrewShot.runJvmShutdownCleanupForTests(); }
            catch (Throwable t) { shutdownFailure.set(t); }
        }, "brewshot-shutdown-wins-admission");
        shutdown.start();

        try {
            assertTrue(cleanupEntered.await(1, TimeUnit.SECONDS));
            AtomicInteger starts = new AtomicInteger();
            FakeProcess rejectedProcess = new FakeProcess(1);
            Path rejectedProfile = temp.resolve("shutdown-rejected");

            IllegalStateException rejected = assertThrows(IllegalStateException.class, () ->
                BrewShot.startOwnedProcess(
                    rejectedProfile,
                    () -> {
                        starts.incrementAndGet();
                        return rejectedProcess;
                    },
                    BrewShotLifecycleOwnershipTest::deleteProfile,
                    () -> { }));

            assertTrue(rejected.getMessage().contains("shutdown"), rejected.getMessage());
            assertEquals(0, starts.get(),
                "shutdown-winning admission must reject before ProcessBuilder.start");
            assertEquals(0, rejectedProcess.destroyForciblyCalls.get());
        } finally {
            allowCleanup.countDown();
            shutdown.join(2_000);
        }

        assertFalse(shutdown.isAlive());
        assertNull(shutdownFailure.get());
        assertFalse(Files.exists(ownedProfile));
    }

    @Test
    void postStartFailureCannotDeleteProfileWhileReapRemainsUnresolved(
            @TempDir Path temp) throws Exception {
        FakeProcess process = new FakeProcess(Integer.MAX_VALUE);
        Path profile = profile(temp, "post-start-failure");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
            BrewShot.startContainedLaunchProcessForTests(
                profile,
                () -> process,
                () -> {
                    throw new IllegalStateException("injected post-start failure");
                }));

        assertTrue(failure.getMessage().contains("injected post-start failure"));
        assertTrue(process.isAlive(),
            "the fake deliberately survives the failed post-start cleanup");
        assertTrue(Files.exists(profile),
            "a still-owned live process must retain its profile");
        assertTrue(BrewShot.ownsResources(process, profile),
            "post-start failure must remain registered for retry in this JVM"
                + " when reap fails");

        process.exitParentAndOrphanChildren();
        BrewShot.runShutdownCleanupForTests();
        assertFalse(Files.exists(profile));
        assertFalse(BrewShot.ownsResources(process, profile));
    }

    @Test
    void preStartFailureDeletesTheUnownedProfile(@TempDir Path temp)
            throws Exception {
        Path profile = profile(temp, "pre-start-failure");
        AtomicInteger starts = new AtomicInteger();

        IOException failure = assertThrows(IOException.class, () ->
            BrewShot.startLaunchProcess(
                profile,
                () -> {
                    starts.incrementAndGet();
                    throw new IOException("injected starter failure");
                },
                () -> { }));

        assertTrue(failure.getMessage().contains("injected starter failure"));
        assertEquals(1, starts.get());
        assertFalse(Files.exists(profile),
            "without a returned Process there is no lease, so the profile is unowned");
    }

    @Test
    void shutdownDuringNeverCompletingConnectUsesNativeTimeoutAndAbortsLateSocket(
            @TempDir Path temp) throws Exception {
        FakeProcess process = new FakeProcess(1);
        Path profile = profile(temp, "connect");
        NonCancellingFuture connecting = new NonCancellingFuture();
        RecordingBuilder builder = new RecordingBuilder(connecting);
        AtomicBoolean ownedInsideConnector = new AtomicBoolean();

        IOException failure = assertThrows(IOException.class, () ->
            BrewShot.finishLaunch(process, profile,
                "ws://127.0.0.1:1/devtools/browser/fake",
                (uri, listener, timeout) -> {
                    ownedInsideConnector.set(
                        BrewShot.ownsResources(process, profile));
                    BrewShot.runShutdownCleanupForTests();
                    return BrewShot.connectWebSocket(
                        builder, uri, listener, timeout);
                },
                25));

        assertTrue(failure.getMessage().contains("connect timed out"), failure.getMessage());
        assertTrue(ownedInsideConnector.get(),
            "connect must begin with the launch lease registered");
        assertEquals(Duration.ofMillis(25), builder.connectTimeout,
            "the same budget must reach WebSocket.Builder.connectTimeout");
        assertTrue(builder.buildCalled.get());
        assertFalse(process.isAlive());
        assertFalse(Files.exists(profile));
        assertFalse(BrewShot.ownsResources(process, profile));

        FakeWebSocket lateSocket = new FakeWebSocket();
        assertTrue(connecting.complete(lateSocket));
        assertEquals(1, lateSocket.abortCalls.get(),
            "a socket completing after the caller timed out must be aborted");
    }

    @Test
    void shutdownDuringBootstrapCannotReturnAnUnownedClient(@TempDir Path temp)
            throws Exception {
        FakeProcess process = new FakeProcess(1);
        Path profile = profile(temp, "bootstrap");
        AtomicReference<BootstrapWebSocket> socketRef = new AtomicReference<>();
        AtomicBoolean ownedAtBootstrap = new AtomicBoolean();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
            BrewShot.finishLaunch(process, profile,
                "ws://127.0.0.1:1/devtools/browser/fake",
                (uri, listener, timeout) -> {
                    BootstrapWebSocket socket = new BootstrapWebSocket(
                        listener, "Target.attachToTarget", () -> {
                            ownedAtBootstrap.set(
                                BrewShot.ownsResources(process, profile));
                            BrewShot.runShutdownCleanupForTests();
                        });
                    socketRef.set(socket);
                    return CompletableFuture.completedFuture(socket);
                },
                100));

        assertTrue(failure.getMessage().contains("cleaned up while launch was in progress"),
            failure.getMessage());
        assertTrue(ownedAtBootstrap.get());
        assertFalse(process.isAlive());
        assertFalse(Files.exists(profile));
        assertFalse(BrewShot.ownsResources(process, profile));
        assertNotNull(socketRef.get());
        assertEquals(1, socketRef.get().abortCalls.get());
    }

    @Test
    void shutdownDuringGracefulCloseSerializesWithCloseAndKeepsOwnership(
            @TempDir Path temp) throws Exception {
        FakeProcess process = new FakeProcess(1);
        Path profile = profile(temp, "close");
        FakeWebSocket socket = new FakeWebSocket();
        socket.closeFuture = new CompletableFuture<>();
        BrewShot shot = new BrewShot(
            process, profile, socket, new java.util.concurrent.LinkedBlockingQueue<>(), 1_000);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();

        Thread closer = new Thread(() -> {
            try { shot.close(); }
            catch (Throwable t) { closeFailure.set(t); }
        }, "brewshot-close-shutdown-race");
        closer.start();
        assertTrue(socket.closeStarted.await(1, TimeUnit.SECONDS));
        assertTrue(BrewShot.ownsResources(process, profile),
            "sendClose must not deregister process/profile ownership");

        BrewShot.runShutdownCleanupForTests();
        assertFalse(process.isAlive());
        assertFalse(Files.exists(profile));
        assertFalse(BrewShot.ownsResources(process, profile));

        socket.closeFuture.complete(socket);
        closer.join(1_000);
        assertFalse(closer.isAlive());
        assertNull(closeFailure.get());

        shot.close();
        assertEquals(1, socket.closeCalls.get(), "the close frame remains once-only");
    }

    @Test
    void processSurvivingForcedReapRemainsOwnedUntilRetry(@TempDir Path temp)
            throws Exception {
        FakeProcess process = new FakeProcess(2);
        Path profile = profile(temp, "forced-reap");
        BrewShot.ResourceLease lease =
            BrewShot.registerContainedLaunchLeaseForTests(process, profile);

        BrewShot.runShutdownCleanupForTests();
        assertTrue(process.isAlive(), "the fake ignores the first destroyForcibly");
        assertTrue(Files.exists(profile), "a live process still owns its profile");
        assertTrue(lease.isOwned(),
            "failed reap must remain registered for retry in this JVM");

        BrewShot.runShutdownCleanupForTests();
        assertFalse(process.isAlive());
        assertFalse(Files.exists(profile));
        assertFalse(lease.isOwned());
        assertEquals(2, process.destroyForciblyCalls.get());
    }

    @Test
    void actualShutdownBudgetExhaustionRetainsOwnership(@TempDir Path temp)
            throws Exception {
        FakeProcess process = new FakeProcess(Integer.MAX_VALUE);
        Path profile = profile(temp, "shutdown-exhausted");
        BrewShot.ResourceLease lease =
            BrewShot.registerContainedLaunchLeaseForTests(process, profile);

        long started = System.nanoTime();
        BrewShot.runJvmShutdownCleanupForTests();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(3, process.destroyForciblyCalls.get(),
            "one shutdown invocation has a fixed reconciliation pass bound");
        assertTrue(elapsedMs < 3_000,
            "three 500ms attempt slices must stay inside the 5s global wait budget");
        assertTrue(process.isAlive());
        assertTrue(Files.exists(profile));
        assertTrue(lease.isOwned(),
            "exhausted cleanup must retain its in-memory owner for this JVM");

        process.exitParentAndOrphanChildren();
        BrewShot.runShutdownCleanupForTests();
        assertFalse(Files.exists(profile));
        assertFalse(lease.isOwned());
    }

    @Test
    void actualShutdownDeadlineIsSharedAcrossSequentialLeases(@TempDir Path temp)
            throws Exception {
        List<FakeProcess> processes = new java.util.ArrayList<>();
        List<Path> profiles = new java.util.ArrayList<>();
        List<BrewShot.ResourceLease> leases = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            FakeProcess process = new FakeProcess(Integer.MAX_VALUE);
            Path profile = profile(temp, "global-bound-" + i);
            processes.add(process);
            profiles.add(profile);
            leases.add(
                BrewShot.registerContainedLaunchLeaseForTests(process, profile));
        }

        long started = System.nanoTime();
        BrewShot.runJvmShutdownCleanupForTests();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        int forceCalls =
            processes.stream().mapToInt(p -> p.destroyForciblyCalls.get()).sum();

        assertTrue(elapsedMs >= 3_500 && elapsedMs < 7_500,
            "sequential lease waits must consume one shared 5s deadline, elapsed="
                + elapsedMs + "ms");
        assertTrue(forceCalls < 12,
            "without a global deadline all 4 leases would receive all 3 slices");
        for (int i = 0; i < leases.size(); i++) {
            assertTrue(processes.get(i).isAlive());
            assertTrue(Files.exists(profiles.get(i)));
            assertTrue(leases.get(i).isOwned());
        }

        for (FakeProcess process : processes) {
            process.exitParentAndOrphanChildren();
        }
        BrewShot.runShutdownCleanupForTests();
        for (int i = 0; i < leases.size(); i++) {
            assertFalse(Files.exists(profiles.get(i)));
            assertFalse(leases.get(i).isOwned());
        }
    }

    @Test
    void deadRegistrationWithoutContainmentProofCannotAuthorizeRelease(
            @TempDir Path temp)
            throws Exception {
        FakeHandle parentHandle = new FakeHandle(31, false, List.of());
        FakeProcess process = new FakeProcess(1, false, parentHandle);
        Path profile = profile(temp, "no-live-snapshot");
        AtomicBoolean containmentClosed = new AtomicBoolean();
        BrewShot.ResourceLease lease =
            BrewShot.registerLaunchLeaseWithReleaseProofForTests(
                process, profile, BrewShotLifecycleOwnershipTest::deleteProfile,
                containmentClosed::get);

        BrewShot.runShutdownCleanupForTests();

        assertTrue(Files.exists(profile),
            "a dead parent does not prove that no reparented helper survives");
        assertTrue(lease.isOwned());

        containmentClosed.set(true);
        BrewShot.runShutdownCleanupForTests();
        assertFalse(Files.exists(profile));
        assertFalse(lease.isOwned());
    }

    @Test
    void unstableEnumerationCannotBypassContainmentProof(
            @TempDir Path temp)
            throws Exception {
        FakeHandle parentHandle = new FakeHandle(32, true, List.of());
        parentHandle.failDescendantEnumeration();
        FakeProcess process = new FakeProcess(1, true, parentHandle);
        Path profile = profile(temp, "unstable-live-snapshot");
        AtomicBoolean containmentClosed = new AtomicBoolean();
        BrewShot.ResourceLease lease =
            BrewShot.registerLaunchLeaseWithReleaseProofForTests(
                process, profile, BrewShotLifecycleOwnershipTest::deleteProfile,
                containmentClosed::get);

        process.exitParentAndOrphanChildren();
        BrewShot.runShutdownCleanupForTests();

        assertTrue(Files.exists(profile),
            "a failed JDK enumeration cannot substitute for containment proof");
        assertTrue(lease.isOwned());

        parentHandle.allowDescendantEnumeration();
        containmentClosed.set(true);
        BrewShot.runShutdownCleanupForTests();
        assertFalse(Files.exists(profile));
        assertFalse(lease.isOwned());
    }

    @Test
    void liveRegistrationSnapshotSurvivesParentDeathAndReparenting(@TempDir Path temp)
            throws Exception {
        FakeHandle helper = new FakeHandle(42, true, List.of());
        FakeHandle parentHandle = new FakeHandle(41, true, List.of(helper));
        FakeProcess process = new FakeProcess(1, true, parentHandle);
        Path profile = profile(temp, "reaped-parent-helper");
        AtomicBoolean sweptBeforeDelete = new AtomicBoolean();
        BrewShot.ResourceLease lease = BrewShot.registerContainedLaunchLeaseForTests(
            process, profile, path -> {
                sweptBeforeDelete.set(helper.destroyForciblyCalls.get() == 1);
                deleteProfile(path);
            });

        process.exitParentAndOrphanChildren();
        assertFalse(process.isAlive());
        assertEquals(0, parentHandle.descendants().count(),
            "the dead parent no longer exposes its reparented child");
        BrewShot.runShutdownCleanupForTests();

        assertEquals(1, helper.destroyForciblyCalls.get(),
            "cleanup must use the child retained while the parent was alive");
        assertTrue(sweptBeforeDelete.get(),
            "helper sweep must precede profile deletion and lease release");
        assertFalse(Files.exists(profile));
        assertFalse(lease.isOwned());
    }

    @Test
    void gracefulParentExitAwaitsAsyncOrphanBeforeProfileRelease(@TempDir Path temp)
            throws Exception {
        FakeHandle helper = new FakeHandle(52, true, List.of(), false);
        FakeHandle parentHandle = new FakeHandle(51, true, List.of(helper));
        FakeProcess process = new FakeProcess(1, true, parentHandle, true);
        Path profile = profile(temp, "graceful-async-helper");
        BrewShot.ResourceLease lease =
            BrewShot.registerContainedLaunchLeaseForTests(process, profile);
        BrewShot shot = new BrewShot(
            lease, new FakeWebSocket(),
            new java.util.concurrent.LinkedBlockingQueue<>(), 40);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();

        Thread closer = new Thread(() -> {
            try { shot.close(); }
            catch (Throwable t) { closeFailure.set(t); }
        }, "brewshot-graceful-async-helper");
        closer.start();

        assertTrue(helper.forceStarted.await(1, TimeUnit.SECONDS),
            "the child retained before graceful parent exit must be force-signalled");
        assertTrue(helper.isAlive(),
            "the discriminator child exits asynchronously, not inside destroyForcibly");
        assertTrue(Files.exists(profile),
            "profile deletion must wait for the captured child to actually exit");
        assertTrue(BrewShot.ownsResources(process, profile),
            "the captured live child must keep ownership registered");

        helper.exitNow();
        closer.join(1_000);

        assertFalse(closer.isAlive());
        assertNull(closeFailure.get());
        assertFalse(helper.isAlive());
        assertFalse(Files.exists(profile));
        assertFalse(lease.isOwned());
    }

    @Test
    void childSpawnedDuringDestroyBlocksUnprovenProfileRelease(@TempDir Path temp)
            throws Exception {
        FakeHandle capturedHelper = new FakeHandle(62, true, List.of());
        FakeHandle lateHelper = new FakeHandle(63, true, List.of());
        FakeHandle parentHandle =
            new FakeHandle(61, true, List.of(capturedHelper));
        AtomicBoolean containmentClosed = new AtomicBoolean();
        FakeProcess process = new FakeProcess(
            1, true, parentHandle, true,
            () -> parentHandle.replaceDescendants(
                List.of(capturedHelper, lateHelper)));
        Path profile = profile(temp, "late-destroy-helper");
        BrewShot.ResourceLease lease =
            BrewShot.registerLaunchLeaseWithReleaseProofForTests(
                process, profile, BrewShotLifecycleOwnershipTest::deleteProfile,
                containmentClosed::get);
        BrewShot shot = new BrewShot(
            lease, new FakeWebSocket(),
            new java.util.concurrent.LinkedBlockingQueue<>(), 40);

        shot.close();

        assertFalse(process.isAlive());
        assertFalse(capturedHelper.isAlive(),
            "known handles must still be terminated before release is considered");
        assertEquals(1, capturedHelper.destroyForciblyCalls.get());
        assertTrue(lateHelper.isAlive(),
            "the post-snapshot child escaped the dead parent's descendants view");
        assertEquals(0, lateHelper.destroyForciblyCalls.get(),
            "an unobserved reparented child cannot be targeted by a stale snapshot");
        assertTrue(Files.exists(profile),
            "without closed tree membership the profile must not be deleted");
        assertTrue(lease.isOwned(),
            "without closed tree membership the lease must remain owned in this JVM");

        // Supply the proof only after the discriminator's external owner has
        // terminated the previously-unobservable helper.
        lateHelper.exitNow();
        containmentClosed.set(true);
        BrewShot.runShutdownCleanupForTests();
        assertFalse(Files.exists(profile));
        assertFalse(lease.isOwned());
    }

    @Test
    void realReparentedShutdownChildBlocksUnprovenProfileRelease(@TempDir Path temp)
            throws Exception {
        Path profile = profile(temp, "real-late-shutdown-helper");
        Path receipt = temp.resolve("late-helper-pid.txt");
        Path helperReady = temp.resolve("late-helper-ready.txt");
        String javaBin =
            Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process parent = new NormalExitProcess(new ProcessBuilder(
            javaBin,
            "-Djava.awt.headless=true",
            "-cp", System.getProperty("java.class.path"),
            LateSpawnOnShutdownProbeMain.class.getName(),
            "parent", receipt.toString(), helperReady.toString())
            .redirectErrorStream(true)
            .start());
        long parentPid = parent.pid();
        AtomicBoolean containmentClosed = new AtomicBoolean();
        BrewShot.ResourceLease lease = null;
        ProcessHandle helper = null;
        try {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                        parent.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                do {
                    line = reader.readLine();
                } while (line != null && !"READY".equals(line));
                assertEquals("READY", line,
                    "real parent probe did not become ready");
                lease = BrewShot.registerLaunchLeaseWithReleaseProofForTests(
                    parent, profile, BrewShotLifecycleOwnershipTest::deleteProfile,
                    containmentClosed::get);
                lease.cleanup(true);
            }

            assertTrue(parent.waitFor(5, TimeUnit.SECONDS),
                "real parent did not exit after graceful destroy");
            assertTrue(Files.exists(receipt),
                "the shutdown hook did not record its late child");
            long helperPid = Long.parseLong(Files.readString(receipt).trim());
            helper = ProcessHandle.of(helperPid).orElseThrow(
                () -> new AssertionError("late helper process disappeared"));
            assertTrue(helper.isAlive(),
                "the late child must outlive the parent discriminator");
            assertTrue(helper.parent().map(p -> p.pid() != parentPid).orElse(true),
                "the late child must have reparented away from the dead parent");
            assertTrue(Files.exists(profile),
                "an unobservable real child must block profile deletion");
            assertTrue(lease.isOwned(),
                "an unobservable real child must retain JVM-lifetime ownership");
        } finally {
            if (parent.isAlive()) {
                parent.destroyForcibly();
                parent.waitFor(5, TimeUnit.SECONDS);
            }
            if (helper == null && Files.exists(receipt)) {
                try {
                    helper = ProcessHandle.of(
                        Long.parseLong(Files.readString(receipt).trim()))
                        .orElse(null);
                } catch (RuntimeException ignored) { }
            }
            if (helper != null && helper.isAlive()) {
                helper.destroyForcibly();
                try { helper.onExit().get(5, TimeUnit.SECONDS); }
                catch (java.util.concurrent.ExecutionException
                        | java.util.concurrent.TimeoutException ignored) { }
            }
            if (lease != null) {
                assertFalse(parent.isAlive(),
                    "the discriminator parent must be dead before test reclamation");
                assertTrue(helper == null || !helper.isAlive(),
                    "the discriminator child must be dead before test reclamation");
                containmentClosed.set(true);
                lease.cleanup(false);
            } else if (Files.exists(profile)) {
                deleteProfile(profile);
            }
        }

        assertFalse(Files.exists(profile));
        assertFalse(lease.isOwned());
    }

    @Test
    void actualJvmShutdownHookRetriesContainedReapAndDeleteWithinOneInvocation(
            @TempDir Path temp) throws Exception {
        Path profile = temp.resolve("hook-profile");
        Path receipt = temp.resolve("hook-receipt.txt");
        String javaBin =
            Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process probe = new ProcessBuilder(
            javaBin,
            "-Djava.awt.headless=true",
            "-cp", System.getProperty("java.class.path"),
            "com.brewshot.ShutdownLifecycleRetryProbeMain",
            profile.toString(),
            receipt.toString())
            .redirectErrorStream(true)
            .start();

        assertTrue(probe.waitFor(10, TimeUnit.SECONDS),
            "pure-Java shutdown-hook probe did not exit within the global bound");
        String output = new String(
            probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, probe.exitValue(), output);
        assertFalse(Files.exists(profile),
            "the controlled fake supplies containment proof, so one real JVM hook"
                + " invocation must finish both retryable stages");
        assertEquals(List.of("force=1", "force=2", "delete=1", "delete=2"),
            Files.readAllLines(receipt),
            "the actual hook must reconcile reap, then delete, without a second invocation");
    }

    @Test
    void actualJvmExitReclaimsUnprovenLeaseButLeavesProfileOnDisk(
            @TempDir Path temp) throws Exception {
        Path profile = temp.resolve("unproven-hook-profile");
        Path receipt = temp.resolve("unproven-hook-receipt.txt");
        String javaBin =
            Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process probe = new ProcessBuilder(
            javaBin,
            "-Djava.awt.headless=true",
            "-cp", System.getProperty("java.class.path"),
            "com.brewshot.ShutdownLifecycleRetryProbeMain",
            profile.toString(),
            receipt.toString(),
            "unproven")
            .redirectErrorStream(true)
            .start();

        assertTrue(probe.waitFor(10, TimeUnit.SECONDS),
            "unproven pure-Java shutdown-hook probe did not exit");
        String output = new String(
            probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, probe.exitValue(), output);
        assertTrue(Files.exists(profile),
            "JVM exit reclaims only the in-memory registry; without release proof"
                + " the profile must remain on disk");
        assertEquals(List.of("force=1", "force=2"),
            Files.readAllLines(receipt),
            "known handles must still be reaped, but deletion must not run");

        // Controlled fake has no unobserved child; the outer test owns cleanup.
        deleteProfile(profile);
    }

    @Test
    void profileDeleteFailureRemainsOwnedUntilRetry(@TempDir Path temp)
            throws Exception {
        FakeProcess process = new FakeProcess(1);
        Path profile = profile(temp, "delete-retry");
        AtomicInteger deleteCalls = new AtomicInteger();
        CountDownLatch firstDeleteStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstDelete = new CountDownLatch(1);
        BrewShot.ResourceLease lease = BrewShot.registerContainedLaunchLeaseForTests(
            process, profile, path -> {
                if (deleteCalls.incrementAndGet() == 1) {
                    firstDeleteStarted.countDown();
                    try {
                        if (!releaseFirstDelete.await(1, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("delete-race test timed out");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    throw new IllegalStateException("injected delete failure");
                }
                deleteProfile(path);
            });
        FakeWebSocket socket = new FakeWebSocket();
        BrewShot shot = new BrewShot(
            lease, socket, new java.util.concurrent.LinkedBlockingQueue<>(), 40);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();

        Thread closer = new Thread(() -> {
            try { shot.close(); }
            catch (Throwable t) { closeFailure.set(t); }
        }, "brewshot-delete-failure-close");
        closer.start();
        assertTrue(firstDeleteStarted.await(1, TimeUnit.SECONDS));

        assertFalse(process.isAlive());
        assertTrue(Files.exists(profile));
        assertTrue(BrewShot.ownsResources(process, profile),
            "a surviving profile must keep its JVM-lifetime cleanup owner");

        Thread shutdown = new Thread(() -> {
            try { BrewShot.runShutdownCleanupForTests(); }
            catch (Throwable t) { shutdownFailure.set(t); }
        }, "brewshot-delete-failure-shutdown");
        shutdown.start();
        releaseFirstDelete.countDown();
        closer.join(1_000);
        shutdown.join(1_000);

        assertFalse(closer.isAlive());
        assertFalse(shutdown.isAlive());
        assertNull(closeFailure.get());
        assertNull(shutdownFailure.get());
        assertFalse(Files.exists(profile));
        assertFalse(lease.isOwned());
        assertEquals(2, deleteCalls.get());
        shot.close();
        assertEquals(1, socket.closeCalls.get());
    }

    private static Path profile(Path temp, String name) throws IOException {
        Path profile = Files.createDirectory(temp.resolve(name));
        Files.writeString(profile.resolve("marker"), "owned by fake Chrome");
        return profile;
    }

    private static void deleteProfile(Path profile) {
        try {
            Files.deleteIfExists(profile.resolve("marker"));
            Files.deleteIfExists(profile);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("lifecycle race test timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static boolean awaitThreadState(Thread thread, Thread.State expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            if (thread.getState() == expected) { return true; }
            Thread.sleep(1);
        }
        return thread.getState() == expected;
    }

    private static class FakeWebSocket implements WebSocket {
        private CompletableFuture<WebSocket> closeFuture =
            CompletableFuture.completedFuture(this);
        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final AtomicInteger closeCalls = new AtomicInteger();
        final AtomicInteger abortCalls = new AtomicInteger();

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            closeCalls.incrementAndGet();
            closeStarted.countDown();
            return closeFuture;
        }

        @Override
        public void request(long n) { }

        @Override
        public String getSubprotocol() { return ""; }

        @Override
        public boolean isOutputClosed() { return false; }

        @Override
        public boolean isInputClosed() { return false; }

        @Override
        public void abort() {
            abortCalls.incrementAndGet();
        }
    }

    private static final class BootstrapWebSocket extends FakeWebSocket {
        private final WebSocket.Listener listener;
        private final String shutdownMethod;
        private final Runnable shutdown;
        private final AtomicBoolean shutdownRan = new AtomicBoolean();

        BootstrapWebSocket(WebSocket.Listener listener, String shutdownMethod,
                           Runnable shutdown) {
            this.listener = listener;
            this.shutdownMethod = shutdownMethod;
            this.shutdown = shutdown;
        }

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sent =
                (Map<String, Object>) MiniJson.parse(data.toString());
            int id = ((Number) sent.get("id")).intValue();
            String method = String.valueOf(sent.get("method"));
            if (shutdownMethod.equals(method) && shutdownRan.compareAndSet(false, true)) {
                shutdown.run();
            }

            String result = switch (method) {
                case "Target.createTarget" -> "{\"targetId\":\"target\"}";
                case "Target.attachToTarget" -> "{\"sessionId\":\"session\"}";
                default -> "{}";
            };
            listener.onText(this,
                "{\"id\":" + id + ",\"result\":" + result + "}", true);
            return CompletableFuture.completedFuture(this);
        }
    }

    private static final class NonCancellingFuture
            extends CompletableFuture<WebSocket> {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }
    }

    private static final class RecordingBuilder implements WebSocket.Builder {
        private final CompletableFuture<WebSocket> connecting;
        private final AtomicBoolean buildCalled = new AtomicBoolean();
        private Duration connectTimeout;

        RecordingBuilder(CompletableFuture<WebSocket> connecting) {
            this.connecting = connecting;
        }

        @Override
        public WebSocket.Builder header(String name, String value) {
            return this;
        }

        @Override
        public WebSocket.Builder connectTimeout(Duration timeout) {
            this.connectTimeout = timeout;
            return this;
        }

        @Override
        public WebSocket.Builder subprotocols(String mostPreferred,
                                              String... lesserPreferred) {
            return this;
        }

        @Override
        public CompletableFuture<WebSocket> buildAsync(
                URI uri, WebSocket.Listener listener) {
            buildCalled.set(true);
            return connecting;
        }
    }

    private static final class FakeProcess extends Process {
        private final int forceKillsAfter;
        private final AtomicBoolean alive;
        private final ProcessHandle handle;
        private final boolean gracefulKillsParent;
        private final Runnable beforeGracefulExit;
        private final AtomicInteger destroyForciblyCalls = new AtomicInteger();

        FakeProcess(int forceKillsAfter) {
            this(forceKillsAfter, true,
                new FakeHandle(100 + forceKillsAfter, true, List.of()), true);
        }

        FakeProcess(int forceKillsAfter, boolean initiallyAlive,
                    ProcessHandle handle) {
            this(forceKillsAfter, initiallyAlive, handle, false);
        }

        FakeProcess(int forceKillsAfter, boolean initiallyAlive,
                    ProcessHandle handle, boolean gracefulKillsParent) {
            this(forceKillsAfter, initiallyAlive, handle, gracefulKillsParent,
                () -> { });
        }

        FakeProcess(int forceKillsAfter, boolean initiallyAlive,
                    ProcessHandle handle, boolean gracefulKillsParent,
                    Runnable beforeGracefulExit) {
            this.forceKillsAfter = forceKillsAfter;
            this.alive = new AtomicBoolean(initiallyAlive);
            this.handle = handle;
            this.gracefulKillsParent = gracefulKillsParent;
            this.beforeGracefulExit = beforeGracefulExit;
        }

        @Override
        public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }

        @Override
        public InputStream getInputStream() { return InputStream.nullInputStream(); }

        @Override
        public InputStream getErrorStream() { return InputStream.nullInputStream(); }

        @Override
        public int waitFor() throws InterruptedException {
            while (alive.get()) { Thread.sleep(1); }
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) { throw new IllegalThreadStateException("still alive"); }
            return 0;
        }

        @Override
        public void destroy() {
            if (gracefulKillsParent) {
                beforeGracefulExit.run();
                exitParentAndOrphanChildren();
            }
        }

        @Override
        public Process destroyForcibly() {
            if (destroyForciblyCalls.incrementAndGet() >= forceKillsAfter) {
                exitParentAndOrphanChildren();
            }
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public ProcessHandle toHandle() {
            return handle != null ? handle : super.toHandle();
        }

        void exitParentAndOrphanChildren() {
            alive.set(false);
            if (handle instanceof FakeHandle fake) {
                fake.exitNow();
                fake.clearDescendants();
            }
        }
    }

    /**
     * Real subprocess with a portable graceful-destroy seam. Java's direct
     * {@code Process.destroy()} does not promise that shutdown hooks run on
     * every OS; this wrapper makes destroy request a normal exit over stdin,
     * while every other lifecycle operation delegates to the real process.
     */
    private static final class NormalExitProcess extends Process {
        private final Process delegate;

        NormalExitProcess(Process delegate) {
            this.delegate = delegate;
        }

        @Override
        public OutputStream getOutputStream() {
            return delegate.getOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return delegate.getInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return delegate.getErrorStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            return delegate.waitFor();
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit)
                throws InterruptedException {
            return delegate.waitFor(timeout, unit);
        }

        @Override
        public int exitValue() {
            return delegate.exitValue();
        }

        @Override
        public void destroy() {
            try {
                delegate.getOutputStream().write(1);
                delegate.getOutputStream().flush();
            } catch (IOException e) {
                throw new IllegalStateException(
                    "could not request normal probe exit", e);
            }
        }

        @Override
        public Process destroyForcibly() {
            delegate.destroyForcibly();
            return this;
        }

        @Override
        public boolean isAlive() {
            return delegate.isAlive();
        }

        @Override
        public ProcessHandle toHandle() {
            return delegate.toHandle();
        }
    }

    private static final class FakeHandle implements ProcessHandle {
        private final long pid;
        private final AtomicBoolean alive;
        private final AtomicReference<List<ProcessHandle>> descendants;
        private final boolean exitOnForce;
        private final AtomicBoolean descendantEnumerationFails = new AtomicBoolean();
        private final CompletableFuture<ProcessHandle> exited = new CompletableFuture<>();
        private final CountDownLatch forceStarted = new CountDownLatch(1);
        private final AtomicInteger destroyForciblyCalls = new AtomicInteger();

        FakeHandle(long pid, boolean initiallyAlive,
                   List<ProcessHandle> descendants) {
            this(pid, initiallyAlive, descendants, true);
        }

        FakeHandle(long pid, boolean initiallyAlive,
                   List<ProcessHandle> descendants, boolean exitOnForce) {
            this.pid = pid;
            this.alive = new AtomicBoolean(initiallyAlive);
            this.descendants = new AtomicReference<>(descendants);
            this.exitOnForce = exitOnForce;
            if (!initiallyAlive) { exited.complete(this); }
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public Optional<ProcessHandle> parent() {
            return Optional.empty();
        }

        @Override
        public Stream<ProcessHandle> children() {
            return Stream.empty();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            if (descendantEnumerationFails.get()) {
                throw new IllegalStateException("injected unstable descendants snapshot");
            }
            return descendants.get().stream();
        }

        @Override
        public Info info() {
            return ProcessHandle.current().info();
        }

        @Override
        public CompletableFuture<ProcessHandle> onExit() {
            return exited;
        }

        @Override
        public boolean supportsNormalTermination() {
            return true;
        }

        @Override
        public boolean destroy() {
            exitNow();
            return true;
        }

        @Override
        public boolean destroyForcibly() {
            destroyForciblyCalls.incrementAndGet();
            forceStarted.countDown();
            if (exitOnForce) { exitNow(); }
            return true;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public int compareTo(ProcessHandle other) {
            return Long.compare(pid, other.pid());
        }

        void exitNow() {
            alive.set(false);
            exited.complete(this);
        }

        void clearDescendants() {
            descendants.set(List.of());
        }

        void replaceDescendants(List<ProcessHandle> replacement) {
            descendants.set(replacement);
        }

        void failDescendantEnumeration() {
            descendantEnumerationFails.set(true);
        }

        void allowDescendantEnumeration() {
            descendantEnumerationFails.set(false);
        }
    }
}
