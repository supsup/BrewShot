package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
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
import java.time.Duration;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Transactional artifact-write failure paths, without Chrome or ImageIO. */
class ArtifactWriterTest {

    // Stage A of plan018: these tests intentionally name the proposed package-private
    // producer API. It does not exist on main yet. A compile error is SETUP, not semantic RED.
    private static volatile byte[] allocationWitness;

    /** Case 2: real format bytes, an independent read census, and an allocation discriminator. */
    @Test
    void arrayReceiptUsesTheSuppliedSnapshotWithoutArtifactReadsOrAnArtifactSizedCopy(
            @TempDir Path directory) throws Exception {
        for (String format : List.of("png", "jpeg", "pdf")) {
            Path venue = Files.createDirectory(directory.resolve(format));
            Path target = venue.resolve("capture." + format);
            byte[] produced = receiptFixture(format);
            String expectedHash = receiptHash(produced);
            var receipt = withoutArtifactReads(venue,
                () -> ArtifactWriter.writeBytesWithReceipt(target, produced, true));
            assertEquals(produced.length, receipt.outBytes(), format);
            assertEquals(expectedHash, receipt.outSha256(), format);
            org.junit.jupiter.api.Assertions.assertArrayEquals(produced, Files.readAllBytes(target));
            java.util.Arrays.fill(produced, (byte) 0x7f);
            assertEquals(expectedHash, receipt.outSha256(), "receipt must not retain mutable input");
        }

        // Measuring just the writer avoids charging image/fixture construction to production.
        // This is a heap-allocation counter, not RSS or a claimed universal heap bound.
        var ordinaryBean = ManagementFactory.getThreadMXBean();
        assertTrue(ordinaryBean instanceof com.sun.management.ThreadMXBean,
            "INSTRUMENT: this venue must expose the allocation counter; do not skip green");
        var allocations = (com.sun.management.ThreadMXBean) ordinaryBean;
        assertTrue(allocations.isThreadAllocatedMemorySupported(), "INSTRUMENT: unsupported counter");
        boolean wasEnabled = allocations.isThreadAllocatedMemoryEnabled();
        allocations.setThreadAllocatedMemoryEnabled(true);
        try {
            ArtifactWriter.writeBytesWithReceipt(directory.resolve("warm.bin"), new byte[] {1}, true);
            byte[] large = new byte[8 * 1024 * 1024];
            for (int i = 0; i < large.length; i++) large[i] = (byte) (i * 31);
            long copied = allocatedBytes(allocations, () -> allocationWitness = large.clone());
            assertTrue(copied >= large.length, "INSTRUMENT: an escaping artifact-sized copy must register");
            allocationWitness = null;
            long spent = allocatedBytes(allocations, () ->
                ArtifactWriter.writeBytesWithReceipt(directory.resolve("large.bin"), large, true));
            assertTrue(spent < large.length / 2,
                "writer allocated " + spent + " bytes for an " + large.length + " byte snapshot");
            copied = allocatedBytes(allocations, () -> allocationWitness = large.clone());
            assertTrue(copied >= large.length, "INSTRUMENT: counter must still see the copy after the write");
        } finally {
            allocationWitness = null;
            allocations.setThreadAllocatedMemoryEnabled(wasEnabled);
        }
    }

    @FunctionalInterface
    interface ReceiptOperation<T> { T run() throws Exception; }

    @FunctionalInterface
    private interface AllocationOperation { void run() throws Exception; }

    private static long allocatedBytes(com.sun.management.ThreadMXBean bean,
            AllocationOperation operation) throws Exception {
        long before = bean.getCurrentThreadAllocatedBytes();
        operation.run();
        long after = bean.getCurrentThreadAllocatedBytes();
        assertTrue(before >= 0 && after >= before, "INSTRUMENT: unavailable/nonmonotonic counter");
        return after - before;
    }

    /**
     * Record real JDK content reads during the producer call, including sibling temporaries.
     * Before/after probes prove the instrument fires. Independent file/digest oracles run only
     * AFTER recording stops. An unavailable recorder, dropped events, or a missing probe is an
     * instrument failure, never a no-read PASS. Metadata calls are outside this content census.
     */
    static <T> T withoutArtifactReads(Path directory, ReceiptOperation<T> operation) throws Exception {
        Path before = directory.resolve("read-control-before.bin");
        Path after = directory.resolve("read-control-after.bin");
        Files.write(before, new byte[] {1, 2, 3});
        Files.write(after, new byte[] {4, 5, 6});
        Path recordingPath = directory.resolve("read-census.jfr");
        T result;
        try (Recording recording = new Recording()) {
            recording.enable("jdk.FileRead").withThreshold(Duration.ZERO)
                .withoutStackTrace().with("throttle", "off");
            recording.enable("jdk.DataLoss");
            recording.start();
            assertEquals(3, Files.readAllBytes(before).length);
            result = operation.run();
            assertEquals(3, Files.readAllBytes(after).length);
            recording.stop();
            recording.dump(recordingPath);
        }
        int firstProbe = 0, lastProbe = 0;
        List<String> artifactReads = new java.util.ArrayList<>();
        Path absolute = directory.toAbsolutePath().normalize();
        for (var event : RecordingFile.readAllEvents(recordingPath)) {
            assertFalse(event.getEventType().getName().equals("jdk.DataLoss"),
                "INSTRUMENT: JFR dropped events; read census is incomplete");
            if (!event.getEventType().getName().equals("jdk.FileRead")) continue;
            String name = event.getString("path");
            if (name == null) continue;
            Path path = Path.of(name).toAbsolutePath().normalize();
            if (path.equals(before.toAbsolutePath().normalize())) firstProbe++;
            else if (path.equals(after.toAbsolutePath().normalize())) lastProbe++;
            else if (path.startsWith(absolute)) artifactReads.add(path.toString());
        }
        assertTrue(firstProbe > 0 && lastProbe > 0,
            "INSTRUMENT: both real content-read probes must appear; first=" + firstProbe + ", last=" + lastProbe);
        assertEquals(List.of(), artifactReads, "producer re-read an artifact or its temporary");
        return result;
    }

    static String receiptHash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Browser-free input bytes, created before any production measurement. */
    static byte[] receiptFixture(String format) throws IOException {
        if (!format.equals("pdf")) {
            BufferedImage image = new BufferedImage(16, 12, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 12; y++) for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, (x * 7919) ^ (y * 104729));
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(image, format, bytes), "INSTRUMENT: missing " + format + " writer");
            return bytes.toByteArray();
        }
        String[] objects = {
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 16 12] /Contents 4 0 R >>",
            "<< /Length 0 >>\nstream\n\nendstream"
        };
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        int[] offsets = new int[objects.length];
        for (int i = 0; i < objects.length; i++) {
            offsets[i] = pdf.length();
            pdf.append(i + 1).append(" 0 obj\n").append(objects[i]).append("\nendobj\n");
        }
        int xref = pdf.length();
        pdf.append("xref\n0 5\n0000000000 65535 f \n");
        for (int offset : offsets) pdf.append(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offset));
        pdf.append("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.US_ASCII);
    }

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
