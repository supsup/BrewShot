package com.brewshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Container-only folder worker for self-contained local HTML files. Every
 * capture calls the shipped {@link Main} entry point with fixed defaults, so
 * this class owns mounted-file orchestration rather than a second browser or
 * argument parser. URL and advanced-option jobs remain available through the
 * ordinary one-shot CLI.
 *
 * <p>Claims are atomic renames to the original filename below a UUID-named
 * directory in {@code input/processing}. Keeping the UUID and source name in
 * separate path components means a host-valid source name is never lengthened
 * past the mounted filesystem's component limit. The unique directory means
 * two workers can race for one source without either overwriting the other.
 * Completed output and source archives are published with hard links from
 * fully written files, which is an atomic create-if-absent operation on the
 * mounted filesystem. There is no overwrite fallback.
 */
public final class BrewShotFolderWorker {

    private static final Path DEFAULT_INPUT = Path.of("/brewshot/input");
    private static final Path DEFAULT_OUTPUT = Path.of("/brewshot/output");
    private static final long DEFAULT_POLL_MILLIS = 500;
    private static final long MIN_POLL_MILLIS = 10;
    private static final long MAX_POLL_MILLIS = 60_000;
    private static final int CLAIM_ID_LENGTH = 32;
    private static final int MAX_DERIVED_NAME_BYTES = 255;
    private static final int DIAGNOSTIC_LIMIT = 512;
    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    private final Path input;
    private final Path output;
    private final Path processing;
    private final Path finished;
    private final Path failed;
    private final Path unreadablePending;
    private final long pollMillis;

    private BrewShotFolderWorker(Path input, Path output, long pollMillis) {
        this.input = input.toAbsolutePath().normalize();
        this.output = output.toAbsolutePath().normalize();
        this.processing = this.input.resolve("processing");
        this.finished = this.input.resolve("finished");
        this.failed = this.input.resolve("failed");
        this.unreadablePending = this.failed.resolve("pending");
        this.pollMillis = pollMillis;
    }

    public static void main(String[] args) {
        if (args.length != 0) {
            System.err.println("brewshot-watch: no arguments accepted");
            System.exit(2);
        }
        try {
            Path input = envPath("BREWSHOT_INPUT_DIR", DEFAULT_INPUT);
            Path output = envPath("BREWSHOT_OUTPUT_DIR", DEFAULT_OUTPUT);
            long pollMillis = envPollMillis();
            new BrewShotFolderWorker(input, output, pollMillis).run();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            // No exception message or path: mounted paths and host-specific
            // details may contain secrets. The class and local method name the
            // failure shape without exposing mounted content.
            System.err.println("brewshot-watch: stopped after "
                + failure.getClass().getSimpleName() + " in "
                + localFailureSite(failure));
            System.exit(2);
        }
    }

    private static String localFailureSite(Throwable failure) {
        for (StackTraceElement frame : failure.getStackTrace()) {
            if (frame.getClassName().equals(BrewShotFolderWorker.class.getName())) {
                return frame.getMethodName();
            }
        }
        return "worker";
    }

    private void run() throws IOException, InterruptedException {
        ensureDirectory(input);
        ensureDirectory(output);
        ensureDirectory(processing);
        ensureDirectory(finished);
        ensureDirectory(failed);
        ensureDirectory(finished.resolve("collisions"));
        ensureDirectory(failed.resolve("collisions"));
        ensureDirectory(unreadablePending);

        System.err.println("brewshot-watch: ready");
        while (!Thread.currentThread().isInterrupted()) {
            boolean worked = processRecoveredUnreadableClaims();
            worked |= processRecoveredClaims();
            worked |= claimNewInputs();
            if (!worked) {
                Thread.sleep(pollMillis);
            }
        }
    }

    /**
     * Finish unreadable jobs whose diagnostic publication was interrupted.
     * The source remains below {@code failed/pending} until the diagnostic is
     * durable, so an output-mount fault cannot turn a restart into silent data
     * loss. The job id is also the stable diagnostic fallback id: concurrent
     * reconcilers therefore publish the same bytes to the same no-replace path.
     */
    private boolean processRecoveredUnreadableClaims() throws IOException {
        List<Claim> claims = new ArrayList<>();
        for (Path candidate : directChildren(unreadablePending)) {
            Claim claim = parseClaim(candidate);
            if (claim != null) {
                claims.add(claim);
            }
        }
        claims.sort(Comparator.comparing(Claim::jobId));
        boolean processedAny = false;
        for (Claim claim : claims) {
            String code = "io-AccessDeniedException";
            if (completeUnreadable(claim, code)) {
                System.err.println("brewshot-watch: job " + claim.jobId()
                    + " failed (" + code + ")");
            }
            processedAny = true;
        }
        return processedAny;
    }

    private boolean processRecoveredClaims() throws IOException {
        List<Claim> claims = new ArrayList<>();
        for (Path candidate : directChildren(processing)) {
            Claim claim = parseClaim(candidate);
            if (claim != null) {
                claims.add(claim);
            }
        }
        claims.sort(Comparator.comparing(Claim::jobId));
        boolean processedAny = false;
        for (Claim claim : claims) {
            processedAny |= tryProcess(claim);
        }
        return processedAny;
    }

    private boolean claimNewInputs() throws IOException {
        List<Path> candidates = new ArrayList<>();
        for (Path candidate : directChildren(input)) {
            if (eligible(candidate)) {
                candidates.add(candidate);
            }
        }
        candidates.sort(Comparator.comparing(path -> path.getFileName().toString()));

        boolean claimedAny = false;
        for (Path source : candidates) {
            String originalName = source.getFileName().toString();
            String jobId = UUID.randomUUID().toString().replace("-", "");
            Path claimDirectory = processing.resolve(jobId);
            try {
                Files.createDirectory(claimDirectory);
            } catch (FileAlreadyExistsException jobIdCollision) {
                // A valid recovery claim already owns this UUID. Leave the
                // source for the next bounded polling pass.
                continue;
            }
            Path claimed = claimDirectory.resolve(originalName);
            try {
                Files.move(source, claimed, StandardCopyOption.ATOMIC_MOVE);
                claimedAny = true;
                Claim claim = claimAt(jobId, originalName, claimed);
                if (claim != null) {
                    tryProcess(claim);
                }
            } catch (NoSuchFileException | FileAlreadyExistsException raced) {
                // Another worker won the source rename, or an operator raced a
                // same-name state entry. No source bytes were overwritten.
                deleteEmptyClaimDirectory(claimDirectory);
            } catch (AtomicMoveNotSupportedException unsupported) {
                deleteEmptyClaimDirectory(claimDirectory);
                throw new IOException("input mount cannot atomically claim work", unsupported);
            } catch (IOException failure) {
                deleteEmptyClaimDirectory(claimDirectory);
                throw failure;
            }
        }
        return claimedAny;
    }

    /**
     * Prefer an exclusive lock on the claimed source. Some bind-mount drivers
     * do not coordinate advisory locks between containers, and a producer may
     * create a readable but non-writable file, so the processing and archive
     * paths are independently idempotent as the correctness boundary.
     */
    private boolean tryProcess(Claim claim) throws IOException {
        String attemptId = UUID.randomUUID().toString().replace("-", "");
        ClaimLease lease;
        try {
            lease = tryAcquire(claim.path());
        } catch (AccessDeniedException unreadableSource) {
            try {
                String code = "io-AccessDeniedException";
                if (failUnreadable(claim, code)) {
                    System.err.println("brewshot-watch: job " + claim.jobId()
                        + " failed (" + code + ")");
                } else {
                    logAlreadyCompleted(claim);
                }
                return true;
            } finally {
                deleteEmptyClaimDirectory(claim.path().getParent());
            }
        }
        if (lease == null) {
            deleteEmptyClaimDirectory(claim.path().getParent());
            return false;
        }
        try (lease) {
            process(claim, lease.channel(), attemptId);
            return true;
        } finally {
            deleteEmptyClaimDirectory(claim.path().getParent());
        }
    }

    private static ClaimLease tryAcquire(Path claimed) throws IOException {
        if (!Files.isRegularFile(claimed, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        FileChannel channel;
        try {
            channel = FileChannel.open(claimed,
                StandardOpenOption.READ, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        } catch (AccessDeniedException readOnlySource) {
            return tryAcquireReadOnly(claimed);
        } catch (NoSuchFileException raced) {
            return null;
        }
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return null;
            }
            // A loser can open the inode while the winner still holds the
            // lock, then acquire that inode lock just after the winner links
            // it into finished/failed and deletes the processing pathname.
            // Revalidate the unique claim path after acquisition so the loser
            // never processes an already-archived inode through a vanished
            // path.
            if (!Files.isRegularFile(claimed, LinkOption.NOFOLLOW_LINKS)) {
                channel.close();
                return null;
            }
            return new ClaimLease(channel, lock);
        } catch (OverlappingFileLockException alreadyHeldHere) {
            channel.close();
            return null;
        } catch (IOException unsupportedLock) {
            channel.close();
            return tryAcquireReadOnly(claimed);
        } catch (RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    private static ClaimLease tryAcquireReadOnly(Path claimed) throws IOException {
        FileChannel channel;
        try {
            channel = FileChannel.open(claimed,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException raced) {
            return null;
        }
        if (!Files.isRegularFile(claimed, LinkOption.NOFOLLOW_LINKS)) {
            channel.close();
            return null;
        }
        return new ClaimLease(channel, null);
    }

    private void process(Claim claim, FileChannel source, String attemptId)
            throws IOException {
        Path renderTemp = output.resolve(".brewshot-watch-" + claim.jobId()
            + "-" + attemptId + ".png");
        Files.deleteIfExists(renderTemp);

        try {
            render(claim, source, renderTemp);
            publishComplete(renderTemp, output.resolve(
                derivedName(claim, ".png")));
        } catch (JobFailure failure) {
            Files.deleteIfExists(renderTemp);
            if (completedByAnotherWorker(claim, source)) {
                logAlreadyCompleted(claim);
                return;
            }
            if (fail(claim, source, attemptId, failure.code())) {
                System.err.println("brewshot-watch: job " + claim.jobId()
                    + " failed (" + failure.code() + ")");
            } else {
                logAlreadyCompleted(claim);
            }
            return;
        } catch (IOException failure) {
            Files.deleteIfExists(renderTemp);
            if (completedByAnotherWorker(claim, source)) {
                logAlreadyCompleted(claim);
                return;
            }
            String code = "io-" + failure.getClass().getSimpleName();
            if (fail(claim, source, attemptId, code)) {
                System.err.println("brewshot-watch: job " + claim.jobId()
                    + " failed (" + code + ")");
            } else {
                logAlreadyCompleted(claim);
            }
            return;
        } catch (RuntimeException failure) {
            Files.deleteIfExists(renderTemp);
            if (completedByAnotherWorker(claim, source)) {
                logAlreadyCompleted(claim);
                return;
            }
            String code = "runtime-" + failure.getClass().getSimpleName();
            if (fail(claim, source, attemptId, code)) {
                System.err.println("brewshot-watch: job " + claim.jobId()
                    + " failed (" + code + ")");
            } else {
                logAlreadyCompleted(claim);
            }
            return;
        }

        if (archive(claim, source, finished)) {
            System.err.println("brewshot-watch: job " + claim.jobId() + " finished");
        } else {
            logAlreadyCompleted(claim);
        }
    }

    private static void logAlreadyCompleted(Claim claim) {
        System.err.println("brewshot-watch: job " + claim.jobId()
            + " already completed by another worker");
    }

    private boolean completedByAnotherWorker(Claim claim, FileChannel source)
            throws IOException {
        return !Files.exists(claim.path(), LinkOption.NOFOLLOW_LINKS)
            && archivedAnywhere(claim, source);
    }

    private void render(Claim claim, FileChannel source, Path renderTemp)
            throws IOException, JobFailure {
        long size = source.size();
        if (size == 0) {
            throw new JobFailure("empty-input");
        }
        if (size > Main.MAX_STDIN_HTML_BYTES) {
            throw new JobFailure("input-too-large");
        }
        int exit;
        try {
            exit = Main.run(new String[] {
                claim.path().toString(), "-o", renderTemp.toString()
            });
        } catch (Exception | LinkageError captureFailure) {
            throw new JobFailure("capture-"
                + captureFailure.getClass().getSimpleName());
        }
        if (exit != 0) {
            throw new JobFailure("capture-exit-" + exit);
        }
        requirePng(renderTemp);
    }

    private static void requirePng(Path rendered) throws IOException, JobFailure {
        if (!Files.isRegularFile(rendered, LinkOption.NOFOLLOW_LINKS)
                || Files.size(rendered) <= PNG_SIGNATURE.length) {
            throw new JobFailure("empty-output");
        }
        byte[] actual = new byte[PNG_SIGNATURE.length];
        try (InputStream in = Files.newInputStream(rendered,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (in.readNBytes(actual, 0, actual.length) != actual.length) {
                throw new JobFailure("truncated-output");
            }
        }
        if (!java.util.Arrays.equals(PNG_SIGNATURE, actual)) {
            throw new JobFailure("invalid-png-output");
        }
    }

    private boolean fail(Claim claim, FileChannel source, String attemptId,
            String rawCode) throws IOException {
        publishDiagnostic(claim, attemptId, rawCode);
        return archive(claim, source, failed);
    }

    /**
     * A source that cannot be opened first moves to a durable pending-failure
     * state. Moving the worker-owned claim directory preserves the unreadable
     * inode without source read permission or the hard-link permission Linux
     * deliberately denies for another user's mode-000 file. Only the winner
     * of that atomic move performs the initial reconciliation; a restart will
     * retry any winner interrupted while the output mount was unavailable.
     */
    private boolean failUnreadable(Claim claim, String rawCode) throws IOException {
        Claim pending = stageUnreadable(claim);
        if (pending == null) {
            return false;
        }
        return completeUnreadable(pending, rawCode);
    }

    private Claim stageUnreadable(Claim claim) throws IOException {
        Path claimDirectory = claim.path().getParent();
        Path pendingDirectory = unreadablePending.resolve(claim.jobId());
        try {
            Files.move(claimDirectory, pendingDirectory,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("input mount cannot atomically stage unreadable work",
                unsupported);
        } catch (IOException racedOrFailed) {
            if (!Files.exists(claimDirectory, LinkOption.NOFOLLOW_LINKS)
                    && unreadableDispositionExists(claim, pendingDirectory)) {
                return null;
            }
            throw racedOrFailed;
        }

        Path pendingSource = pendingDirectory.resolve(claim.originalName());
        if (!sameUnreadableIdentity(claim, pendingSource)) {
            throw new IOException("unreadable pending state lost its source");
        }
        return new Claim(claim.jobId(), claim.originalName(), pendingSource,
            claim.identity());
    }

    private boolean completeUnreadable(Claim claim, String rawCode) throws IOException {
        // Reuse the job id as the fallback id. A retry or a second worker then
        // converges on the same diagnostic instead of minting duplicates.
        publishDiagnostic(claim, claim.jobId(), rawCode);
        return archiveUnreadable(claim);
    }

    private void publishDiagnostic(Claim claim, String attemptId, String rawCode)
            throws IOException {
        String code = boundedCode(rawCode);
        byte[] diagnostic = ("BrewShot watch job failed: " + code + ".\n")
            .getBytes(StandardCharsets.UTF_8);
        String tempId = UUID.randomUUID().toString().replace("-", "");
        Path temp = output.resolve(".brewshot-watch-" + claim.jobId()
            + "-" + tempId + ".error.tmp");
        Files.deleteIfExists(temp);
        Files.write(temp, diagnostic, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        Path direct = output.resolve(derivedName(claim, ".error.txt"));
        try {
            publishComplete(temp, direct);
        } catch (JobFailure collision) {
            Files.deleteIfExists(temp);
            Files.write(temp, diagnostic, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                publishComplete(temp, output.resolve(
                    "job-" + claim.jobId() + "-" + attemptId + ".error.txt"));
            } catch (JobFailure corruptRecovery) {
                throw new IOException("unique diagnostic path collision", corruptRecovery);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Publish a fully written same-directory file without any overwrite path. */
    private static void publishComplete(Path complete, Path target)
            throws IOException, JobFailure {
        try {
            Files.createLink(target, complete);
        } catch (FileAlreadyExistsException collision) {
            if (!sameRegularFileBytes(complete, target)) {
                throw new JobFailure("output-collision");
            }
        }
        Files.delete(complete);
    }

    private boolean archive(Claim claim, FileChannel source, Path bucket)
            throws IOException {
        Path direct = bucket.resolve(claim.originalName());
        ArchiveAttempt directAttempt = tryArchiveAt(claim.path(), source, direct);
        if (directAttempt == ArchiveAttempt.ARCHIVED) {
            return true;
        }
        if (directAttempt == ArchiveAttempt.ALREADY_ARCHIVED) {
            return false;
        }

        Path collisionDir = bucket.resolve("collisions").resolve(claim.jobId());
        ensureDirectory(collisionDir);
        Path collisionTarget = collisionDir.resolve(claim.originalName());
        ArchiveAttempt collisionAttempt = tryArchiveAt(
            claim.path(), source, collisionTarget);
        if (collisionAttempt == ArchiveAttempt.ARCHIVED) {
            return true;
        }
        if (collisionAttempt == ArchiveAttempt.ALREADY_ARCHIVED
                || archivedAnywhere(claim, source)) {
            return false;
        }
        throw new IOException("claim source disappeared before archive");
    }

    /**
     * Finalize a staged unreadable source without inspecting or copying its
     * bytes. This runs only after diagnostic publication. The no-replace file
     * move restores the ordinary {@code failed/original-name} shape when that
     * name is free; a collision remains below {@code failed/collisions/job-id}.
     */
    private boolean archiveUnreadable(Claim claim) throws IOException {
        Path claimDirectory = claim.path().getParent();
        Path collisionDirectory = failed.resolve("collisions").resolve(claim.jobId());
        try {
            Files.move(claimDirectory, collisionDirectory,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("input mount cannot atomically archive unreadable work",
                unsupported);
        } catch (IOException racedOrFailed) {
            if (!Files.exists(claimDirectory, LinkOption.NOFOLLOW_LINKS)
                    && unreadableArchived(claim, collisionDirectory)) {
                return false;
            }
            throw racedOrFailed;
        }

        Path archivedSource = collisionDirectory.resolve(claim.originalName());
        if (!sameUnreadableIdentity(claim, archivedSource)) {
            throw new IOException("unreadable claim archive lost its source");
        }

        Path direct = failed.resolve(claim.originalName());
        try {
            // No REPLACE_EXISTING option: a same-name archive is never
            // overwritten. Both paths are on the input mount, so this is a
            // metadata-only rename and does not need permission to read bytes.
            Files.move(archivedSource, direct);
            deleteEmptyClaimDirectory(collisionDirectory);
        } catch (FileAlreadyExistsException collision) {
            // The source is already in its final collision archive.
        } catch (IOException promotionFailure) {
            // The source already has a durable failed/collisions disposition.
            // Promotion is cosmetic; do not turn it back into live work or
            // stop the watcher when a mount refuses the shorter presentation.
            if (!sameUnreadableIdentity(claim, archivedSource)) {
                throw promotionFailure;
            }
        }
        return true;
    }

    /**
     * A failed processing-to-pending move can mean another worker is still in
     * pending or already reached either final archive shape. Compare the
     * snapshotted inode identity at every location: a pre-existing same-name
     * failed file is not proof that this claim completed.
     */
    private boolean unreadableDispositionExists(Claim claim, Path pendingDirectory)
            throws IOException {
        return sameUnreadableIdentity(
                claim, pendingDirectory.resolve(claim.originalName()))
            || unreadableArchived(claim,
                failed.resolve("collisions").resolve(claim.jobId()));
    }

    private boolean unreadableArchived(Claim claim, Path collisionDirectory)
            throws IOException {
        return sameUnreadableIdentity(
                claim, collisionDirectory.resolve(claim.originalName()))
            || sameUnreadableIdentity(
                claim, failed.resolve(claim.originalName()));
    }

    private static boolean sameUnreadableIdentity(Claim claim, Path candidate)
            throws IOException {
        // A null file key cannot prove inode continuity. Fail closed instead
        // of falling back to a same-name check; the supported Docker bind-mount
        // path exposes a stable key and the smoke suite verifies it across moves.
        if (claim.identity() == null || claim.identity().fileKey() == null) {
            return false;
        }
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(candidate,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return false;
        }
        return attributes.isRegularFile()
            && attributes.size() == claim.identity().size()
            && Objects.equals(attributes.fileKey(), claim.identity().fileKey());
    }

    private static ArchiveAttempt tryArchiveAt(Path sourcePath, FileChannel source,
            Path target)
            throws IOException {
        try {
            Files.createLink(target, sourcePath);
            return Files.deleteIfExists(sourcePath)
                ? ArchiveAttempt.ARCHIVED : ArchiveAttempt.ALREADY_ARCHIVED;
        } catch (FileAlreadyExistsException collision) {
            if (!sameOpenFileBytes(source, target)) {
                return ArchiveAttempt.TARGET_COLLISION;
            }
            return Files.deleteIfExists(sourcePath)
                ? ArchiveAttempt.ARCHIVED : ArchiveAttempt.ALREADY_ARCHIVED;
        } catch (NoSuchFileException sourceDisappeared) {
            return sameOpenFileBytes(source, target)
                ? ArchiveAttempt.ALREADY_ARCHIVED : ArchiveAttempt.SOURCE_MISSING;
        }
    }

    private boolean archivedAnywhere(Claim claim, FileChannel source) throws IOException {
        return archivedIn(finished, claim, source) || archivedIn(failed, claim, source);
    }

    private static boolean archivedIn(Path bucket, Claim claim, FileChannel source)
            throws IOException {
        Path direct = bucket.resolve(claim.originalName());
        Path collision = bucket.resolve("collisions").resolve(claim.jobId())
            .resolve(claim.originalName());
        return sameOpenFileBytes(source, direct) || sameOpenFileBytes(source, collision);
    }

    private static boolean sameOpenFileBytes(FileChannel source, Path target)
            throws IOException {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (FileChannel other = FileChannel.open(target,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = source.size();
            if (size != other.size()) {
                return false;
            }
            ByteBuffer left = ByteBuffer.allocate(8192);
            ByteBuffer right = ByteBuffer.allocate(8192);
            long position = 0;
            while (position < size) {
                int chunk = (int) Math.min(left.capacity(), size - position);
                left.clear();
                right.clear();
                left.limit(chunk);
                right.limit(chunk);
                if (readAt(source, left, position) != chunk
                        || readAt(other, right, position) != chunk) {
                    return false;
                }
                left.flip();
                right.flip();
                if (!left.equals(right)) {
                    return false;
                }
                position += chunk;
            }
            return true;
        } catch (NoSuchFileException raced) {
            return false;
        }
    }

    private static int readAt(FileChannel channel, ByteBuffer buffer, long position)
            throws IOException {
        int total = 0;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + total);
            if (read <= 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    private static boolean sameRegularFileBytes(Path first, Path second)
            throws IOException {
        return Files.isRegularFile(first, LinkOption.NOFOLLOW_LINKS)
            && Files.isRegularFile(second, LinkOption.NOFOLLOW_LINKS)
            && Files.mismatch(first, second) == -1;
    }

    private static List<Path> directChildren(Path dir) throws IOException {
        List<Path> children = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                children.add(child);
            }
        } catch (DirectoryIteratorException failure) {
            throw failure.getCause();
        }
        return children;
    }

    private static boolean eligible(Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        String name = path.getFileName().toString();
        if (name.startsWith(".")) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".html") || lower.endsWith(".htm");
    }

    private static Claim parseClaim(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        String jobId = path.getFileName().toString();
        if (!validJobId(jobId)) {
            return null;
        }
        List<Path> children;
        try {
            children = directChildren(path);
        } catch (NoSuchFileException raced) {
            return null;
        }
        if (children.size() != 1) {
            return null;
        }
        Path claimed = children.get(0);
        if (!Files.isRegularFile(claimed, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        String original = claimed.getFileName().toString();
        return eligibleName(original) ? claimAt(jobId, original, claimed) : null;
    }

    private static Claim claimAt(String jobId, String originalName, Path path)
            throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException raced) {
            return null;
        }
        if (!attributes.isRegularFile()) {
            return null;
        }
        return new Claim(jobId, originalName, path,
            new FileIdentity(attributes.fileKey(), attributes.size()));
    }

    private static boolean validJobId(String value) {
        if (value.length() != CLAIM_ID_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static boolean eligibleName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return !name.startsWith(".")
            && (lower.endsWith(".html") || lower.endsWith(".htm"));
    }

    private static void ensureDirectory(Path dir) throws IOException {
        Files.createDirectories(dir);
        if (Files.isSymbolicLink(dir)
                || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("required worker path is not a real directory");
        }
    }

    private static void deleteEmptyClaimDirectory(Path directory) throws IOException {
        try {
            Files.deleteIfExists(directory);
        } catch (DirectoryNotEmptyException | NoSuchFileException racedOrInUse) {
            // Another worker still owns the claim, or already removed it.
        }
    }

    private static String derivedName(Claim claim, String suffix) {
        String preferred = claim.originalName() + suffix;
        if (preferred.getBytes(StandardCharsets.UTF_8).length
                <= MAX_DERIVED_NAME_BYTES) {
            return preferred;
        }
        return "job-" + claim.jobId() + suffix;
    }

    private static Path envPath(String name, Path fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Path.of(value);
    }

    private static long envPollMillis() {
        String value = System.getenv("BREWSHOT_WATCH_POLL_MS");
        if (value == null || value.isBlank()) {
            return DEFAULT_POLL_MILLIS;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed >= MIN_POLL_MILLIS && parsed <= MAX_POLL_MILLIS) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // Fixed refusal below; never echo the untrusted environment value.
        }
        throw new IllegalArgumentException(
            "BREWSHOT_WATCH_POLL_MS must be between 10 and 60000");
    }

    private static String boundedCode(String raw) {
        String code = raw == null ? "unknown" : raw.replaceAll("[^A-Za-z0-9._-]", "-");
        if (code.length() > DIAGNOSTIC_LIMIT) {
            return code.substring(0, DIAGNOSTIC_LIMIT);
        }
        return code;
    }

    private record Claim(String jobId, String originalName, Path path,
                         FileIdentity identity) { }

    private record FileIdentity(Object fileKey, long size) { }

    private record ClaimLease(FileChannel channel, FileLock lock)
            implements AutoCloseable {
        @Override
        public void close() throws IOException {
            // Closing the channel releases any advisory lock. The worker's
            // idempotent state transitions remain the correctness boundary.
            channel.close();
        }
    }

    private enum ArchiveAttempt {
        ARCHIVED,
        ALREADY_ARCHIVED,
        TARGET_COLLISION,
        SOURCE_MISSING
    }

    private static final class JobFailure extends Exception {
        private final String code;

        JobFailure(String code) {
            super(code);
            this.code = code;
        }

        String code() {
            return code;
        }
    }
}
