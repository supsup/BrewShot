package com.brewshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Shared byte-bounded UTF-8 ingestion for CLI-controlled source text. */
final class BoundedUtf8 {

    private BoundedUtf8() { }

    static String read(Path path, int maxBytes, String label) throws IOException {
        Objects.requireNonNull(path, "path");
        try (InputStream input = Files.newInputStream(path)) {
            return read(input, maxBytes, label);
        }
    }

    /** Read bounded configuration text and reject malformed or unmappable UTF-8. */
    static String readStrict(Path path, int maxBytes, String label) throws IOException {
        Objects.requireNonNull(path, "path");
        try (InputStream input = Files.newInputStream(path)) {
            return readStrict(input, maxBytes, label);
        }
    }

    /**
     * Read at most {@code maxBytes + 1}. The sentinel byte distinguishes an
     * exact-limit input from an over-limit one without an unbounded allocation.
     * The byte limit is checked before decoding; decoding deliberately retains
     * {@link String}'s replacement behavior for malformed UTF-8.
     */
    static String read(InputStream input, int maxBytes, String label) throws IOException {
        byte[] bytes = readBytes(input, maxBytes, label);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static String readStrict(InputStream input, int maxBytes, String label) throws IOException {
        byte[] bytes = readBytes(input, maxBytes, label);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException malformed) {
            throw new IOException(label + " is not valid UTF-8", malformed);
        }
    }

    private static byte[] readBytes(InputStream input, int maxBytes, String label)
            throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(label, "label");
        Validation.positiveInt("maxBytes", maxBytes);
        if (maxBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maxBytes is too large for a limit sentinel");
        }

        int sentinelLimit = maxBytes + 1;
        byte[] bytes = new byte[Math.min(8192, sentinelLimit)];
        int count = 0;
        while (count < sentinelLimit) {
            if (count == bytes.length) {
                int grown = (int) Math.min((long) sentinelLimit,
                    Math.max((long) count + 1, (long) count * 2));
                bytes = java.util.Arrays.copyOf(bytes, grown);
            }
            int read = input.read(bytes, count, bytes.length - count);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                int one = input.read();
                if (one < 0) {
                    break;
                }
                bytes[count++] = (byte) one;
            } else {
                count += read;
            }
        }
        if (count > maxBytes) {
            throw new IOException(label + " exceeds the " + maxBytes + "-byte limit");
        }
        return java.util.Arrays.copyOf(bytes, count);
    }
}
