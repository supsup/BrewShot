package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exact byte-boundary tests for stdin HTML and --eval-file ingestion. */
class BoundedUtf8Test {

    @Test
    void stdinHtmlAcceptsExactlySixteenMibAndReadsOnlyOneSentinelByteOver()
            throws Exception {
        CountingInputStream exact = new CountingInputStream(Main.MAX_STDIN_HTML_BYTES);
        String decoded = BoundedUtf8.read(
            exact, Main.MAX_STDIN_HTML_BYTES, "stdin HTML");
        assertEquals(Main.MAX_STDIN_HTML_BYTES, decoded.length());
        assertEquals(Main.MAX_STDIN_HTML_BYTES, exact.bytesRead);

        CountingInputStream over = new CountingInputStream(Main.MAX_STDIN_HTML_BYTES + 10);
        assertThrows(IOException.class, () -> BoundedUtf8.read(
            over, Main.MAX_STDIN_HTML_BYTES, "stdin HTML"));
        assertEquals(Main.MAX_STDIN_HTML_BYTES + 1, over.bytesRead,
            "limit+1 proves overflow without consuming or allocating the rest");
    }

    @Test
    void evalFileAcceptsExactlyOneMibAndRejectsOneByteMore(@TempDir Path directory)
            throws Exception {
        byte[] bytes = new byte[Main.MAX_EVAL_FILE_BYTES + 1];
        Arrays.fill(bytes, (byte) 'e');
        Path exact = directory.resolve("exact.js");
        Path over = directory.resolve("over.js");
        Files.write(exact, Arrays.copyOf(bytes, Main.MAX_EVAL_FILE_BYTES));
        Files.write(over, bytes);

        assertEquals(Main.MAX_EVAL_FILE_BYTES,
            BoundedUtf8.read(exact, Main.MAX_EVAL_FILE_BYTES, "--eval-file").length());
        IOException failure = assertThrows(IOException.class,
            () -> BoundedUtf8.read(over, Main.MAX_EVAL_FILE_BYTES, "--eval-file"));
        assertEquals("--eval-file exceeds the " + Main.MAX_EVAL_FILE_BYTES + "-byte limit",
            failure.getMessage());
    }

    @Test
    void malformedUtf8RetainsThePreviousReplacementDecodeBehavior() throws Exception {
        byte[] malformed = {(byte) 0xC3, 0x28};
        assertEquals(new String(malformed, StandardCharsets.UTF_8),
            BoundedUtf8.read(new java.io.ByteArrayInputStream(malformed), 8, "source"));
    }

    private static final class CountingInputStream extends InputStream {
        private int remaining;
        private int bytesRead;

        CountingInputStream(int length) {
            this.remaining = length;
        }

        @Override
        public int read() {
            if (remaining == 0) {
                return -1;
            }
            remaining--;
            bytesRead++;
            return 'h';
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            if (remaining == 0) {
                return -1;
            }
            int count = Math.min(remaining, length);
            Arrays.fill(target, offset, offset + count, (byte) 'h');
            remaining -= count;
            bytesRead += count;
            return count;
        }
    }
}
