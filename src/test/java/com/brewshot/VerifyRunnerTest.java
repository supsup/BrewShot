package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifyRunnerTest {

    @Test
    void verifyCliRequiresOneExplicitMode() throws Exception {
        assertEquals(2, Main.run(new String[] {
            "verify", "--manifest", "shots.json", "--check", "--update"}));
        assertEquals(2, Main.run(new String[] {"verify", "--manifest", "shots.json"}));
        assertEquals(0, Main.run(new String[] {"verify", "--help"}));
    }

    @Test
    void checkRunsEveryJobKeepsBaselinesImmutableAndWritesStableReceipts(
            @TempDir Path directory) throws Exception {
        Fixture fixture = fixture(directory);
        byte[] oneBefore = Files.readAllBytes(fixture.baselineOne());
        byte[] twoBefore = Files.readAllBytes(fixture.baselineTwo());
        FileTime frozen = FileTime.fromMillis(1_234_567_890L);
        Files.setLastModifiedTime(fixture.baselineOne(), frozen);
        Files.setLastModifiedTime(fixture.baselineTwo(), frozen);
        AtomicInteger captures = new AtomicInteger();
        VerifyRunner runner = VerifyRunner.forTest((job, input, output) -> {
            captures.incrementAndGet();
            writePng(output, job.id().equals("one") ? Color.WHITE : Color.BLACK);
            return 0;
        }, null);

        int firstExit = runner.execute(fixture.manifest(), VerifyPreflight.Mode.CHECK);

        assertEquals(4, firstExit);
        assertEquals(2, captures.get(), "a threshold failure must not cancel later jobs");
        assertArrayEquals(oneBefore, Files.readAllBytes(fixture.baselineOne()));
        assertArrayEquals(twoBefore, Files.readAllBytes(fixture.baselineTwo()));
        assertEquals(frozen, Files.getLastModifiedTime(fixture.baselineOne()));
        assertEquals(frozen, Files.getLastModifiedTime(fixture.baselineTwo()));
        assertTrue(Files.readString(fixture.receiptOne()).contains("\"status\": \"passed\""));
        assertTrue(Files.readString(fixture.receiptTwo())
            .contains("\"status\": \"threshold-exceeded\""));
        assertTrue(Files.readString(fixture.batchReceipt()).contains("\"exit\": 4"));
        String receiptOneCore = deterministicCore(fixture.receiptOne());
        String receiptTwoCore = deterministicCore(fixture.receiptTwo());
        String batchCore = deterministicCore(fixture.batchReceipt());

        int secondExit = runner.execute(fixture.manifest(), VerifyPreflight.Mode.CHECK);

        assertEquals(4, secondExit);
        assertEquals(receiptOneCore, deterministicCore(fixture.receiptOne()));
        assertEquals(receiptTwoCore, deterministicCore(fixture.receiptTwo()));
        assertEquals(batchCore, deterministicCore(fixture.batchReceipt()));
    }

    @Test
    void unchangedCheckReturnsZeroWithTwoNonExceededReceipts(@TempDir Path directory)
            throws Exception {
        Fixture fixture = fixture(directory);
        VerifyRunner runner = VerifyRunner.forTest((job, input, output) -> {
            writePng(output, Color.WHITE);
            return 0;
        }, null);

        int exit = runner.execute(fixture.manifest(), VerifyPreflight.Mode.CHECK);

        assertEquals(0, exit);
        assertTrue(Files.readString(fixture.receiptOne())
            .contains("\"exceeded\": false"));
        assertTrue(Files.readString(fixture.receiptTwo())
            .contains("\"exceeded\": false"));
        assertTrue(Files.readString(fixture.batchReceipt()).contains("\"exit\": 0"));
    }

    @Test
    void laterCaptureFailurePublishesNoBaselineReplacement(@TempDir Path directory)
            throws Exception {
        Fixture fixture = fixture(directory);
        byte[] oneBefore = Files.readAllBytes(fixture.baselineOne());
        byte[] twoBefore = Files.readAllBytes(fixture.baselineTwo());
        VerifyRunner runner = VerifyRunner.forTest((job, input, output) -> {
            if (job.id().equals("one")) {
                writePng(output, Color.BLUE);
                return 0;
            }
            assertArrayEquals(oneBefore, Files.readAllBytes(fixture.baselineOne()));
            assertArrayEquals(twoBefore, Files.readAllBytes(fixture.baselineTwo()));
            return 1;
        }, null);

        int exit = runner.execute(fixture.manifest(), VerifyPreflight.Mode.UPDATE);

        assertEquals(1, exit);
        assertArrayEquals(oneBefore, Files.readAllBytes(fixture.baselineOne()));
        assertArrayEquals(twoBefore, Files.readAllBytes(fixture.baselineTwo()));
        assertTrue(Files.readString(fixture.receiptOne())
            .contains("\"status\": \"batch-aborted\""));
        assertTrue(Files.readString(fixture.receiptTwo())
            .contains("\"status\": \"capture-failed\""));
        assertTrue(Files.readString(fixture.batchReceipt()).contains("\"replaced\": 0"));
    }

    @Test
    void updateCreatesTheWholeMissingBaselineSetOnlyAfterEveryCaptureSucceeds(
            @TempDir Path directory) throws Exception {
        Fixture fixture = fixture(directory);
        Files.delete(fixture.baselineOne());
        Files.delete(fixture.baselineTwo());
        AtomicInteger captures = new AtomicInteger();
        VerifyRunner runner = VerifyRunner.forTest((job, input, output) -> {
            assertFalse(Files.exists(fixture.baselineOne()));
            assertFalse(Files.exists(fixture.baselineTwo()));
            captures.incrementAndGet();
            writePng(output, job.id().equals("one") ? Color.BLUE : Color.RED);
            return 0;
        }, null);

        int exit = runner.execute(fixture.manifest(), VerifyPreflight.Mode.UPDATE);

        assertEquals(0, exit);
        assertEquals(2, captures.get());
        assertTrue(Files.isRegularFile(fixture.baselineOne()));
        assertTrue(Files.isRegularFile(fixture.baselineTwo()));
        assertTrue(Files.readString(fixture.batchReceipt()).contains("\"replaced\": 2"));
    }

    @Test
    void unsupportedAtomicMoveFallbackIsNamedWithoutAnAtomicClaim(
            @TempDir Path directory) throws Exception {
        Fixture fixture = fixture(directory);
        AtomicInteger atomicAttempts = new AtomicInteger();
        AtomicInteger fallbacks = new AtomicInteger();
        ArtifactWriter.MoveStrategy fallbackMover = (temporary, target, atomic) -> {
            if (atomic) {
                if (atomicAttempts.get() == 0) {
                    try (var siblings = Files.list(target.getParent())) {
                        assertEquals(2, siblings.filter(path -> path.getFileName().toString()
                            .startsWith(".brewshot-verify-")).count(),
                            "the full commit set must exist before replacement one");
                    }
                }
                atomicAttempts.incrementAndGet();
                throw new AtomicMoveNotSupportedException(
                    temporary.toString(), target.toString(), "forced by test");
            }
            fallbacks.incrementAndGet();
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        };
        VerifyRunner runner = VerifyRunner.forTest((job, input, output) -> {
            writePng(output, job.id().equals("one") ? Color.BLUE : Color.RED);
            return 0;
        }, fallbackMover);

        int exit = runner.execute(fixture.manifest(), VerifyPreflight.Mode.UPDATE);

        assertEquals(0, exit);
        assertEquals(2, atomicAttempts.get());
        assertEquals(2, fallbacks.get());
        String batch = Files.readString(fixture.batchReceipt());
        assertTrue(batch.contains("\"crashAtomic\": false"));
        assertTrue(batch.contains("\"nonAtomicReplacements\": 2"));
        assertTrue(Files.readString(fixture.receiptOne())
            .contains("\"outcome\": \"non-atomic-replace\""));
    }

    @Test
    void commitFailureReportsThePublishedSubsetAndStopsLaterReplacements(
            @TempDir Path directory) throws Exception {
        Fixture fixture = fixture(directory);
        byte[] twoBefore = Files.readAllBytes(fixture.baselineTwo());
        byte[] oneReplacement = pngBytes(Color.BLUE);
        ArtifactWriter.MoveStrategy failSecond = (temporary, target, atomic) -> {
            if (target.getFileName().toString().equals("two.png")) {
                throw new IOException("forced second replacement failure");
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        };
        VerifyRunner runner = VerifyRunner.forTest((job, input, output) -> {
            Files.write(output, job.id().equals("one") ? oneReplacement : pngBytes(Color.RED));
            return 0;
        }, failSecond);

        int exit = runner.execute(fixture.manifest(), VerifyPreflight.Mode.UPDATE);

        assertEquals(1, exit);
        assertArrayEquals(oneReplacement, Files.readAllBytes(fixture.baselineOne()));
        assertArrayEquals(twoBefore, Files.readAllBytes(fixture.baselineTwo()));
        String batch = Files.readString(fixture.batchReceipt());
        assertTrue(batch.contains("\"partial\": true"));
        assertTrue(batch.contains("\"replaced\": 1"));
        assertTrue(batch.contains("\"failed\": 1"));
        assertTrue(Files.readString(fixture.receiptTwo())
            .contains("\"status\": \"commit-failed\""));
    }

    @Test
    void processCrashAfterReplacementLeavesAnInProgressBatchMarker(
            @TempDir Path directory) throws Exception {
        Fixture fixture = fixture(directory);
        Files.createDirectories(fixture.receiptOne().getParent());
        Files.writeString(fixture.receiptOne(), "old job receipt");
        Files.writeString(fixture.receiptTwo(), "old job receipt");
        Files.writeString(fixture.batchReceipt(),
            "{\"state\":\"complete\",\"attemptId\":\"old\"}");
        AtomicInteger moves = new AtomicInteger();
        ArtifactWriter.MoveStrategy crashSecond = (temporary, target, atomic) -> {
            if (moves.incrementAndGet() == 2) {
                throw new AssertionError("simulated process crash");
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        };
        VerifyRunner runner = VerifyRunner.forTest((job, input, output) -> {
            writePng(output, job.id().equals("one") ? Color.BLUE : Color.RED);
            return 0;
        }, crashSecond);

        assertThrows(AssertionError.class,
            () -> runner.execute(fixture.manifest(), VerifyPreflight.Mode.UPDATE));

        assertEquals("old job receipt", Files.readString(fixture.receiptOne()));
        String marker = Files.readString(fixture.batchReceipt());
        assertTrue(marker.contains("\"state\": \"in-progress\""));
        assertTrue(marker.contains("\"powerLossDurable\": false"));
        assertFalse(marker.contains("\"attemptId\": \"old\""));
    }

    @Test
    void capturesUseThePreflightedInputGeneration(@TempDir Path directory)
            throws Exception {
        Fixture fixture = fixture(directory);
        String originalTwo = Files.readString(directory.resolve("fixtures/two.html"));
        VerifyRunner runner = VerifyRunner.forTest((job, input, output) -> {
            if (job.id().equals("one")) {
                Files.writeString(directory.resolve("fixtures/two.html"), "changed live input");
            } else {
                assertEquals(originalTwo, Files.readString(input),
                    "capture must consume the staged generation, not a later live edit");
            }
            writePng(output, Color.WHITE);
            return 0;
        }, null);

        assertEquals(0, runner.execute(fixture.manifest(), VerifyPreflight.Mode.CHECK));
        assertTrue(Files.readString(fixture.receiptTwo()).contains("\"inputSha256\""));
    }

    @Test
    void baselineDriftDuringCheckIsVisibleAndOutranksACleanSnapshotDiff(
            @TempDir Path directory) throws Exception {
        Fixture fixture = fixture(directory);
        VerifyRunner runner = VerifyRunner.forTest((job, input, output) -> {
            if (job.id().equals("one")) {
                writePng(fixture.baselineTwo(), Color.GREEN);
            }
            writePng(output, Color.WHITE);
            return 0;
        }, null);

        int exit = runner.execute(fixture.manifest(), VerifyPreflight.Mode.CHECK);

        assertEquals(2, exit);
        assertTrue(Files.readString(fixture.receiptTwo())
            .contains("\"status\": \"preflight-failed\""));
        assertTrue(Files.readString(fixture.batchReceipt())
            .contains("baseline changed during check"));
    }

    @Test
    void manifestChangeAfterCaptureRefusesBeforeTheFirstReplacement(
            @TempDir Path directory) throws Exception {
        Fixture fixture = fixture(directory);
        byte[] oneBefore = Files.readAllBytes(fixture.baselineOne());
        byte[] twoBefore = Files.readAllBytes(fixture.baselineTwo());
        VerifyRunner runner = VerifyRunner.forTest((job, input, output) -> {
            writePng(output, Color.BLUE);
            if (job.id().equals("two")) {
                Files.writeString(fixture.manifest(),
                    Files.readString(fixture.manifest()) + " ");
            }
            return 0;
        }, null);

        int exit = runner.execute(fixture.manifest(), VerifyPreflight.Mode.UPDATE);

        assertEquals(2, exit);
        assertArrayEquals(oneBefore, Files.readAllBytes(fixture.baselineOne()));
        assertArrayEquals(twoBefore, Files.readAllBytes(fixture.baselineTwo()));
        try (var baselines = Files.list(directory.resolve("baselines"))) {
            assertFalse(baselines.anyMatch(path ->
                path.getFileName().toString().startsWith(".brewshot-verify-")));
        }
    }

    private static Fixture fixture(Path directory) throws Exception {
        Files.createDirectories(directory.resolve("fixtures"));
        Files.writeString(directory.resolve("fixtures/one.html"), "<main>one</main>");
        Files.writeString(directory.resolve("fixtures/two.html"), "<main>two</main>");
        Path baselineOne = directory.resolve("baselines/one.png");
        Path baselineTwo = directory.resolve("baselines/two.png");
        Files.createDirectories(baselineOne.getParent());
        writePng(baselineOne, Color.WHITE);
        writePng(baselineTwo, Color.WHITE);
        Path manifest = directory.resolve("shots.json");
        Files.writeString(manifest, """
            {"version":1,"jobs":[
              {"id":"one","input":"fixtures/one.html","baseline":"baselines/one.png",
               "receipt":"receipts/one.json","heatmap":"receipts/one.diff.png"},
              {"id":"two","input":"fixtures/two.html","baseline":"baselines/two.png",
               "receipt":"receipts/two.json","heatmap":"receipts/two.diff.png"}
            ]}
            """);
        return new Fixture(manifest, baselineOne, baselineTwo,
            directory.resolve("receipts/one.json"),
            directory.resolve("receipts/two.json"),
            directory.resolve("shots.json.verify.json"));
    }

    private static void writePng(Path path, Color color) throws IOException {
        Files.write(path, pngBytes(color));
    }

    private static byte[] pngBytes(Color color) throws IOException {
        BufferedImage image = new BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        try (var output = new java.io.ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(image, "png", output));
            return output.toByteArray();
        }
    }

    @SuppressWarnings("unchecked")
    private static String deterministicCore(Path receipt) throws Exception {
        Object parsed = MiniJson.parseStrict(Files.readString(receipt));
        Object core = ((java.util.Map<String, Object>) parsed).get("deterministicCore");
        return MiniJson.stringifyPretty(core);
    }

    private record Fixture(Path manifest, Path baselineOne, Path baselineTwo,
                           Path receiptOne, Path receiptTwo, Path batchReceipt) {
    }
}
