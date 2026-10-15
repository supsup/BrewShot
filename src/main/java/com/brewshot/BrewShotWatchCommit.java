package com.brewshot;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Durable first-complete-capture selection for one immutable manifest claim.
 * State is retained: an unlocked late attempt must see the same decision even
 * after the winning attempt has archived its input and removed its temporary.
 */
final class BrewShotWatchCommit {
    private final Path directory;
    private final String sourceHash;

    BrewShotWatchCommit(Path output, String jobId, String sourceHash) throws IOException {
        if (!jobId.matches("[a-f0-9]{32}") || !sourceHash.matches("[a-f0-9]{64}")) {
            throw new IOException("invalid capture identity");
        }
        Path state = output.resolve(".brewshot-state");
        ensureDirectory(state);
        this.directory = state.resolve(jobId);
        ensureDirectory(directory);
        this.sourceHash = sourceHash;
    }

    Path recover() throws IOException {
        Path decision = directory.resolve(sourceHash + ".decision");
        if (!regularOrMissing(decision)) { return null; }
        try (var input = Files.newInputStream(decision)) {
            byte[] prefix = input.readNBytes(5);
            if (java.util.Arrays.equals(prefix, "FAIL ".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                byte[] code = input.readNBytes(81);
                String text = new String(code, java.nio.charset.StandardCharsets.US_ASCII);
                if (!text.matches("[A-Za-z0-9._-]{1,80}")) {
                    throw new IOException("invalid failure decision");
                }
                throw new Failed(text);
            }
        }
        return decision;
    }

    Path record(Path complete, String suffix) throws IOException {
        if (!suffix.equals(".png") && !suffix.equals(".gif")) {
            throw new IOException("invalid capture format");
        }
        Path target = directory.resolve(sourceHash + ".decision");
        try {
            // A complete, owner-only image is committed BEFORE public output.
            // Concurrent unlocked attempts choose exactly the same first image.
            Files.createLink(target, complete);
        } catch (FileAlreadyExistsException winner) {
            if (!regularOrMissing(target)) { throw new IOException("capture disappeared"); }
        }
        return recover();
    }

    /** First terminal result wins, including a failing competing attempt. */
    Path fail(String code) throws IOException {
        if (!code.matches("[A-Za-z0-9._-]{1,80}")) { code = "job-failed"; }
        Path candidate = Files.createTempFile(directory, ".failure-", ".tmp");
        try {
            Files.writeString(candidate, "FAIL " + code);
            return record(candidate, ".png");
        } finally { Files.deleteIfExists(candidate); }
    }

    static String suffix(Path committed) throws IOException {
        try (var input = Files.newInputStream(committed)) {
            byte[] header = input.readNBytes(8);
            if (header.length >= 6 && header[0] == 'G' && header[1] == 'I' && header[2] == 'F') {
                return ".gif";
            }
            if (header.length == 8 && header[0] == (byte) 0x89 && header[1] == 'P'
                    && header[2] == 'N' && header[3] == 'G') { return ".png"; }
            throw new IOException("invalid committed image");
        }
    }

    static void publish(Path committed, Path target) throws IOException {
        try {
            Files.createLink(target, committed);
        } catch (FileAlreadyExistsException existing) {
            // Same claim retries compare against the durable image, never a new
            // recording. Different claims still cannot replace an old output.
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.mismatch(committed, target) != -1) {
                throw new Collision();
            }
        }
    }

    private static boolean regularOrMissing(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { return false; }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("invalid capture state");
        }
        return true;
    }

    private static void ensureDirectory(Path path) throws IOException {
        try { Files.createDirectory(path); }
        catch (FileAlreadyExistsException exists) {
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("invalid capture state directory");
            }
        }
    }

    static final class Collision extends IOException { }
    static final class Failed extends IOException {
        Failed(String code) { super(code); }
    }
}
