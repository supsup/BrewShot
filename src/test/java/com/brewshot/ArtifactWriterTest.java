package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Transactional artifact-write failure paths, without Chrome or ImageIO. */
class ArtifactWriterTest {

    @Test
    void injectedPartialWriteLeavesExistingTargetIntactAndNoTempResidue(
            @TempDir Path directory) throws Exception {
        Path target = directory.resolve("evidence.png");
        Files.writeString(target, "complete-old-artifact");

        IOException failure = assertThrows(IOException.class,
            () -> ArtifactWriter.write(target, temporary -> {
                Files.writeString(temporary, "partial-new-artifact");
                throw new IOException("injected write failure");
            }));

        assertEquals("injected write failure", failure.getMessage());
        assertEquals("complete-old-artifact", Files.readString(target));
        try (var files = Files.list(directory)) {
            assertEquals(List.of(target), files.toList(),
                "the sibling temporary file must always be cleaned");
        }
    }

    @Test
    void injectedPartialWriteLeavesNoNewTargetOrTempResidue(@TempDir Path directory) {
        Path target = directory.resolve("new.json");

        assertThrows(IOException.class, () -> ArtifactWriter.write(target, temporary -> {
            Files.writeString(temporary, "{\"partial\":");
            throw new IOException("injected");
        }));

        assertFalse(Files.exists(target));
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count(), "no target or sibling temp may survive");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void unsupportedAtomicMoveFallsBackOnlyAfterTheTempArtifactIsComplete(
            @TempDir Path directory) throws Exception {
        Path target = directory.resolve("fallback.pdf");
        Files.writeString(target, "old");
        AtomicInteger calls = new AtomicInteger();

        ArtifactWriter.write(target, temporary -> Files.writeString(temporary, "complete"),
            (temporary, destination, atomic) -> {
                calls.incrementAndGet();
                assertEquals("complete", Files.readString(temporary),
                    "both move attempts see a fully written temporary artifact");
                if (atomic) {
                    throw new AtomicMoveNotSupportedException(
                        temporary.toString(), destination.toString(), "injected");
                }
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            });

        assertEquals(2, calls.get(), "atomic attempt followed by one non-atomic fallback");
        assertEquals("complete", Files.readString(target));
        try (var files = Files.list(directory)) {
            assertEquals(List.of(target), files.toList());
        }
    }

    @Test
    void replacingExistingTargetPreservesPosixPermissions(
            @TempDir Path directory) throws Exception {
        Path target = directory.resolve("mode-sensitive.json");
        Files.writeString(target, "old");
        Assumptions.assumeTrue(
            Files.getFileAttributeView(target, PosixFileAttributeView.class) != null,
            "filesystem does not expose POSIX permissions");
        Set<PosixFilePermission> expected = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ);
        Files.setPosixFilePermissions(target, expected);

        ArtifactWriter.writeString(target, "new", StandardCharsets.UTF_8);

        assertEquals("new", Files.readString(target));
        assertEquals(expected, Files.getPosixFilePermissions(target));
    }

    @Test
    void relativeLeafSymlinkRemainsAndItsReferentIsReplaced(
            @TempDir Path directory) throws Exception {
        Path realDirectory = Files.createDirectory(directory.resolve("real"));
        Path linkDirectory = Files.createDirectory(directory.resolve("links"));
        Path referent = realDirectory.resolve("evidence.json");
        Files.writeString(referent, "old");
        Path link = linkDirectory.resolve("latest.json");
        Path relativeTarget = Path.of("../real/evidence.json");
        createSymlinkOrSkip(link, relativeTarget);

        ArtifactWriter.writeString(link, "new", StandardCharsets.UTF_8);

        assertTrue(Files.isSymbolicLink(link), "the output symlink entry must survive");
        assertEquals(relativeTarget, Files.readSymbolicLink(link));
        assertEquals("new", Files.readString(referent));
        assertEquals("new", Files.readString(link));
    }

    @Test
    void brokenAndCyclicSymlinksFailBeforeWriterOrTempCreation(
            @TempDir Path directory) throws Exception {
        AtomicBoolean writerCalled = new AtomicBoolean();
        Path broken = directory.resolve("broken.json");
        createSymlinkOrSkip(broken, Path.of("missing.json"));

        IOException brokenFailure = assertThrows(IOException.class,
            () -> ArtifactWriter.write(broken, temporary -> writerCalled.set(true)));
        assertTrue(brokenFailure.getMessage().contains("broken"), brokenFailure.getMessage());
        assertFalse(writerCalled.get(), "broken link must fail before invoking the writer");

        Path first = directory.resolve("first.json");
        Path second = directory.resolve("second.json");
        createSymlinkOrSkip(first, Path.of("second.json"));
        createSymlinkOrSkip(second, Path.of("first.json"));
        IOException cycleFailure = assertThrows(IOException.class,
            () -> ArtifactWriter.write(first, temporary -> writerCalled.set(true)));
        assertTrue(cycleFailure.getMessage().contains("cycle"), cycleFailure.getMessage());
        assertFalse(writerCalled.get(), "cycle must fail before invoking the writer");

        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                "link validation must happen before sibling temp creation");
        }
    }

    private static void createSymlinkOrSkip(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException | IOException unavailable) {
            Assumptions.assumeTrue(false,
                "symbolic links unavailable for this test: " + unavailable);
        }
    }
}
