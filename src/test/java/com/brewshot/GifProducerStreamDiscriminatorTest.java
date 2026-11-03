package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProviderException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plan 0181881e, accepted feasibility slice PROJECT/brewshot/864, answering 845.
 * Everything added here is TEST ONLY. No producer API or receipt is shipped.
 *
 * <p>The reference is the actual GifWriter.write sequence. Candidate encoding calls its
 * existing private palette/dither/metadata helpers, rather than copying those algorithms.
 * The candidate retains neither a second encoded artifact nor a post-write file reader.
 * File reads below belong exclusively to independent, post-completion test oracles.
 *
 * <p>The measured bound is the sum of actual retained byte[] payloads in the selected
 * JDK MemoryCache, NOT heap, RSS, allocation, decoded-frame storage or logical pending
 * bytes. Physical observation needs this option in the TEST JVM:
 * --add-opens=java.desktop/javax.imageio.stream=ALL-UNNAMED
 * If access or the implementation layout differs, fail with UNKNOWN; never substitute
 * streamPosition-flushedPosition as physical evidence. Fixpoint owns all actual builds.
 *
 * <p>For causal runs, select a positive parity test and set the test-JVM property
 * brewshot.gif.stream.discriminatorMutation to an enum name below. This deliberately
 * changes the candidate, not the reference or oracle. Default NONE is the control.
 */
class GifProducerStreamDiscriminatorTest {
    private static final int BLOCK = 8192;
    private static final long RETAINED_PAYLOAD_LIMIT = 2L * BLOCK;
    private static final String MUTANT_PROPERTY = "brewshot.gif.stream.discriminatorMutation";

    private enum Mutation {
        NONE, DROP_BYTE, DOUBLE_COUNT, REORDER_BYTES, OMIT_TRAILER,
        SEEK_BACKWARDS, NO_EAGER_FLUSH, FRAME_ONLY_FLUSH
    }

    private enum FlushPolicy { EACH_CHUNK, EACH_FRAME, AT_CLOSE }

    @Test
    void actualSequenceMatchesLegacyForTinyAndNonPowerOfTwoFrames(@TempDir Path dir)
            throws Exception {
        for (int[] shape : List.of(new int[] {1, 1, 1}, new int[] {31, 19, 1},
                new int[] {37, 23, 3})) {
            assertSequenceParity(dir, shape[0], shape[1], shape[2]);
        }
    }

    @Test
    void largeSingleFrameCrossesCacheBlocksAndGifSubblocksWithoutChangingBytes(@TempDir Path dir)
            throws Exception {
        Run run = assertSequenceParity(dir, 257, 97, 1);
        assertTrue(run.receipt().bytes() > 2L * BLOCK,
            "the real encoded fixture must exceed two cache blocks and GIF's 255-byte subblocks");
    }

    @Test
    void retainedEncodedCacheDoesNotGrowWithFrameCount(@TempDir Path dir) throws Exception {
        Run one = assertSequenceParity(dir, 73, 67, 1);
        Run many = assertSequenceParity(dir, 73, 67, 12);
        assertTrue(many.receipt().bytes() > 5L * BLOCK, "the many-frame control must grow");
        assertTrue(many.receipt().bytes() > one.receipt().bytes());
        assertTrue(one.cache().peakBytes <= RETAINED_PAYLOAD_LIMIT);
        assertTrue(many.cache().peakBytes <= RETAINED_PAYLOAD_LIMIT,
            "observe retained physical blocks, not just pending bytes");
    }

    @Test
    void standaloneWriterWriteAlsoHasExactStreamParity(@TempDir Path dir) throws Exception {
        List<byte[]> pngs = pngs(127, 71, 1);
        List<BufferedImage> frames = decode(pngs);
        Path reference = dir.resolve("standalone-reference.gif");
        ImageWriter writer = selectedWriter();
        try (OutputStream file = Files.newOutputStream(reference);
                MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(file)) {
            encode(writer, frames, stream, true, () -> { });
        } finally {
            writer.dispose();
        }
        Path candidate = dir.resolve("standalone-candidate.gif");
        Run run = candidate(pngs, candidate, true, selectedMutation());
        assertArrayEquals(Files.readAllBytes(reference), Files.readAllBytes(candidate));
        assertReceiptMatchesFile(run.receipt(), candidate);
        assertBoundAndSequential(run);
    }

    @Test
    void scalarChunkBoundaryAndOffsetWritesHaveTheSameByteCountAndDigest() throws Exception {
        byte[] expected = new byte[3 * BLOCK + 19];
        new Random(185L).nextBytes(expected);
        for (int chunk : new int[] {0, 1, 7, 255, 8191, 8192, 8193}) {
            ByteArrayOutputStream oracle = new ByteArrayOutputStream();
            DigestSink sink = new DigestSink(oracle, sha256(), Mutation.NONE);
            CacheProbe cache;
            try (sink; GuardedStream stream = new GuardedStream(sink, FlushPolicy.EACH_CHUNK)) {
                cache = stream.cache;
                if (chunk == 0) {
                    for (byte value : expected) { stream.write(value & 0xff); }
                } else {
                    byte[] padded = new byte[expected.length + 6];
                    System.arraycopy(expected, 0, padded, 3, expected.length);
                    stream.write(padded, 3, 0);
                    for (int at = 0; at < expected.length; at += chunk) {
                        stream.write(padded, at + 3, Math.min(chunk, expected.length - at));
                    }
                }
                sink.encodingFinished = true;
            }
            ProducerReceipt receipt = sink.complete();
            assertArrayEquals(expected, oracle.toByteArray(), "chunk=" + chunk);
            assertEquals(expected.length, receipt.bytes(), "chunk=" + chunk);
            assertEquals(hex(sha256().digest(expected)), receipt.sha256(), "chunk=" + chunk);
            assertTrue(cache.peakBytes <= RETAINED_PAYLOAD_LIMIT, "chunk=" + chunk);
            assertTrue(cache.observations > 0);
        }
    }

    @Test
    void noEagerFlushAndFrameOnlyFlushAreCausalUnboundedControls(@TempDir Path dir)
            throws Exception {
        List<byte[]> frames = pngs(257, 97, 1);
        Run control = candidate(frames, dir.resolve("bounded.gif"), false, Mutation.NONE);
        assertBoundAndSequential(control);
        for (Mutation mutation : List.of(Mutation.NO_EAGER_FLUSH, Mutation.FRAME_ONLY_FLUSH)) {
            Path out = dir.resolve(mutation + ".gif");
            Run mutant = candidate(frames, out, false, mutation);
            assertReceiptMatchesFile(mutant.receipt(), out);
            assertTrue(mutant.cache().peakBytes > RETAINED_PAYLOAD_LIMIT,
                mutation + " must retain more than two REAL cache blocks within one large frame");
            assertThrows(AssertionError.class, () -> assertBoundAndSequential(mutant),
                "the same bound oracle that passes the control must kill " + mutation);
        }
    }

    @Test
    void droppedDoubleCountedReorderedAndOmittedTrailerMutantsFailTheSameOracle(@TempDir Path dir)
            throws Exception {
        List<byte[]> frames = pngs(31, 19, 2);
        Path controlFile = dir.resolve("honest.gif");
        Run control = candidate(frames, controlFile, false, Mutation.NONE);
        assertReceiptMatchesFile(control.receipt(), controlFile);
        assertEquals(0x3b, Files.readAllBytes(controlFile)[(int) Files.size(controlFile) - 1] & 0xff);
        for (Mutation mutation : List.of(Mutation.DROP_BYTE, Mutation.DOUBLE_COUNT,
                Mutation.REORDER_BYTES, Mutation.OMIT_TRAILER)) {
            Path out = dir.resolve(mutation + ".gif");
            Run mutant = candidate(frames, out, false, mutation);
            assertThrows(AssertionError.class, () -> assertReceiptMatchesFile(mutant.receipt(), out),
                "a semantic byte/count/hash disagreement must kill " + mutation);
        }
    }

    @Test
    void aPermissiveSeekShowsWhyAttemptedWriteHashIsNotFinalByteIdentity() throws Exception {
        ByteArrayOutputStream finalBytes = new ByteArrayOutputStream();
        MessageDigest attempted = sha256();
        try (MemoryCacheImageOutputStream permissive = new MemoryCacheImageOutputStream(finalBytes)) {
            permissive.write('A');
            attempted.update((byte) 'A');
            permissive.seek(0);
            permissive.write('B');
            attempted.update((byte) 'B');
        }
        assertArrayEquals(new byte[] {'B'}, finalBytes.toByteArray());
        assertNotEquals(hex(sha256().digest(finalBytes.toByteArray())), hex(attempted.digest()),
            "hash(A || B) is not hash(B): a seek-capable attempted-write tee lies");

        DigestSink sink = new DigestSink(new ByteArrayOutputStream(), sha256(), Mutation.NONE);
        GuardedStream guarded = new GuardedStream(sink, FlushPolicy.EACH_CHUNK);
        guarded.write('A');
        assertThrows(IOException.class, () -> guarded.seek(0));
        assertThrows(IOException.class, guarded::close);
        sink.close();
        assertThrows(IllegalStateException.class, sink::complete);
        assertEquals(1, guarded.movedSeekCalls);
    }

    @Test
    void readsForwardSeeksAndBitOperationsRefuseWithoutACompleteReceipt() throws Exception {
        for (StreamOperation operation : List.<StreamOperation>of(
                stream -> stream.read(),
                stream -> stream.read(new byte[4], 1, 2),
                stream -> stream.seek(stream.getStreamPosition() + 1),
                stream -> stream.writeBit(1),
                stream -> stream.writeBits(3, 2),
                stream -> { stream.setBitOffset(1); stream.write(1); },
                stream -> stream.readBit(),
                stream -> stream.readBits(2))) {
            DigestSink sink = new DigestSink(new ByteArrayOutputStream(), sha256(), Mutation.NONE);
            GuardedStream stream = new GuardedStream(sink, FlushPolicy.EACH_CHUNK);
            stream.write(7);
            assertThrows(IOException.class, () -> operation.apply(stream));
            assertThrows(IOException.class, stream::close);
            sink.close();
            assertThrows(IllegalStateException.class, sink::complete);
        }
    }

    @Test
    void partialWriteAndFlushFailuresNeverRetryDuringCloseOrPublishAReceipt() throws Exception {
        for (String fault : List.of("partial-write", "flush")) {
            FaultOutput file = new FaultOutput(fault);
            DigestSink sink = new DigestSink(file, sha256(), Mutation.NONE);
            GuardedStream stream = new GuardedStream(sink, FlushPolicy.EACH_CHUNK);
            IOException first = assertThrows(IOException.class, () -> stream.write(new byte[40]));
            assertTrue(first.getMessage().contains("injected"));
            if (fault.equals("partial-write")) {
                assertEquals(3, file.written, "the failing delegate really accepted a prefix");
            }
            int writesAtFailure = file.writes;
            int flushesAtFailure = file.flushes;
            assertThrows(IOException.class, stream::close);
            stream.close(); // a repeated close is inert, never an output retry
            assertEquals(writesAtFailure, file.writes);
            assertEquals(flushesAtFailure, file.flushes);
            sink.close();
            assertTrue(file.closed);
            assertThrows(IllegalStateException.class, sink::complete);
        }
    }

    @Test
    void closeFailureAndDigestFailureCannotProduceASuccessfulCompleteValue() throws Exception {
        FaultOutput closeFault = new FaultOutput("close");
        DigestSink closing = new DigestSink(closeFault, sha256(), Mutation.NONE);
        try (GuardedStream stream = new GuardedStream(closing, FlushPolicy.EACH_CHUNK)) {
            stream.write(new byte[] {1, 2, 3});
            closing.encodingFinished = true;
        }
        assertThrows(IOException.class, closing::close);
        assertThrows(IllegalStateException.class, closing::complete);

        FailingDigest digest = new FailingDigest();
        DigestSink failing = new DigestSink(new ByteArrayOutputStream(), digest, Mutation.NONE);
        try (failing; GuardedStream stream = new GuardedStream(failing, FlushPolicy.EACH_CHUNK)) {
            stream.write(new byte[] {1, 2, 3});
            failing.encodingFinished = true;
        }
        assertThrows(ProviderException.class, failing::complete);
        assertThrows(IllegalStateException.class, failing::complete);
        assertEquals(1, digest.finishes, "a failed digest is never retried as if untouched");
    }

    @Test
    void failedStreamCannotCommitOverAnExistingArtifactAndTheTemporaryIsRemoved(@TempDir Path dir)
            throws Exception {
        Path target = dir.resolve("known-good.gif");
        byte[] original = {7, 6, 5, 4};
        Files.write(target, original);
        Path[] temporary = new Path[1];
        MessageDigest digest = sha256();
        IOException failure = assertThrows(IOException.class, () -> ArtifactWriter.write(target, path -> {
            temporary[0] = path;
            try (DigestSink sink = new DigestSink(
                        new FaultOutput("partial-write", Files.newOutputStream(path)),
                        digest, Mutation.NONE);
                    GuardedStream stream = new GuardedStream(sink, FlushPolicy.EACH_CHUNK)) {
                stream.write(new byte[40]);
                sink.encodingFinished = true;
            }
        }));
        assertTrue(failure.getMessage().contains("injected partial write"));
        assertArrayEquals(original, Files.readAllBytes(target));
        assertTrue(temporary[0] != null, "the real ArtifactWriter precommit path was entered");
        assertFalse(Files.exists(temporary[0]), "the failed temporary cannot masquerade as an artifact");
    }

    @Test
    void postCompletionReplacementCannotChangeTheProducerSnapshot(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("snapshot.gif");
        Run run = candidate(pngs(31, 19, 1), file, false, Mutation.NONE);
        ProducerReceipt snapshot = run.receipt();
        assertReceiptMatchesFile(snapshot, file);
        Files.write(file, new byte[(int) snapshot.bytes()]);
        assertEquals(snapshot, run.receipt());
        assertNotEquals(hex(sha256().digest(Files.readAllBytes(file))), snapshot.sha256(),
            "the receipt describes producer-time bytes, not later filesystem contents");
    }

    @Test
    void providerRuntimeAndActualCacheObservationAreRecorded(@TempDir Path dir) throws Exception {
        ImageWriter writer = selectedWriter();
        try {
            System.out.println("GIF_STREAM_RUNTIME " + Runtime.version()
                + " vendor=" + System.getProperty("java.vendor")
                + " writer=" + writer.getClass().getName()
                + " spi=" + writer.getOriginatingProvider().getClass().getName()
                + " writerClassSha256=" + classSha256(writer.getClass())
                + " cacheStreamClassSha256=" + classSha256(MemoryCacheImageOutputStream.class)
                + " digestProvider=" + sha256().getProvider().getName());
        } finally {
            writer.dispose();
        }
        Run run = candidate(pngs(17, 13, 1), dir.resolve("observed.gif"), false, Mutation.NONE);
        assertBoundAndSequential(run);
        assertTrue(run.cache().peakBytes >= BLOCK, "a zero observer must not certify the bound");
        System.out.println("GIF_STREAM_MEMORY physicalRetainedEncodedCachePeak="
            + run.cache().peakBytes + " observations=" + run.cache().observations
            + " claim=retained-byte-array-payload-only not=heap-or-rss");
    }

    private static Run assertSequenceParity(Path dir, int width, int height, int count)
            throws Exception {
        List<byte[]> frames = pngs(width, height, count);
        String key = width + "x" + height + "-" + count;
        Path legacy = dir.resolve(key + "-legacy.gif");
        Path output = dir.resolve(key + "-candidate.gif");
        GifWriter.write(frames, 25, 75, legacy);
        Run run = assertDoesNotThrow(() -> candidate(frames, output, false, selectedMutation()),
            "candidate must complete; unsupported seek is a semantic refusal, not setup success");
        assertArrayEquals(Files.readAllBytes(legacy), Files.readAllBytes(output), key);
        assertReceiptMatchesFile(run.receipt(), output);
        assertBoundAndSequential(run);
        byte[] bytes = Files.readAllBytes(output);
        assertEquals(0x3b, bytes[bytes.length - 1] & 0xff, "complete GIF trailer");
        System.out.println("GIF_STREAM_FIXTURE " + key + " bytes=" + run.receipt().bytes()
            + " physicalRetainedEncodedCachePeak=" + run.cache().peakBytes);
        return run;
    }

    private static Run candidate(List<byte[]> pngs, Path out, boolean standalone, Mutation mutation)
            throws Exception {
        FlushPolicy policy = switch (mutation) {
            case NO_EAGER_FLUSH -> FlushPolicy.AT_CLOSE;
            case FRAME_ONLY_FLUSH -> FlushPolicy.EACH_FRAME;
            default -> FlushPolicy.EACH_CHUNK;
        };
        ImageWriter writer = selectedWriter();
        try {
            MessageDigest digest = sha256();
            DigestSink sink = new DigestSink(Files.newOutputStream(out), digest, mutation);
            GuardedStream stream;
            try (sink) {
                stream = new GuardedStream(sink, policy);
                try (stream) {
                    encode(writer, decode(pngs), stream, standalone, () -> {
                        if (mutation == Mutation.SEEK_BACKWARDS) { stream.seek(0); }
                        stream.frameFinished();
                    });
                    sink.encodingFinished = true;
                }
            }
            return new Run(sink.complete(), stream.cache, stream.readCalls,
                stream.movedSeekCalls, stream.bitCalls);
        } finally {
            writer.dispose();
        }
    }

    private static void encode(ImageWriter writer, List<BufferedImage> frames,
            ImageOutputStream output, boolean standalone, IOAction afterFrame) throws Exception {
        IndexColorModel palette = (IndexColorModel) invoke("buildGlobalPalette",
            new Class<?>[] {List.class}, frames);
        writer.setOutput(output);
        if (!standalone) { writer.prepareWriteSequence(null); }
        for (int i = 0; i < frames.size(); i++) {
            BufferedImage indexed = (BufferedImage) invoke("ditherToPalette",
                new Class<?>[] {BufferedImage.class, IndexColorModel.class}, frames.get(i), palette);
            IIOMetadata metadata = writer.getDefaultImageMetadata(
                ImageTypeSpecifier.createFromRenderedImage(indexed), writer.getDefaultWriteParam());
            invoke("applyFrameMetadata", new Class<?>[] {IIOMetadata.class, int.class},
                metadata, i == 0 ? 75 : 25);
            IIOImage image = new IIOImage(indexed, null, metadata);
            if (standalone) { writer.write(null, image, null); }
            else { writer.writeToSequence(image, null); }
            afterFrame.run();
        }
        if (!standalone) { writer.endWriteSequence(); }
    }

    private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = GifWriter.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception cause) { throw cause; }
            if (wrapped.getCause() instanceof Error cause) { throw cause; }
            throw wrapped;
        }
    }

    private static ImageWriter selectedWriter() {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        assertEquals("com.sun.imageio.plugins.gif.GIFImageWriter", writer.getClass().getName(),
            "a different provider needs re-audit; do not transfer the old source proof");
        return writer;
    }

    private static List<byte[]> pngs(int width, int height, int count) throws IOException {
        Random pixels = new Random(185L + width * 31L + height);
        List<byte[]> result = new ArrayList<>();
        for (int frame = 0; frame < count; frame++) {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) { image.setRGB(x, y, pixels.nextInt(1 << 24)); }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(image, "png", bytes));
            result.add(bytes.toByteArray());
        }
        return result;
    }

    private static List<BufferedImage> decode(List<byte[]> pngs) throws IOException {
        List<BufferedImage> result = new ArrayList<>();
        for (byte[] png : pngs) {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image == null) { throw new IOException("fixture PNG was not decoded"); }
            result.add(image);
        }
        return result;
    }

    private static void assertReceiptMatchesFile(ProducerReceipt receipt, Path file) throws Exception {
        // Independent AFTER-completion oracle. The producer helper never calls this.
        byte[] actual = Files.readAllBytes(file);
        assertEquals(actual.length, receipt.bytes(), "producer byte count");
        assertEquals(hex(sha256().digest(actual)), receipt.sha256(), "producer exact-byte identity");
    }

    private static void assertBoundAndSequential(Run run) {
        assertTrue(run.cache().observations > 0, "the physical observer actually ran");
        assertTrue(run.cache().peakBytes <= RETAINED_PAYLOAD_LIMIT,
            "retained encoded-cache payload=" + run.cache().peakBytes);
        assertEquals(0, run.readCalls(), "producer must never read");
        assertEquals(0, run.movedSeekCalls(), "producer must never move its cursor with seek");
        assertEquals(0, run.bitCalls(), "the accepted byte-aligned encoder must not use bit I/O");
    }

    private static Mutation selectedMutation() {
        return Mutation.valueOf(System.getProperty(MUTANT_PROPERTY, "NONE"));
    }

    private static MessageDigest sha256() throws Exception {
        return MessageDigest.getInstance("SHA-256");
    }

    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }

    private static String classSha256(Class<?> type) throws Exception {
        try (InputStream bytes = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            assertTrue(bytes != null, "loaded provider class bytes must be observable");
            MessageDigest digest = sha256();
            byte[] chunk = new byte[BLOCK];
            int n;
            while ((n = bytes.read(chunk)) >= 0) { digest.update(chunk, 0, n); }
            return hex(digest.digest());
        }
    }

    private record ProducerReceipt(long bytes, String sha256) { }
    private record Run(ProducerReceipt receipt, CacheProbe cache, int readCalls,
            int movedSeekCalls, int bitCalls) { }
    @FunctionalInterface private interface IOAction { void run() throws IOException; }
    @FunctionalInterface private interface StreamOperation { void apply(GuardedStream stream) throws IOException; }

    /** No artifact reader and no artifact-sized buffer. Receipt is unavailable until both closes. */
    private static final class DigestSink extends OutputStream {
        private final OutputStream out;
        private final MessageDigest digest;
        private final Mutation mutation;
        private long bytes;
        private boolean failed;
        private boolean closed;
        private boolean closeSucceeded;
        private boolean completionAttempted;
        private boolean encodingFinished;
        private boolean dropped;
        private boolean reordered;
        private int pending = -1;

        DigestSink(OutputStream out, MessageDigest digest, Mutation mutation) {
            this.out = out;
            this.digest = digest;
            this.mutation = mutation;
        }

        @Override public void write(int value) throws IOException {
            byte[] one = {(byte) value};
            write(one, 0, 1);
        }

        @Override public void write(byte[] values, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, values.length);
            if (closed || failed) { throw new IOException("sink is closed or failed"); }
            try {
                if (mutation == Mutation.DROP_BYTE || mutation == Mutation.REORDER_BYTES
                        || mutation == Mutation.OMIT_TRAILER) {
                    for (int i = offset; i < offset + length; i++) { mutantWrite(values[i] & 0xff); }
                } else {
                    out.write(values, offset, length);
                }
                digest.update(values, offset, length);
                bytes = Math.addExact(bytes, length);
                if (mutation == Mutation.DOUBLE_COUNT) {
                    digest.update(values, offset, length);
                    bytes = Math.addExact(bytes, length);
                }
            } catch (IOException | RuntimeException failure) {
                failed = true;
                throw failure;
            }
        }

        private void mutantWrite(int value) throws IOException {
            if (mutation == Mutation.DROP_BYTE && !dropped) { dropped = true; return; }
            if (mutation == Mutation.REORDER_BYTES && !reordered) {
                if (pending < 0) { pending = value; return; }
                out.write(value);
                out.write(pending);
                pending = -1;
                reordered = true;
                return;
            }
            if (mutation == Mutation.OMIT_TRAILER) {
                if (pending >= 0) { out.write(pending); }
                pending = value; // deliberately discard the final encoded byte at close
                return;
            }
            out.write(value);
        }

        @Override public void flush() throws IOException {
            if (failed || closed) { throw new IOException("sink is closed or failed"); }
            try { out.flush(); }
            catch (IOException failure) { failed = true; throw failure; }
        }

        @Override public void close() throws IOException {
            if (closed) { return; }
            closed = true;
            try { out.close(); closeSucceeded = true; }
            catch (IOException failure) { failed = true; throw failure; }
        }

        ProducerReceipt complete() {
            if (failed || !closed || !closeSucceeded || !encodingFinished || completionAttempted) {
                throw new IllegalStateException("no successful complete producer snapshot");
            }
            completionAttempted = true;
            return new ProducerReceipt(bytes, hex(digest.digest()));
        }
    }

    /** Observe the real retained block arrays, before AND after the disposal boundary. */
    private static final class CacheProbe {
        private final Field cacheField;
        private final Field blocksField;
        private long peakBytes;
        private long observations;

        CacheProbe() throws IOException {
            try {
                cacheField = MemoryCacheImageOutputStream.class.getDeclaredField("cache");
                if (!cacheField.trySetAccessible()) { throw new IllegalAccessException("cache"); }
                blocksField = cacheField.getType().getDeclaredField("cache");
                if (!blocksField.trySetAccessible()) { throw new IllegalAccessException("blocks"); }
            } catch (ReflectiveOperationException inaccessible) {
                throw new IOException("MEMORY_OBSERVATION=UNKNOWN; actual retained blocks inaccessible. "
                    + "Use TEST JVM --add-opens=java.desktop/javax.imageio.stream=ALL-UNNAMED "
                    + "or re-audit the implementation; logical pending bytes are insufficient.", inaccessible);
            }
        }

        void observe(MemoryCacheImageOutputStream stream) throws IOException {
            try {
                Object cache = cacheField.get(stream);
                long retained = 0;
                if (cache != null) {
                    Object blocks = blocksField.get(cache);
                    if (!(blocks instanceof List<?> list)) {
                        throw new IOException("MEMORY_OBSERVATION=UNKNOWN; cache is not a block list");
                    }
                    for (Object block : list) {
                        if (!(block instanceof byte[] payload) || payload.length != BLOCK) {
                            throw new IOException("MEMORY_OBSERVATION=UNKNOWN; cache block layout changed");
                        }
                        retained += payload.length;
                    }
                }
                peakBytes = Math.max(peakBytes, retained);
                observations++;
            } catch (IllegalAccessException inaccessible) {
                throw new IOException("MEMORY_OBSERVATION=UNKNOWN", inaccessible);
            }
        }
    }

    /** TEST candidate only: preserve byte encoding, eject cache blocks eagerly, forbid backpatching. */
    private static final class GuardedStream extends MemoryCacheImageOutputStream {
        private final FlushPolicy policy;
        private final CacheProbe cache;
        private boolean failed;
        private boolean closeAttempted;
        private int readCalls;
        private int movedSeekCalls;
        private int bitCalls;

        GuardedStream(DigestSink output, FlushPolicy policy) throws IOException {
            super(output);
            this.policy = policy;
            this.cache = new CacheProbe();
            cache.observe(this);
        }

        private void writable() throws IOException {
            if (failed || closeAttempted) { throw new IOException("stream is closed or failed"); }
            if (getBitOffset() != 0) { throw refuse("non-byte-aligned cursor"); }
        }

        @Override public void write(int value) throws IOException {
            writable();
            try {
                super.write(value);
                cache.observe(this);
                if (policy == FlushPolicy.EACH_CHUNK) { flushBefore(getStreamPosition()); }
            } catch (IOException | RuntimeException failure) { failed = true; throw failure; }
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            writable();
            try {
                for (int at = offset; at < offset + length; ) {
                    int count = Math.min(BLOCK, offset + length - at);
                    super.write(bytes, at, count);
                    cache.observe(this);
                    if (policy == FlushPolicy.EACH_CHUNK) { flushBefore(getStreamPosition()); }
                    at += count;
                }
            } catch (IOException | RuntimeException failure) { failed = true; throw failure; }
        }

        void frameFinished() throws IOException {
            if (policy == FlushPolicy.EACH_FRAME) { flushBefore(getStreamPosition()); }
        }

        @Override public void flushBefore(long position) throws IOException {
            if (failed) { throw new IOException("failed flush is never retried"); }
            try {
                cache.observe(this);
                super.flushBefore(position);
                cache.observe(this);
            } catch (IOException | RuntimeException failure) { failed = true; throw failure; }
        }

        @Override public void seek(long position) throws IOException {
            if (position != getStreamPosition()) {
                movedSeekCalls++;
                throw refuse("cursor-moving seek is not sequential output");
            }
            super.seek(position); // inherited close may seek to its already-current length
        }

        @Override public int read() throws IOException { readCalls++; throw refuse("producer read"); }
        @Override public int read(byte[] b, int o, int n) throws IOException {
            readCalls++; throw refuse("producer read");
        }
        @Override public int readBit() throws IOException { bitCalls++; throw refuse("bit read"); }
        @Override public long readBits(int count) throws IOException {
            bitCalls++; throw refuse("bit read");
        }
        @Override public void writeBit(int bit) throws IOException { bitCalls++; throw refuse("bit write"); }
        @Override public void writeBits(long bits, int count) throws IOException {
            bitCalls++; throw refuse("bit write");
        }

        private IOException refuse(String reason) {
            failed = true;
            return new IOException("sequential GIF stream refused: " + reason);
        }

        @Override public void close() throws IOException {
            if (closeAttempted) { return; }
            closeAttempted = true;
            if (failed) { throw new IOException("failed stream close refuses to retry output"); }
            try {
                super.close();
                cache.observe(this);
            } catch (IOException | RuntimeException failure) { failed = true; throw failure; }
        }
    }

    private static final class FaultOutput extends OutputStream {
        private final String fault;
        private final OutputStream accepted;
        private int writes;
        private int flushes;
        private int written;
        private boolean closed;

        FaultOutput(String fault) { this(fault, new ByteArrayOutputStream()); }
        FaultOutput(String fault, OutputStream accepted) {
            this.fault = fault;
            this.accepted = accepted;
        }
        @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}); }
        @Override public void write(byte[] bytes, int offset, int count) throws IOException {
            Objects.checkFromIndexSize(offset, count, bytes.length);
            writes++;
            if (fault.equals("partial-write")) {
                // OutputStream permits writing a prefix and then throwing. The sink may not retry.
                int prefix = Math.min(3, count);
                accepted.write(bytes, offset, prefix);
                written += prefix;
                throw new IOException("injected partial write after prefix");
            }
            accepted.write(bytes, offset, count);
            written += count;
        }
        @Override public void flush() throws IOException {
            flushes++;
            if (fault.equals("flush")) { throw new IOException("injected flush failure"); }
        }
        @Override public void close() throws IOException {
            closed = true;
            accepted.close();
            if (fault.equals("close")) { throw new IOException("injected close failure"); }
        }
    }

    private static final class FailingDigest extends MessageDigest {
        private int finishes;
        FailingDigest() { super("injected-failing-SHA-256"); }
        @Override protected void engineUpdate(byte value) { }
        @Override protected void engineUpdate(byte[] bytes, int offset, int length) { }
        @Override protected byte[] engineDigest() {
            finishes++;
            throw new ProviderException("injected digest completion failure");
        }
        @Override protected void engineReset() { }
    }
}
