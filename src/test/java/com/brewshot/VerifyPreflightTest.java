package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifyPreflightTest {

    @Test
    void checkBuildsTheWholeGraphWithoutWriting(@TempDir Path directory) throws Exception {
        Path manifestPath = fixture(directory, true);
        VerifyManifest manifest = VerifyManifest.load(manifestPath);

        VerifyPreflight.Prepared prepared = VerifyPreflight.inspect(
            manifest, VerifyPreflight.Mode.CHECK, "checkstage01");

        assertEquals(directory.toRealPath(), prepared.workspaceReal());
        assertEquals(directory.resolve("shots.json.verify.json"), prepared.batchReceipt());
        assertEquals(directory.resolve(".brewshot-verify-stage-checkstage01"),
            prepared.stagingRoot());
        assertFalse(Files.exists(prepared.stagingRoot()));
        assertFalse(Files.exists(prepared.batchReceipt()));
        assertEquals(1, prepared.jobs().size());
        assertEquals(directory.resolve(
            "fixtures/.brewshot-verify-checkstage01-home.input.html"),
            prepared.jobs().getFirst().stagedInput());
        assertFalse(Files.exists(prepared.jobs().getFirst().stagedInput()));
        assertNull(prepared.jobs().getFirst().stagedBaseline());
        assertNotNull(prepared.jobs().getFirst().stagedHeatmap());
    }

    @Test
    void updateAcceptsAMissingBaselineAndStagesItsCandidate(@TempDir Path directory)
            throws Exception {
        Path manifestPath = fixture(directory, false);
        VerifyPreflight.Prepared prepared = VerifyPreflight.inspect(
            VerifyManifest.load(manifestPath), VerifyPreflight.Mode.UPDATE, "updatestage1");

        assertFalse(Files.exists(directory.resolve("baselines/home.png")));
        assertEquals(prepared.stagingRoot().resolve("home.baseline.png"),
            prepared.jobs().getFirst().stagedBaseline());
        assertEquals(directory.resolve(
            "baselines/.brewshot-verify-updatestage1-home.tmp"),
            prepared.jobs().getFirst().commitTemporary());
        assertFalse(Files.exists(prepared.jobs().getFirst().commitTemporary()));
        assertFalse(Files.exists(prepared.stagingRoot()));
    }

    @Test
    void updateCommitTemporaryParticipatesInWholeGraphAliasChecks(
            @TempDir Path directory) throws Exception {
        Path manifestPath = fixture(directory, false);
        Files.writeString(manifestPath, manifest("baselines/home.png",
            "baselines/.brewshot-verify-committemp1-home.tmp/result.json"));

        VerifyPreflight.PreflightException failure = assertThrows(
            VerifyPreflight.PreflightException.class,
            () -> VerifyPreflight.inspect(VerifyManifest.load(manifestPath),
                VerifyPreflight.Mode.UPDATE, "committemp1"));

        assertTrue(failure.getMessage().contains("ancestor/descendant"));
        assertNoStage(directory);
    }

    @Test
    void checkRequiresAnExistingBaselineBeforeAnyEffect(@TempDir Path directory)
            throws Exception {
        Path manifestPath = fixture(directory, false);
        VerifyPreflight.PreflightException failure = assertThrows(
            VerifyPreflight.PreflightException.class,
            () -> VerifyPreflight.inspect(VerifyManifest.load(manifestPath),
                VerifyPreflight.Mode.CHECK, "missingbase1"));

        assertEquals(VerifyManifest.FailureCategory.PREFLIGHT, failure.failure().category());
        assertTrue(failure.getMessage().contains("baseline must be an existing regular file"));
        assertNoStage(directory);
    }

    @Test
    void manifestDigestIsRecheckedBeforeStaging(@TempDir Path directory) throws Exception {
        Path manifestPath = fixture(directory, true);
        VerifyPreflight.Prepared prepared = VerifyPreflight.inspect(
            VerifyManifest.load(manifestPath), VerifyPreflight.Mode.CHECK, "digeststage1");
        Files.writeString(manifestPath,
            Files.readString(manifestPath, StandardCharsets.UTF_8) + " ",
            StandardCharsets.UTF_8);

        VerifyPreflight.PreflightException failure = assertThrows(
            VerifyPreflight.PreflightException.class, prepared::createStaging);
        assertTrue(failure.getMessage().contains("manifest changed"));
        assertFalse(Files.exists(prepared.stagingRoot()));
    }

    @Test
    void missingManifestIsRecheckedBeforeStaging(@TempDir Path directory) throws Exception {
        Path manifestPath = fixture(directory, true);
        VerifyPreflight.Prepared prepared = VerifyPreflight.inspect(
            VerifyManifest.load(manifestPath), VerifyPreflight.Mode.CHECK, "missingstage1");
        Files.delete(manifestPath);

        VerifyPreflight.PreflightException failure = assertThrows(
            VerifyPreflight.PreflightException.class, prepared::createStaging);
        assertTrue(failure.getMessage().contains("manifest must be an existing regular file"));
        assertFalse(Files.exists(prepared.stagingRoot()));
    }

    @Test
    void normalizedAndDerivedOutputAliasesRefuse(@TempDir Path directory) throws Exception {
        Files.createDirectories(directory.resolve("fixtures"));
        Files.writeString(directory.resolve("fixtures/home.html"), "<main/>");
        Files.createDirectories(directory.resolve("baselines"));
        Files.write(directory.resolve("baselines/home.png"), new byte[] {1});
        Path manifestPath = directory.resolve("shots.json");
        Files.writeString(manifestPath, manifest(
            "baselines/home.png", "receipts/../shots.json.verify.json"));

        VerifyPreflight.PreflightException failure = assertThrows(
            VerifyPreflight.PreflightException.class,
            () -> VerifyPreflight.inspect(VerifyManifest.load(manifestPath),
                VerifyPreflight.Mode.CHECK, "aliasstage01"));
        assertTrue(failure.getMessage().contains("path alias"));
        assertNoStage(directory);
    }

    @Test
    void symlinkTraversalRefusesBeforeWriting(@TempDir Path directory) throws Exception {
        Path manifestPath = fixture(directory, true);
        Path real = directory.resolve("fixtures");
        Path link = directory.resolve("linked-fixtures");
        try {
            Files.createSymbolicLink(link, real.getFileName());
        } catch (UnsupportedOperationException unavailable) {
            Assumptions.assumeTrue(false,
                "symbolic links are unavailable: " + unavailable.getMessage());
        }
        Files.writeString(manifestPath,
            manifest("baselines/home.png", "receipts/home.json")
                .replace("fixtures/home.html", "linked-fixtures/home.html"));

        VerifyPreflight.PreflightException failure = assertThrows(
            VerifyPreflight.PreflightException.class,
            () -> VerifyPreflight.inspect(VerifyManifest.load(manifestPath),
                VerifyPreflight.Mode.CHECK, "linkstage001"));
        assertTrue(failure.getMessage().contains("symbolic link"));
        assertNoStage(directory);
    }

    @Test
    void symlinkAndRealOutputSpellingsCannotReachTheSameTarget(
            @TempDir Path directory) throws Exception {
        Files.createDirectories(directory.resolve("fixtures"));
        Files.writeString(directory.resolve("fixtures/one.html"), "<main>one</main>");
        Files.writeString(directory.resolve("fixtures/two.html"), "<main>two</main>");
        Files.createDirectories(directory.resolve("baselines"));
        Files.write(directory.resolve("baselines/one.png"), new byte[] {1});
        Files.write(directory.resolve("baselines/two.png"), new byte[] {2});
        Path realOutputs = Files.createDirectories(directory.resolve("real-outputs"));
        Path linkedOutputs = directory.resolve("linked-outputs");
        try {
            Files.createSymbolicLink(linkedOutputs, realOutputs.getFileName());
        } catch (UnsupportedOperationException unavailable) {
            Assumptions.assumeTrue(false,
                "symbolic links are unavailable: " + unavailable.getMessage());
        }
        Path manifestPath = directory.resolve("shots.json");
        Files.writeString(manifestPath, """
            {"version":1,"jobs":[
              {"id":"one","input":"fixtures/one.html","baseline":"baselines/one.png",
               "receipt":"real-outputs/shared.json"},
              {"id":"two","input":"fixtures/two.html","baseline":"baselines/two.png",
               "receipt":"linked-outputs/shared.json"}
            ]}
            """);

        VerifyPreflight.PreflightException failure = assertThrows(
            VerifyPreflight.PreflightException.class,
            () -> VerifyPreflight.inspect(VerifyManifest.load(manifestPath),
                VerifyPreflight.Mode.CHECK, "linkalias001"));
        assertTrue(failure.getMessage().contains("symbolic link")
            || failure.getMessage().contains("path alias"));
        assertFalse(Files.exists(realOutputs.resolve("shared.json")));
        assertNoStage(directory);
    }

    @Test
    void hardLinkedWritableDestinationRefuses(@TempDir Path directory) throws Exception {
        Path manifestPath = fixture(directory, true);
        Path baseline = directory.resolve("baselines/home.png");
        Path receipt = directory.resolve("receipts/home.json");
        Files.createDirectories(receipt.getParent());
        try {
            Files.createLink(receipt, baseline);
        } catch (UnsupportedOperationException unavailable) {
            Assumptions.assumeTrue(false,
                "hard links are unavailable: " + unavailable.getMessage());
        }

        VerifyPreflight.PreflightException failure = assertThrows(
            VerifyPreflight.PreflightException.class,
            () -> VerifyPreflight.inspect(VerifyManifest.load(manifestPath),
                VerifyPreflight.Mode.UPDATE, "hardlink001"));
        assertTrue(failure.getMessage().contains("path alias"));
        assertNoStage(directory);
    }

    @Test
    void ancestorDestinationCollisionRefuses(@TempDir Path directory) throws Exception {
        Path manifestPath = fixture(directory, true);
        Files.writeString(manifestPath,
            manifest("baselines/home.png",
                ".brewshot-verify-stage-ancestor01/home.receipt.json"));

        VerifyPreflight.PreflightException failure = assertThrows(
            VerifyPreflight.PreflightException.class,
            () -> VerifyPreflight.inspect(VerifyManifest.load(manifestPath),
                VerifyPreflight.Mode.CHECK, "ancestor01"));
        assertTrue(failure.getMessage().contains("ancestor/descendant"));
        assertNoStage(directory);
    }

    @Test
    void successfulPreparationCreatesOnlyAPrivateStageRoot(@TempDir Path directory)
            throws Exception {
        Path manifestPath = fixture(directory, true);
        byte[] baselineBefore = Files.readAllBytes(directory.resolve("baselines/home.png"));
        VerifyPreflight.Prepared prepared = VerifyPreflight.inspect(
            VerifyManifest.load(manifestPath), VerifyPreflight.Mode.UPDATE, "createstage1");

        VerifyPreflight.Staging staging = prepared.createStaging();

        assertTrue(Files.isDirectory(staging.root()));
        assertFalse(Files.exists(prepared.batchReceipt()));
        assertFalse(Files.exists(directory.resolve("receipts/home.json")));
        assertEquals(java.util.List.of((byte) 1), boxed(baselineBefore));
        assertEquals(java.util.List.of((byte) 1),
            boxed(Files.readAllBytes(directory.resolve("baselines/home.png"))));
        if (Files.getFileStore(staging.root()).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(staging.root()));
        }
    }

    @Test
    void preexistingStageRootRefusesDuringReadOnlyInspection(@TempDir Path directory)
            throws Exception {
        Path manifestPath = fixture(directory, true);
        Path collision = directory.resolve(".brewshot-verify-stage-collision1");
        Files.createDirectory(collision);

        VerifyPreflight.PreflightException failure = assertThrows(
            VerifyPreflight.PreflightException.class,
            () -> VerifyPreflight.inspect(VerifyManifest.load(manifestPath),
                VerifyPreflight.Mode.CHECK, "collision1"));
        assertTrue(failure.getMessage().contains("staging root already exists"));
        try (var children = Files.list(collision)) {
            assertEquals(0, children.count());
        }
    }

    private static Path fixture(Path directory, boolean baseline) throws Exception {
        Files.createDirectories(directory.resolve("fixtures"));
        Files.writeString(directory.resolve("fixtures/home.html"), "<main/>");
        Files.createDirectories(directory.resolve("baselines"));
        if (baseline) {
            Files.write(directory.resolve("baselines/home.png"), new byte[] {1});
        }
        Path manifestPath = directory.resolve("shots.json");
        Files.writeString(manifestPath,
            manifest("baselines/home.png", "receipts/home.json"));
        return manifestPath;
    }

    private static String manifest(String baseline, String receipt) {
        return "{\"version\":1,\"jobs\":[{\"id\":\"home\","
            + "\"input\":\"fixtures/home.html\",\"baseline\":\"" + baseline
            + "\",\"receipt\":\"" + receipt
            + "\",\"heatmap\":\"receipts/home.diff.png\"}]}";
    }

    private static void assertNoStage(Path directory) throws Exception {
        try (var children = Files.list(directory)) {
            assertFalse(children.anyMatch(path ->
                path.getFileName().toString().startsWith(".brewshot-verify-stage-")));
        }
    }

    private static java.util.List<Byte> boxed(byte[] bytes) {
        java.util.List<Byte> boxed = new java.util.ArrayList<>(bytes.length);
        for (byte value : bytes) {
            boxed.add(value);
        }
        return boxed;
    }
}
