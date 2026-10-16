package com.brewshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class BrewShotWatchCommitTest {
    @TempDir Path directory;
    private static final String JOB = "a".repeat(32), SOURCE = "b".repeat(64);

    @Test void restartAfterPublicationReusesOriginalImageEvenWhenNewRecordingDiffers() throws Exception {
        Path first = Files.writeString(directory.resolve("first"), "first complete capture");
        var beforeCrash = new BrewShotWatchCommit(directory, JOB, SOURCE);
        Path committed = beforeCrash.record(first, ".gif");
        Path output = directory.resolve("job.gif");
        BrewShotWatchCommit.publish(committed, output);
        Files.delete(first); // process exited before input archive
        var afterRestart = new BrewShotWatchCommit(directory, JOB, SOURCE);
        assertEquals(committed, afterRestart.recover());
        Path different = Files.writeString(directory.resolve("new"), "different animation frame");
        assertEquals(committed, afterRestart.record(different, ".gif"));
        BrewShotWatchCommit.publish(committed, output);
        assertEquals("first complete capture", Files.readString(output));
    }

    @Test void concurrentUnlockedAttemptsChooseOneCaptureBeforePublishing() throws Exception {
        Path one = Files.writeString(directory.resolve("one"), "one");
        Path two = Files.writeString(directory.resolve("two"), "two");
        var start = new CountDownLatch(1);
        Path output = directory.resolve("race.gif");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                var commit = new BrewShotWatchCommit(directory, JOB, SOURCE);
                start.await();
                Path chosen = commit.record(one, ".gif");
                BrewShotWatchCommit.publish(chosen, output);
                return chosen;
            });
            var second = pool.submit(() -> {
                var commit = new BrewShotWatchCommit(directory, JOB, SOURCE);
                start.await();
                Path chosen = commit.record(two, ".gif");
                BrewShotWatchCommit.publish(chosen, output);
                return chosen;
            });
            start.countDown();
            assertEquals(first.get(), second.get());
        }
        assertTrue(Files.readString(output).equals("one") || Files.readString(output).equals("two"));
        assertEquals(Files.readString(new BrewShotWatchCommit(directory, JOB, SOURCE).recover()),
            Files.readString(output));
    }

    @Test void foreignOutputAndDifferentSourceAreNotMistakenForTheSameCommit() throws Exception {
        Path complete = Files.writeString(directory.resolve("complete"), "image");
        var commit = new BrewShotWatchCommit(directory, JOB, SOURCE);
        Path chosen = commit.record(complete, ".png");
        Path foreign = Files.writeString(directory.resolve("foreign.png"), "foreign bytes");
        assertThrows(BrewShotWatchCommit.Collision.class,
            () -> BrewShotWatchCommit.publish(chosen, foreign));
        assertEquals("foreign bytes", Files.readString(foreign));
        assertNull(new BrewShotWatchCommit(directory, JOB, "c".repeat(64)).recover());
    }

    @Test void aLateFailureCannotOverruleSuccessAndALateSuccessCannotOverruleFailure() throws Exception {
        Path image = Files.writeString(directory.resolve("image"), "image bytes");
        var success = new BrewShotWatchCommit(directory, JOB, SOURCE);
        Path selected = success.record(image, ".gif");
        assertEquals(selected, success.fail("job-deadline"));
        var failure = new BrewShotWatchCommit(directory, "d".repeat(32), SOURCE);
        assertEquals("job-deadline", assertThrows(BrewShotWatchCommit.Failed.class,
            () -> failure.fail("job-deadline")).getMessage());
        assertEquals("job-deadline", assertThrows(BrewShotWatchCommit.Failed.class,
            () -> failure.record(image, ".gif")).getMessage());
        assertThrows(BrewShotWatchCommit.Failed.class, failure::recover);
    }
}
