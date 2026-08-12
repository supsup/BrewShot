package com.brewshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Read-only whole-batch filesystem validation and private staging allocation for
 * {@code brewshot verify}.
 *
 * <p>The inspected graph includes every authored input/final output plus the
 * derived batch receipt, same-base-directory input snapshots, sibling commit
 * temporaries, and staging tree. No directory or file is created until the
 * complete graph has passed and the manifest bytes have been rechecked.
 */
final class VerifyPreflight {

    private static final Pattern STAGE_ID = Pattern.compile("[a-z0-9]{8,64}");
    private static final Set<java.nio.file.attribute.PosixFilePermission> PRIVATE_DIRECTORY =
        PosixFilePermissions.fromString("rwx------");

    enum Mode {
        CHECK,
        UPDATE
    }

    private enum Phase {
        BEFORE_STAGING,
        STAGED,
        COMMIT_READY,
        EVIDENCE
    }

    private VerifyPreflight() {
    }

    static Prepared inspect(VerifyManifest manifest, Mode mode) throws PreflightException {
        return inspect(manifest, mode,
            UUID.randomUUID().toString().replace("-", ""));
    }

    static Prepared inspect(VerifyManifest manifest, Mode mode, String stageId)
            throws PreflightException {
        return inspect(manifest, mode, stageId, Phase.BEFORE_STAGING);
    }

    private static Prepared inspect(VerifyManifest manifest, Mode mode, String stageId,
                                    Phase phase) throws PreflightException {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(phase, "phase");
        if (stageId == null || !STAGE_ID.matcher(stageId).matches()) {
            throw new IllegalArgumentException("stageId must match [a-z0-9]{8,64}");
        }

        try {
            Path workspace = manifest.workspaceRoot();
            Path workspaceReal = workspace.toRealPath();
            Node manifestNode = existingNode(
                workspace, workspaceReal, manifest.source(), "manifest", false);
            assertManifestUnchanged(manifest);

            List<Node> graph = new ArrayList<>();
            graph.add(manifestNode);
            List<PreparedJob> jobs = new ArrayList<>(manifest.jobs().size());
            Path stagingRoot = workspace.resolve(".brewshot-verify-stage-" + stageId);
            Node stagingNode = phase == Phase.BEFORE_STAGING
                ? futureNode(workspace, workspaceReal, stagingRoot, "staging root", true)
                : existingDirectoryNode(
                    workspace, workspaceReal, stagingRoot, "staging root", true);
            graph.add(stagingNode);

            Path batchReceipt = workspace.resolve(
                manifest.source().getFileName().toString() + ".verify.json");
            graph.add(destinationNode(
                workspace, workspaceReal, batchReceipt, "batch receipt", true));

            for (VerifyManifest.Job job : manifest.jobs()) {
                String prefix = "job " + job.id() + " ";
                graph.add(existingNode(
                    workspace, workspaceReal, job.input(), prefix + "input", false));
                if (mode == Mode.CHECK) {
                    graph.add(existingNode(
                        workspace, workspaceReal, job.baseline(), prefix + "baseline", false));
                } else {
                    graph.add(destinationNode(
                        workspace, workspaceReal, job.baseline(), prefix + "baseline", true));
                }
                graph.add(destinationNode(
                    workspace, workspaceReal, job.receipt(), prefix + "receipt", true));
                if (job.heatmap() != null) {
                    graph.add(destinationNode(
                        workspace, workspaceReal, job.heatmap(), prefix + "heatmap", true));
                }

                // Keep the snapshot beside the authored HTML so relative subresources
                // resolve against the same directory when Chrome opens this generation.
                Path stagedInput = job.input().getParent().resolve(
                    ".brewshot-verify-" + stageId + "-" + job.id() + ".input.html");
                graph.add(phase == Phase.BEFORE_STAGING
                    ? futureNode(workspace, workspaceReal, stagedInput,
                        prefix + "input snapshot", true)
                    : existingNode(workspace, workspaceReal, stagedInput,
                        prefix + "input snapshot", true));
                Path stagedBaselineInput = mode == Mode.CHECK
                    ? stagingRoot.resolve(job.id() + ".baseline-input.png") : null;
                Path stagedCapture = stagingRoot.resolve(job.id() + ".capture.png");
                Path stagedReceipt = stagingRoot.resolve(job.id() + ".receipt.json");
                Path stagedHeatmap = job.heatmap() == null ? null
                    : stagingRoot.resolve(job.id() + ".heatmap.png");
                Path stagedBaseline = mode == Mode.UPDATE
                    ? stagingRoot.resolve(job.id() + ".baseline.png") : null;
                Path commitTemporary = mode == Mode.UPDATE
                    ? job.baseline().getParent().resolve(
                        ".brewshot-verify-" + stageId + "-" + job.id() + ".tmp")
                    : null;
                if (commitTemporary != null && phase != Phase.EVIDENCE) {
                    graph.add(phase == Phase.COMMIT_READY
                        ? existingNode(workspace, workspaceReal, commitTemporary,
                            prefix + "commit temporary", true)
                        : futureNode(workspace, workspaceReal, commitTemporary,
                            prefix + "commit temporary", true));
                }
                jobs.add(new PreparedJob(
                    job, stagedInput, stagedBaselineInput, stagedCapture, stagedReceipt,
                    stagedHeatmap, stagedBaseline, commitTemporary));
            }

            rejectGraphCollisions(graph);
            return new Prepared(
                manifest, mode, stageId, workspaceReal, batchReceipt, stagingRoot,
                stagingRoot.resolve("batch-receipt.json"),
                stagingRoot.resolve("commit-ledger.json"), jobs);
        } catch (PreflightProblem invalid) {
            throw failure(invalid.getMessage(), invalid);
        } catch (IOException | SecurityException invalid) {
            throw failure(message(invalid), invalid);
        }
    }

    private static Node existingNode(Path workspace, Path workspaceReal, Path path,
                                     String role, boolean writable) throws IOException {
        rejectSymlinkPath(workspace, path, role);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw problem(role + " must be an existing regular file: " + relative(workspace, path));
        }
        if (!Files.isReadable(path)) {
            throw problem(role + " is not readable: " + relative(workspace, path));
        }
        return new Node(role, path, resolveInside(workspaceReal, path, role), writable, true);
    }

    private static Node destinationNode(Path workspace, Path workspaceReal, Path path,
                                        String role, boolean writable) throws IOException {
        rejectSymlinkPath(workspace, path, role);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw problem(role + " must be a regular file destination: "
                + relative(workspace, path));
        }
        Path parent = path.getParent();
        if (parent == null) {
            throw problem(role + " has no parent directory");
        }
        requireCreatableParent(parent, role);
        Path resolved = resolveInside(workspaceReal, path, role);
        return new Node(role, path, resolved, writable,
            Files.exists(path, LinkOption.NOFOLLOW_LINKS));
    }

    private static Node futureNode(Path workspace, Path workspaceReal, Path path,
                                   String role, boolean writable) throws IOException {
        rejectSymlinkPath(workspace, path, role);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw problem(role + " already exists: " + relative(workspace, path));
        }
        requireCreatableParent(path.getParent(), role);
        return new Node(role, path, resolveInside(workspaceReal, path, role), writable, false);
    }

    private static Node existingDirectoryNode(Path workspace, Path workspaceReal, Path path,
                                              String role, boolean writable)
            throws IOException {
        rejectSymlinkPath(workspace, path, role);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw problem(role + " must be an existing directory: "
                + relative(workspace, path));
        }
        return new Node(role, path, resolveInside(workspaceReal, path, role), writable, true);
    }

    private static void requireCreatableParent(Path parent, String role) {
        Path cursor = parent;
        while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            cursor = cursor.getParent();
        }
        if (cursor == null || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
            throw problem(role + " has no existing directory ancestor");
        }
        if (!Files.isWritable(cursor)) {
            throw problem(role + " has no writable directory ancestor: " + cursor);
        }
    }

    private static Path resolveInside(Path workspaceReal, Path path, String role)
            throws IOException {
        Path cursor = path;
        Deque<Path> missing = new ArrayDeque<>();
        while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            missing.addFirst(cursor.getFileName());
            cursor = cursor.getParent();
        }
        if (cursor == null) {
            throw problem(role + " has no existing ancestor");
        }
        Path resolved = cursor.toRealPath();
        for (Path component : missing) {
            resolved = resolved.resolve(component);
        }
        resolved = resolved.normalize();
        if (!resolved.startsWith(workspaceReal)) {
            throw problem(role + " resolves outside the manifest directory: " + path);
        }
        return resolved;
    }

    private static void rejectSymlinkPath(Path workspace, Path path, String role) {
        Path relative;
        try {
            relative = workspace.relativize(path);
        } catch (IllegalArgumentException outside) {
            throw problem(role + " is outside the manifest directory: " + path);
        }
        Path cursor = workspace;
        for (Path component : relative) {
            cursor = cursor.resolve(component);
            if (Files.isSymbolicLink(cursor)) {
                throw problem(role + " traverses symbolic link: " + relative(workspace, cursor));
            }
        }
    }

    private static void rejectGraphCollisions(List<Node> graph) throws IOException {
        for (int leftIndex = 0; leftIndex < graph.size(); leftIndex++) {
            Node left = graph.get(leftIndex);
            for (int rightIndex = leftIndex + 1; rightIndex < graph.size(); rightIndex++) {
                Node right = graph.get(rightIndex);
                if (!left.writable() && !right.writable()) {
                    continue;
                }
                boolean same = left.resolved().equals(right.resolved());
                if (!same && left.exists() && right.exists()) {
                    same = Files.isSameFile(left.lexical(), right.lexical());
                }
                if (same) {
                    throw problem("path alias between " + left.role() + " and " + right.role()
                        + ": " + left.lexical());
                }
                if (left.resolved().startsWith(right.resolved())
                        || right.resolved().startsWith(left.resolved())) {
                    throw problem("ancestor/descendant path collision between " + left.role()
                        + " and " + right.role());
                }
            }
        }
    }

    private static void assertManifestUnchanged(VerifyManifest manifest)
            throws IOException {
        if (!Files.isRegularFile(manifest.source(), LinkOption.NOFOLLOW_LINKS)) {
            throw problem("manifest is missing or no longer a regular file");
        }
        String current = BoundedUtf8.readStrict(
            manifest.source(), VerifyManifest.MAX_MANIFEST_BYTES,
            "verify manifest " + manifest.source());
        String digest = VerifyManifest.sha256(current.getBytes(StandardCharsets.UTF_8));
        if (!digest.equals(manifest.sourceSha256())) {
            throw problem("manifest changed after authorship validation");
        }
    }

    private static void createPrivateDirectory(Path directory) throws IOException {
        Path parent = directory.getParent();
        FileStore store = Files.getFileStore(parent);
        if (store.supportsFileAttributeView("posix")) {
            FileAttribute<?> permissions = PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY);
            Files.createDirectory(directory, permissions);
        } else {
            Files.createDirectory(directory);
        }
    }

    private static String relative(Path workspace, Path path) {
        try {
            return workspace.relativize(path).toString();
        } catch (IllegalArgumentException outside) {
            return path.toString();
        }
    }

    private static String message(Exception failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
            ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private static PreflightProblem problem(String message) {
        return new PreflightProblem(message);
    }

    private static PreflightException failure(String message, Throwable cause) {
        return new PreflightException(new VerifyManifest.Failure(
            VerifyManifest.FailureCategory.PREFLIGHT, null,
            message == null || message.isBlank() ? "unknown preflight failure" : message), cause);
    }

    record Prepared(VerifyManifest manifest, Mode mode, String stageId, Path workspaceReal,
                    Path batchReceipt, Path stagingRoot, Path stagedBatchReceipt,
                    Path commitLedger, List<PreparedJob> jobs) {
        Prepared {
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(stageId, "stageId");
            Objects.requireNonNull(workspaceReal, "workspaceReal");
            Objects.requireNonNull(batchReceipt, "batchReceipt");
            Objects.requireNonNull(stagingRoot, "stagingRoot");
            Objects.requireNonNull(stagedBatchReceipt, "stagedBatchReceipt");
            Objects.requireNonNull(commitLedger, "commitLedger");
            jobs = List.copyOf(jobs);
        }

        void assertManifestUnchanged() throws PreflightException {
            try {
                VerifyPreflight.assertManifestUnchanged(manifest);
            } catch (PreflightProblem invalid) {
                throw failure(invalid.getMessage(), invalid);
            } catch (IOException | SecurityException invalid) {
                throw failure(message(invalid), invalid);
            }
        }

        Staging createStaging() throws PreflightException {
            VerifyPreflight.inspect(manifest, mode, stageId, Phase.BEFORE_STAGING);
            try {
                createPrivateDirectory(stagingRoot);
                return new Staging(
                    stagingRoot, stagedBatchReceipt, commitLedger, jobs);
            } catch (IOException | SecurityException invalid) {
                throw failure("cannot create private staging root: " + message(invalid), invalid);
            }
        }

        void revalidateStagedGraph() throws PreflightException {
            VerifyPreflight.inspect(manifest, mode, stageId, Phase.STAGED);
        }

        void revalidateCommitGraph() throws PreflightException {
            if (mode != Mode.UPDATE) {
                throw new IllegalStateException("commit graph exists only in update mode");
            }
            VerifyPreflight.inspect(manifest, mode, stageId, Phase.COMMIT_READY);
        }

        void revalidateEvidenceGraph() throws PreflightException {
            VerifyPreflight.inspect(manifest, mode, stageId, Phase.EVIDENCE);
        }
    }

    record PreparedJob(VerifyManifest.Job job, Path stagedInput, Path stagedBaselineInput,
                       Path stagedCapture, Path stagedReceipt, Path stagedHeatmap,
                       Path stagedBaseline, Path commitTemporary) {
        PreparedJob {
            Objects.requireNonNull(job, "job");
            Objects.requireNonNull(stagedInput, "stagedInput");
            Objects.requireNonNull(stagedCapture, "stagedCapture");
            Objects.requireNonNull(stagedReceipt, "stagedReceipt");
        }
    }

    record Staging(Path root, Path batchReceipt, Path commitLedger,
                   List<PreparedJob> jobs) {
        Staging {
            jobs = List.copyOf(jobs);
        }
    }

    static final class PreflightException extends Exception {
        private final VerifyManifest.Failure failure;

        PreflightException(VerifyManifest.Failure failure, Throwable cause) {
            super("verify preflight: " + failure.message(), cause);
            this.failure = failure;
        }

        VerifyManifest.Failure failure() {
            return failure;
        }
    }

    private record Node(String role, Path lexical, Path resolved,
                        boolean writable, boolean exists) {
    }

    private static final class PreflightProblem extends RuntimeException {
        PreflightProblem(String message) {
            super(message);
        }
    }
}
