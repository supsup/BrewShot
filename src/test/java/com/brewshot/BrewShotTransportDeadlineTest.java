package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pure transport discriminators for plan 0cd89c1b. No test in this class
 * discovers or launches Chrome.
 */
class BrewShotTransportDeadlineTest {

    @Test
    void publicTimeoutAndHeapContractsRejectInvalidValuesAndKeepDocumentedZeroNoOps(
            @TempDir Path temp) throws Exception {
        FakeWebSocket socket = new FakeWebSocket();
        BrewShot shot = new BrewShot(
            new FakeProcess(true), profile(temp, "validation"), socket,
            new LinkedBlockingQueue<>(), 40);
        try {
            assertThrows(IllegalArgumentException.class, () -> shot.navTimeout(0));
            assertThrows(IllegalArgumentException.class, () -> shot.navTimeout(-1));
            assertThrows(IllegalArgumentException.class, () -> shot.commandTimeout(0));
            assertThrows(IllegalArgumentException.class, () -> shot.commandTimeout(-1));
            assertThrows(IllegalArgumentException.class, () -> shot.recordingHeapBudget(0));
            assertThrows(IllegalArgumentException.class, () -> shot.recordingHeapBudget(-1));
            assertThrows(IllegalArgumentException.class, () -> shot.waitFor("true", 0));
            assertThrows(IllegalArgumentException.class,
                () -> shot.waitForNetworkIdle(-1, 1));
            assertThrows(IllegalArgumentException.class,
                () -> shot.waitForNetworkIdle(1, -1));
            assertThrows(IllegalArgumentException.class, () -> shot.settle(-1));

            assertSame(shot, shot.navTimeout(1));
            assertSame(shot, shot.commandTimeout(1));
            assertSame(shot, shot.recordingHeapBudget(1));
            shot.waitForNetworkIdle(0, 0);
            shot.settle(0);
            assertEquals(0, socket.sendCalls.get(),
                "documented zero-duration no-ops must not touch CDP");
        } finally {
            shot.close();
        }
    }

    @Test
    void everyLibraryRecorderRejectsBadCountsAndDelaysBeforeProtocolTraffic(
            @TempDir Path temp) throws Exception {
        FakeWebSocket socket = new FakeWebSocket();
        BrewShot shot = new BrewShot(
            new FakeProcess(true), profile(temp, "recorder-validation"), socket,
            new LinkedBlockingQueue<>(), 40);
        Path out = temp.resolve("never-written.gif");
        try {
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGif(0, 0, 1, 1, 0, 1, 1, out));
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGif(0, 0, 1, 1, 1, 0, 1, out));
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGif(0, 0, 1, 1, 1, 1, 0, out));
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGifElement("#x", 0, 1, 1, 1, out));
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGifElement("#x", 1, 0, 1, 1, out));
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGifScroll(0, 0, 1, 1, out));
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGifScroll(1, -1, 1, 1, out));
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGifScroll(1, 0, 0, 1, out));
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGifFullPage(0, 1, 1, out));
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGifFullPage(1, 0, 1, out));
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGifRegion(0, 1, 0, 1, 1, out));
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGifRegion(0, 1, 1, 0, 1, out));
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGifStream(0, 1, out));
            assertThrows(IllegalArgumentException.class,
                () -> shot.recordGifStream(1, 0, out));
            assertEquals(0, socket.sendCalls.get(),
                "invalid recorder arguments must fail before page eval/capture");
        } finally {
            shot.close();
        }
    }

    @Test
    void neverCompletingSendUsesCommandBudgetAndCancelsTransport(@TempDir Path temp)
            throws Exception {
        LinkedBlockingQueue<BrewShot.InboxMessage> inbox = new LinkedBlockingQueue<>();
        FakeWebSocket socket = new FakeWebSocket();
        CompletableFuture<WebSocket> stuckSend = new CompletableFuture<>();
        socket.onSend = ignored -> stuckSend;
        FakeProcess process = new FakeProcess(true);
        BrewShot shot = new BrewShot(process, profile(temp, "send"), socket, inbox, 40);
        shot.commandTimeout(40);

        long started = System.nanoTime();
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> shot.eval("1"));
            long elapsedMs = elapsedMillis(started);

            assertTrue(failure.getMessage().contains("CDP timeout sending Runtime.evaluate"),
                failure.getMessage());
            assertTrue(elapsedMs >= 20 && elapsedMs < 750,
                "40ms send budget completed in " + elapsedMs + "ms");
            assertTrue(stuckSend.isCancelled(), "timed-out send future must be cancelled");
            assertEquals(1, socket.abortCalls.get(),
                "a late send cannot be allowed onto a later command");
        } finally {
            shot.close();
        }
    }

    @Test
    void sendTimeIsSpentFromTheSameDeadlineAsTheResponse(@TempDir Path temp)
            throws Exception {
        LinkedBlockingQueue<BrewShot.InboxMessage> inbox = new LinkedBlockingQueue<>();
        FakeWebSocket socket = new FakeWebSocket();
        // Send consumes 180ms of a 250ms command budget. No response follows.
        // A reset-after-send implementation takes about 430ms; one deadline
        // takes about 250ms.
        socket.onSend = ignored -> new DelayedSuccessfulFuture(socket, 180);
        BrewShot shot = new BrewShot(
            new FakeProcess(true), profile(temp, "shared"), socket, inbox, 40);
        shot.commandTimeout(250);

        long started = System.nanoTime();
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> shot.eval("1"));
            long elapsedMs = elapsedMillis(started);

            assertTrue(failure.getMessage().contains("CDP timeout"), failure.getMessage());
            assertTrue(elapsedMs >= 200 && elapsedMs < 360,
                "send + response should share 250ms, completed in " + elapsedMs + "ms");
        } finally {
            shot.close();
        }
    }

    @Test
    void successfulCommandSurvivesASaturatedTimeout(@TempDir Path temp) throws Exception {
        LinkedBlockingQueue<BrewShot.InboxMessage> inbox = new LinkedBlockingQueue<>();
        FakeWebSocket socket = new FakeWebSocket();
        socket.onSend = text -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> sent = (Map<String, Object>) MiniJson.parse(text);
            int id = ((Number) sent.get("id")).intValue();
            inbox.add(BrewShot.InboxMessage.untracked("{\"id\":" + id
                + ",\"result\":{\"result\":{\"value\":\"still-ok\"}}}"));
            return CompletableFuture.completedFuture(socket);
        };
        BrewShot shot = new BrewShot(
            new FakeProcess(true), profile(temp, "success"), socket, inbox, 40);
        shot.commandTimeout(Long.MAX_VALUE);

        try {
            assertEquals("still-ok", shot.eval("'still-ok'"));
            assertEquals(1, socket.sendCalls.get());
        } finally {
            shot.close();
        }
    }

    @Test
    void waitForUsesSaturatedMonotonicDeadlineAtLongMax(
            @TempDir Path temp) throws Exception {
        LinkedBlockingQueue<BrewShot.InboxMessage> inbox = new LinkedBlockingQueue<>();
        FakeWebSocket socket = new FakeWebSocket();
        AtomicInteger polls = new AtomicInteger();
        socket.onSend = text -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> sent = (Map<String, Object>) MiniJson.parse(text);
            int id = ((Number) sent.get("id")).intValue();
            boolean ready = polls.incrementAndGet() >= 2;
            inbox.add(BrewShot.InboxMessage.untracked("{\"id\":" + id
                + ",\"result\":{\"result\":{\"value\":" + ready + "}}}"));
            return CompletableFuture.completedFuture(socket);
        };
        BrewShot shot = new BrewShot(
            new FakeProcess(true), profile(temp, "wait-for-max"), socket, inbox, 40);

        long started = System.nanoTime();
        try {
            shot.waitFor("ready", Long.MAX_VALUE);
            assertEquals(2, polls.get(),
                "Long.MAX_VALUE must not wrap into a timeout after the first false poll");
            assertTrue(elapsedMillis(started) < 1_000,
                "the successful discriminator must not spend the enormous budget");
        } finally {
            shot.close();
        }
    }

    @Test
    void waitForBackpressuredSendSpendsOnlyOuterBudget(@TempDir Path temp) throws Exception {
        FakeWebSocket socket = new FakeWebSocket();
        CompletableFuture<WebSocket> stuck = new CompletableFuture<>();
        socket.onSend = ignored -> stuck;
        BrewShot shot = new BrewShot(new FakeProcess(true), profile(temp, "wait-send"),
            socket, new LinkedBlockingQueue<>(), 40);
        shot.commandTimeout(1_000);
        long started = System.nanoTime();
        try {
            assertOuterTimeout(assertThrows(IllegalStateException.class,
                () -> shot.waitFor("ready", 50)), 50);
            assertElapsed(started, 25, 700);
            assertTrue(stuck.isCancelled());
            assertEquals(1, socket.abortCalls.get());
        } finally { shot.close(); }
    }

    @Test
    void waitForSendAndAbsentResponseShareOuterBudget(@TempDir Path temp) throws Exception {
        FakeWebSocket socket = new FakeWebSocket();
        socket.onSend = ignored -> new DelayedSuccessfulFuture(socket, 140);
        BrewShot shot = new BrewShot(new FakeProcess(true), profile(temp, "wait-response"),
            socket, new LinkedBlockingQueue<>(), 40);
        shot.commandTimeout(1_000);
        long started = System.nanoTime();
        try {
            assertOuterTimeout(assertThrows(IllegalStateException.class,
                () -> shot.waitFor("ready", 200)), 200);
            assertElapsed(started, 170, 310);
            assertEquals(1, socket.sendCalls.get());
        } finally { shot.close(); }
    }

    @Test
    void waitForRejectsTrueQueuedOnTimeButReturnedAfterExpiry(@TempDir Path temp) throws Exception {
        LinkedBlockingQueue<BrewShot.InboxMessage> inbox = new LinkedBlockingQueue<>() {
            @Override public BrewShot.InboxMessage poll(long timeout, TimeUnit unit)
                    throws InterruptedException {
                // Deliberately return a queued response after the caller's deadline:
                // models descheduling after poll acquired it, without helper threads.
                TimeUnit.MILLISECONDS.sleep(100);
                return super.poll();
            }
        };
        FakeWebSocket socket = new FakeWebSocket();
        socket.onSend = text -> {
            respond(inbox, text, true);
            return CompletableFuture.completedFuture(socket);
        };
        BrewShot shot = new BrewShot(new FakeProcess(true), profile(temp, "late-true"), socket, inbox, 40);
        long started = System.nanoTime();
        try {
            assertOuterTimeout(assertThrows(IllegalStateException.class,
                () -> shot.waitFor("ready", 40)), 40);
            assertElapsed(started, 80, 650);
            assertEquals(1, socket.sendCalls.get());
        } finally { shot.close(); }
    }

    @Test
    void waitForAcceptsOnTimeTrueAndFalseThenTrue(@TempDir Path temp) throws Exception {
        for (int falsePolls : new int[] {0, 1}) {
            LinkedBlockingQueue<BrewShot.InboxMessage> inbox = new LinkedBlockingQueue<>();
            FakeWebSocket socket = new FakeWebSocket();
            AtomicInteger polls = new AtomicInteger();
            socket.onSend = text -> {
                respond(inbox, text, polls.incrementAndGet() > falsePolls);
                return CompletableFuture.completedFuture(socket);
            };
            BrewShot shot = new BrewShot(new FakeProcess(true), profile(temp, "on-time-" + falsePolls),
                socket, inbox, 40);
            try {
                shot.waitFor("ready", 1_000);
                assertEquals(falsePolls + 1, polls.get());
            } finally { shot.close(); }
        }
    }

    @Test
    void nearExpiryFalseDoesNotStartFreshPauseOrEvaluation(@TempDir Path temp) throws Exception {
        AtomicLong clock = new AtomicLong();
        AtomicLong requestedPause = new AtomicLong(-1);
        AtomicInteger pauses = new AtomicInteger();
        LinkedBlockingQueue<BrewShot.InboxMessage> inbox = new LinkedBlockingQueue<>();
        FakeWebSocket socket = new FakeWebSocket();
        socket.onSend = text -> {
            // The first false response spends exactly 100ms of a 110ms budget.
            // Any erroneous second send expires it, keeping the bad path finite
            // even if a faulty pause requests zero or the retry check is removed.
            clock.set(TimeUnit.MILLISECONDS.toNanos(socket.sendCalls.get() == 1 ? 100 : 110));
            respond(inbox, text, false);
            return CompletableFuture.completedFuture(socket);
        };
        BrewShot shot = new BrewShot(new FakeProcess(true), profile(temp, "near-expiry"), socket, inbox, 40)
            .commandTimeout(1_000);
        try {
            Class<?> deadlineType = Class.forName("com.brewshot.BrewShot$Deadline");
            var constructor = deadlineType.getDeclaredConstructor(long.class, java.util.function.LongSupplier.class);
            constructor.setAccessible(true);
            Object deadline = constructor.newInstance(TimeUnit.MILLISECONDS.toNanos(110),
                (java.util.function.LongSupplier) clock::get);
            BrewShot.RetryPause pause = nanos -> {
                requestedPause.set(nanos);
                pauses.incrementAndGet();
                clock.addAndGet(nanos);
            };
            var wait = BrewShot.class.getDeclaredMethod("waitFor", String.class, long.class,
                deadlineType, BrewShot.RetryPause.class);
            wait.setAccessible(true);
            var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> wait.invoke(shot, "ready", 110L, deadline, pause));
            assertTrue(failure.getCause() instanceof IllegalStateException,
                "wait must fail with its timeout, not a reflection/setup error: " + failure.getCause());
            assertOuterTimeout((IllegalStateException) failure.getCause(), 110);
            assertEquals(TimeUnit.MILLISECONDS.toNanos(10), requestedPause.get(),
                "actual retry pause must request exactly the remaining 10ms, never a fresh 60ms");
            assertEquals(1, pauses.get(), "one false response permits exactly one bounded pause");
            assertEquals(TimeUnit.MILLISECONDS.toNanos(110), clock.get());
            assertEquals(1, socket.sendCalls.get(), "expiry must be checked before the next send");
        } finally { shot.close(); }
    }

    @Test
    void interleavedEventsDoNotRenewWaitForBudget(@TempDir Path temp) throws Exception {
        AtomicInteger events = new AtomicInteger();
        LinkedBlockingQueue<BrewShot.InboxMessage> inbox = new LinkedBlockingQueue<>() {
            @Override public BrewShot.InboxMessage poll(long timeout, TimeUnit unit)
                    throws InterruptedException {
                long remaining = unit.toNanos(timeout);
                long step = TimeUnit.MILLISECONDS.toNanos(10);
                TimeUnit.NANOSECONDS.sleep(Math.min(step, remaining));
                if (remaining < step) return null;
                // Keep the deadline-reset discriminator finite even if a mutant
                // renews every poll: 26 events cannot fit this 100 ms budget.
                assertTrue(events.incrementAndGet() <= 25,
                    "interleaved event wait renewed beyond its outer budget");
                return BrewShot.InboxMessage.untracked("{\"method\":\"Network.loadingFinished\"}");
            }
        };
        FakeWebSocket socket = new FakeWebSocket();
        BrewShot shot = new BrewShot(new FakeProcess(true), profile(temp, "interleaved"), socket, inbox, 40);
        shot.commandTimeout(1_000);
        long started = System.nanoTime();
        try {
            assertOuterTimeout(assertThrows(IllegalStateException.class,
                () -> shot.waitFor("ready", 100)), 100);
            assertElapsed(started, 75, 700);
            assertTrue(events.get() > 0, "control must route events before expiry");
            assertEquals(1, socket.sendCalls.get());
        } finally { shot.close(); }
    }

    @Test
    void waitForPreservesShorterConfiguredCommandTimeout(@TempDir Path temp) throws Exception {
        BrewShot shot = new BrewShot(new FakeProcess(true), profile(temp, "short-command"),
            new FakeWebSocket(), new LinkedBlockingQueue<>(), 40);
        shot.commandTimeout(40);
        long started = System.nanoTime();
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> shot.waitFor("ready", 1_000));
            assertTrue(failure.getMessage().startsWith("CDP timeout"), failure.getMessage());
            assertElapsed(started, 20, 700);
        } finally { shot.close(); }
    }

    @Test
    void expiredInitialWaitForBudgetSendsNothing(@TempDir Path temp) throws Exception {
        FakeWebSocket socket = new FakeWebSocket();
        BrewShot shot = new BrewShot(new FakeProcess(true), profile(temp, "expired"),
            socket, new LinkedBlockingQueue<>(), 40);
        Class<?> type = Class.forName("com.brewshot.BrewShot$Deadline");
        var after = type.getDeclaredMethod("afterMillis", long.class);
        after.setAccessible(true);
        Object expired = after.invoke(null, 0L);
        var wait = BrewShot.class.getDeclaredMethod("waitFor", String.class, long.class, type);
        wait.setAccessible(true);
        var nextId = BrewShot.class.getDeclaredField("nextId");
        nextId.setAccessible(true);
        int before = nextId.getInt(shot);
        try {
            var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> wait.invoke(shot, "ready", 1L, expired));
            assertOuterTimeout((IllegalStateException) failure.getCause(), 1);
            assertEquals(before, nextId.getInt(shot),
                "outer expiry must stop before command's redundant no-send guard");
            assertEquals(0, socket.sendCalls.get());
            assertThrows(IllegalArgumentException.class, () -> shot.waitFor("ready", 0));
        } finally { shot.close(); }
    }

    @Test
    void waitForPreservesClosedTransportAndInterruptCause(@TempDir Path temp) throws Exception {
        LinkedBlockingQueue<BrewShot.InboxMessage> inbox = new LinkedBlockingQueue<>();
        inbox.add(BrewShot.InboxMessage.closed());
        FakeWebSocket socket = new FakeWebSocket();
        BrewShot shot = new BrewShot(new FakeProcess(true), profile(temp, "causal"), socket, inbox, 40);
        try {
            IllegalStateException closed = assertThrows(IllegalStateException.class,
                () -> shot.waitFor("ready", 500));
            assertTrue(closed.getMessage().contains("DevTools socket closed"), closed.getMessage());
            int sends = socket.sendCalls.get();
            assertThrows(IllegalStateException.class, () -> shot.waitFor("ready", 500));
            assertEquals(sends, socket.sendCalls.get(), "latched close must fail before another send");
            Thread.currentThread().interrupt();
            try {
                IllegalStateException interrupted = assertThrows(IllegalStateException.class,
                    () -> shot.waitFor("ready", 500));
                assertTrue(interrupted.getMessage().contains("interrupted"));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        } finally { shot.close(); }
    }

    @Test
    void waitForInterruptedSendCancelsAndKeepsInterrupt(@TempDir Path temp) throws Exception {
        FakeWebSocket socket = new FakeWebSocket();
        CompletableFuture<WebSocket> stuck = new CompletableFuture<>();
        socket.onSend = ignored -> { Thread.currentThread().interrupt(); return stuck; };
        BrewShot shot = new BrewShot(new FakeProcess(true), profile(temp, "interrupt-send"),
            socket, new LinkedBlockingQueue<>(), 40);
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> shot.waitFor("ready", 500));
            assertTrue(failure.getMessage().contains("interrupted sending"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(stuck.isCancelled());
            assertEquals(1, socket.abortCalls.get());
        } finally { Thread.interrupted(); shot.close(); }
    }

    @Test
    void waitForPreservesPageErrorEvenWhenDeliveredAfterOuterExpiry(@TempDir Path temp) throws Exception {
        LinkedBlockingQueue<BrewShot.InboxMessage> inbox = new LinkedBlockingQueue<>() {
            @Override public BrewShot.InboxMessage poll(long timeout, TimeUnit unit)
                    throws InterruptedException {
                TimeUnit.MILLISECONDS.sleep(80);
                return super.poll();
            }
        };
        FakeWebSocket socket = new FakeWebSocket();
        socket.onSend = text -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> sent = (Map<String, Object>) MiniJson.parse(text);
            int id = ((Number) sent.get("id")).intValue();
            inbox.add(BrewShot.InboxMessage.untracked("{\"id\":" + id
                + ",\"result\":{\"exceptionDetails\":{\"text\":\"predicate-error\"}}}"));
            return CompletableFuture.completedFuture(socket);
        };
        BrewShot shot = new BrewShot(new FakeProcess(true), profile(temp, "page-error"), socket, inbox, 40);
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> shot.waitFor("ready", 40));
            assertTrue(failure.getMessage().startsWith("page JS threw:"), failure.getMessage());
        } finally { shot.close(); }
    }

    @Test
    void waitForInterruptedResponseKeepsInterrupt(@TempDir Path temp) throws Exception {
        LinkedBlockingQueue<BrewShot.InboxMessage> inbox = new LinkedBlockingQueue<>() {
            @Override public BrewShot.InboxMessage poll(long timeout, TimeUnit unit)
                    throws InterruptedException {
                throw new InterruptedException("scripted response interruption");
            }
        };
        BrewShot shot = new BrewShot(new FakeProcess(true), profile(temp, "interrupt-response"),
            new FakeWebSocket(), inbox, 40);
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> shot.waitFor("ready", 500));
            assertTrue(failure.getMessage().contains("interrupted"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); shot.close(); }
    }

    @Test
    void waitEventUsesMonotonicSaturatedBudgetAndPreservesPendingEvent(@TempDir Path temp) throws Exception {
        AtomicInteger timedPolls = new AtomicInteger();
        LinkedBlockingQueue<BrewShot.InboxMessage> inbox = new LinkedBlockingQueue<>() {
            @Override public BrewShot.InboxMessage poll(long timeout, TimeUnit unit)
                    throws InterruptedException {
                assertTrue(unit.toNanos(timeout) > TimeUnit.DAYS.toNanos(1),
                    "Long.MAX_VALUE navigation budget must saturate rather than overflow wall clock");
                timedPolls.incrementAndGet();
                return super.poll(timeout, unit);
            }
        };
        inbox.add(BrewShot.InboxMessage.untracked("{\"method\":\"Page.loadEventFired\"}"));
        BrewShot shot = new BrewShot(new FakeProcess(true), profile(temp, "event-max"),
            new FakeWebSocket(), inbox, 40);
        var wait = BrewShot.class.getDeclaredMethod("waitEvent", String.class, long.class);
        wait.setAccessible(true);
        var route = BrewShot.class.getDeclaredMethod("routeEvent", Map.class);
        route.setAccessible(true);
        try {
            wait.invoke(shot, "Page.loadEventFired", Long.MAX_VALUE);
            assertEquals(1, timedPolls.get());
            route.invoke(shot, Map.of("method", "Page.loadEventFired"));
            wait.invoke(shot, "Page.loadEventFired", 0L);
            assertEquals(1, timedPolls.get(), "pending event stays consumable without a new poll");
        } finally { shot.close(); }
    }

    private static void respond(LinkedBlockingQueue<BrewShot.InboxMessage> inbox, String text, boolean ready) {
        @SuppressWarnings("unchecked")
        Map<String, Object> sent = (Map<String, Object>) MiniJson.parse(text);
        int id = ((Number) sent.get("id")).intValue();
        inbox.add(BrewShot.InboxMessage.untracked("{\"id\":" + id
            + ",\"result\":{\"result\":{\"value\":" + ready + "}}}"));
    }

    private static void assertOuterTimeout(IllegalStateException failure, long budgetMs) {
        assertTrue(failure.getMessage().startsWith("waitFor timed out after " + budgetMs + "ms: ready"),
            failure.getMessage());
    }

    private static void assertElapsed(long started, long minimum, long maximum) {
        long elapsed = elapsedMillis(started);
        assertTrue(elapsed >= minimum && elapsed < maximum,
            "bounded wait completed in " + elapsed + "ms; expected [" + minimum + ", " + maximum + ")");
    }

    @Test
    void networkIdleUsesSaturatedMonotonicDeadlineAtLongMax(
            @TempDir Path temp) throws Exception {
        LinkedBlockingQueue<BrewShot.InboxMessage> inbox = new LinkedBlockingQueue<>();
        inbox.add(BrewShot.InboxMessage.untracked(
            "{\"method\":\"Network.requestWillBeSent\","
                + "\"params\":{\"requestId\":\"request-1\"}}"));
        inbox.add(BrewShot.InboxMessage.untracked(
            "{\"method\":\"Network.loadingFinished\","
                + "\"params\":{\"requestId\":\"request-1\"}}"));
        BrewShot shot = new BrewShot(
            new FakeProcess(true), profile(temp, "network-idle-max"),
            new FakeWebSocket(), inbox, 40);

        long started = System.nanoTime();
        try {
            shot.waitForNetworkIdle(30, Long.MAX_VALUE);
            long elapsedMs = elapsedMillis(started);
            assertTrue(elapsedMs >= 20 && elapsedMs < 1_000,
                "network quiet window should complete promptly, not wrap or wait forever: "
                    + elapsedMs + "ms");
        } finally {
            shot.close();
        }
    }

    @Test
    void neverCompletingConnectTimesOutAndCleansLaunchResources(@TempDir Path temp)
            throws Exception {
        CompletableFuture<WebSocket> stuckConnect = new CompletableFuture<>();
        FakeProcess process = new FakeProcess(false);
        Path profile = profile(temp, "connect");

        long started = System.nanoTime();
        IOException failure = assertThrows(IOException.class, () ->
            BrewShot.finishLaunch(process, profile, "ws://127.0.0.1:1/devtools/browser/fake",
                (uri, listener, timeout) -> stuckConnect, 40));
        long elapsedMs = elapsedMillis(started);

        assertTrue(failure.getMessage().contains("connect timed out"), failure.getMessage());
        assertTrue(elapsedMs >= 20 && elapsedMs < 750,
            "40ms connect budget completed in " + elapsedMs + "ms");
        assertTrue(stuckConnect.isCancelled(), "timed-out connect future must be cancelled");
        assertFalse(process.isAlive(), "failed launch must forcibly reap its process");
        assertTrue(process.destroyForciblyCalls.get() >= 1);
        assertFalse(Files.exists(profile), "failed launch must delete its temp profile");
    }

    @Test
    void neverCompletingCloseIsBoundedCleansUpAndIsIdempotent(@TempDir Path temp)
            throws Exception {
        FakeWebSocket socket = new FakeWebSocket();
        CompletableFuture<WebSocket> stuckClose = new CompletableFuture<>();
        socket.closeFuture = stuckClose;
        FakeProcess process = new FakeProcess(false);
        Path profile = profile(temp, "close");
        BrewShot shot = new BrewShot(
            process, profile, socket, new LinkedBlockingQueue<>(), 40);

        long started = System.nanoTime();
        shot.close();
        long elapsedMs = elapsedMillis(started);

        assertTrue(elapsedMs >= 20 && elapsedMs < 750,
            "40ms close grace completed in " + elapsedMs + "ms");
        assertTrue(stuckClose.isCancelled(), "timed-out close future must be cancelled");
        assertEquals(1, socket.abortCalls.get());
        assertFalse(process.isAlive(), "close must reap the browser process");
        assertEquals(1, process.destroyCalls.get());
        assertTrue(process.destroyForciblyCalls.get() >= 1,
            "an uncooperative process must be forcibly reaped");
        assertFalse(Files.exists(profile), "close must delete its temp profile");

        shot.close();
        assertEquals(1, socket.closeCalls.get(), "close must send at most one close frame");
        assertEquals(1, process.destroyCalls.get(), "close must tear down the process once");
    }

    @Test
    void closeKeepsResourcesRegisteredUntilCleanupCompletes(@TempDir Path temp)
            throws Exception {
        FakeWebSocket socket = new FakeWebSocket();
        FakeProcess process = new FakeProcess(true);
        Path profile = profile(temp, "owned-close");
        AtomicBoolean ownedWhileCloseFrameWasSent = new AtomicBoolean();
        socket.onClose = () -> ownedWhileCloseFrameWasSent.set(
            BrewShot.ownsResources(process, profile));
        BrewShot shot = new BrewShot(
            process, profile, socket, new LinkedBlockingQueue<>(), 40);

        shot.close();

        assertTrue(ownedWhileCloseFrameWasSent.get(),
            "shutdown cleanup must retain ownership until process/profile cleanup completes");
    }

    @Test
    void interruptedCloseRestoresTheInterruptAfterCleanup(@TempDir Path temp)
            throws Exception {
        FakeWebSocket socket = new FakeWebSocket();
        socket.closeFuture = new CompletableFuture<>();
        FakeProcess process = new FakeProcess(true);
        Path profile = profile(temp, "interrupt");
        BrewShot shot = new BrewShot(
            process, profile, socket, new LinkedBlockingQueue<>(), 1_000);
        AtomicBoolean interruptRestored = new AtomicBoolean();
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();

        Thread closer = new Thread(() -> {
            try {
                Thread.currentThread().interrupt();
                shot.close();
                interruptRestored.set(Thread.currentThread().isInterrupted());
            } catch (Throwable t) {
                threadFailure.set(t);
            }
        }, "brewshot-interrupted-close-test");
        closer.start();
        closer.join(2_000);

        assertFalse(closer.isAlive(), "interrupted close must remain bounded");
        assertNull(threadFailure.get());
        assertTrue(interruptRestored.get(), "close must restore the caller's interrupt flag");
        assertFalse(process.isAlive());
        assertFalse(Files.exists(profile));
    }

    private static Path profile(Path temp, String name) throws IOException {
        Path profile = Files.createDirectory(temp.resolve(name));
        Files.writeString(profile.resolve("marker"), "owned by fake Chrome");
        return profile;
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static final class DelayedSuccessfulFuture
            extends CompletableFuture<WebSocket> {
        private final WebSocket value;
        private final long delayMs;

        DelayedSuccessfulFuture(WebSocket value, long delayMs) {
            this.value = value;
            this.delayMs = delayMs;
        }

        @Override
        public WebSocket get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            long allowedNanos = unit.toNanos(timeout);
            long delayNanos = TimeUnit.MILLISECONDS.toNanos(delayMs);
            TimeUnit.NANOSECONDS.sleep(Math.min(allowedNanos, delayNanos));
            if (allowedNanos < delayNanos) { throw new TimeoutException(); }
            complete(value);
            return value;
        }
    }

    private static final class FakeWebSocket implements WebSocket {
        private Function<String, CompletableFuture<WebSocket>> onSend =
            ignored -> CompletableFuture.completedFuture(this);
        private CompletableFuture<WebSocket> closeFuture =
            CompletableFuture.completedFuture(this);
        private Runnable onClose = () -> { };
        private final AtomicInteger sendCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicInteger abortCalls = new AtomicInteger();

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            sendCalls.incrementAndGet();
            return onSend.apply(data.toString());
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
            onClose.run();
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

    private static final class FakeProcess extends Process {
        private final boolean exitsOnDestroy;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final ProcessHandle handle = new FakeHandle(alive);
        private final AtomicInteger destroyCalls = new AtomicInteger();
        private final AtomicInteger destroyForciblyCalls = new AtomicInteger();

        FakeProcess(boolean exitsOnDestroy) {
            this.exitsOnDestroy = exitsOnDestroy;
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
            destroyCalls.incrementAndGet();
            if (exitsOnDestroy) { alive.set(false); }
        }

        @Override
        public Process destroyForcibly() {
            destroyForciblyCalls.incrementAndGet();
            alive.set(false);
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public ProcessHandle toHandle() {
            return handle;
        }
    }

    /**
     * Transport tests are not process-wait tests: an immediately completed
     * onExit future makes the cleanup code proceed to its force fallback
     * without spending the production 3s graceful allowance.
     */
    private static final class FakeHandle implements ProcessHandle {
        private final AtomicBoolean alive;

        FakeHandle(AtomicBoolean alive) {
            this.alive = alive;
        }

        @Override
        public long pid() { return 808_080; }

        @Override
        public Optional<ProcessHandle> parent() { return Optional.empty(); }

        @Override
        public Stream<ProcessHandle> children() { return Stream.empty(); }

        @Override
        public Stream<ProcessHandle> descendants() { return Stream.empty(); }

        @Override
        public Info info() { return ProcessHandle.current().info(); }

        @Override
        public CompletableFuture<ProcessHandle> onExit() {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public boolean supportsNormalTermination() { return true; }

        @Override
        public boolean destroy() {
            alive.set(false);
            return true;
        }

        @Override
        public boolean destroyForcibly() {
            alive.set(false);
            return true;
        }

        @Override
        public boolean isAlive() { return alive.get(); }

        @Override
        public int compareTo(ProcessHandle other) {
            return Long.compare(pid(), other.pid());
        }
    }
}
