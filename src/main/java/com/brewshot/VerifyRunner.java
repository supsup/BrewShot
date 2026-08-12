package com.brewshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Executes one already-authored verify manifest after whole-batch preflight. */
final class VerifyRunner {

    @FunctionalInterface
    interface Capture {
        int capture(VerifyManifest.Job job, Path input, Path output) throws Exception;
    }

    private final Capture capture;
    private final ArtifactWriter.MoveStrategy mover;

    private VerifyRunner(Capture capture, ArtifactWriter.MoveStrategy mover) {
        this.capture = Objects.requireNonNull(capture, "capture");
        this.mover = mover;
    }

    static int run(Path manifestPath, VerifyPreflight.Mode mode) {
        return new VerifyRunner(VerifyRunner::captureWithChrome, null)
            .execute(manifestPath, mode);
    }

    static VerifyRunner forTest(Capture capture, ArtifactWriter.MoveStrategy mover) {
        return new VerifyRunner(capture, mover);
    }

    int execute(Path manifestPath, VerifyPreflight.Mode mode) {
        Objects.requireNonNull(manifestPath, "manifestPath");
        Objects.requireNonNull(mode, "mode");
        VerifyManifest manifest;
        VerifyPreflight.Prepared prepared;
        try {
            manifest = VerifyManifest.load(manifestPath);
            prepared = VerifyPreflight.inspect(manifest, mode);
            prepared.createStaging();
        } catch (VerifyManifest.ManifestException invalid) {
            System.err.println("brewshot: " + invalid.getMessage());
            return invalid.failure().category().exitCode();
        } catch (VerifyPreflight.PreflightException invalid) {
            System.err.println("brewshot: " + invalid.getMessage());
            return invalid.failure().category().exitCode();
        }

        List<JobState> states = new ArrayList<>(prepared.jobs().size());
        List<VerifyManifest.Failure> batchFailures = new ArrayList<>();
        for (VerifyPreflight.PreparedJob job : prepared.jobs()) {
            states.add(new JobState(job));
        }

        try {
            snapshotInputs(prepared, states);
        } catch (IOException snapshotFailure) {
            System.err.println("brewshot: cannot snapshot verify inputs: "
                + stableMessage(prepared, snapshotFailure));
            cleanupStaging(prepared.stagingRoot());
            return VerifyManifest.FailureCategory.PREFLIGHT.exitCode();
        }

        for (JobState state : states) {
            capture(state, prepared, batchFailures);
        }

        if (mode == VerifyPreflight.Mode.CHECK) {
            runCheck(prepared, states, batchFailures);
        } else {
            runUpdate(prepared, states, batchFailures);
        }

        publishEvidence(prepared, states, batchFailures);
        cleanupStaging(prepared.stagingRoot());
        cleanupCommitTemporaries(states);
        return finalExit(states, batchFailures);
    }

    private static void snapshotInputs(VerifyPreflight.Prepared prepared,
                                       List<JobState> states) throws IOException {
        for (JobState state : states) {
            Snapshot input = snapshot(state.job().input(), state.prepared.stagedInput());
            state.inputSha256 = input.sha256();
            if (prepared.mode() == VerifyPreflight.Mode.CHECK) {
                Snapshot baseline = snapshot(
                    state.job().baseline(), state.prepared.stagedBaselineInput());
                state.originalBaselineSha256 = baseline.sha256();
                state.originalBaselineMetadata = baseline.metadata();
            } else if (Files.isRegularFile(
                    state.job().baseline(), LinkOption.NOFOLLOW_LINKS)) {
                Snapshot baseline = inspectStable(state.job().baseline());
                state.originalBaselineSha256 = baseline.sha256();
                state.originalBaselineMetadata = baseline.metadata();
            }
        }
    }

    private void capture(JobState state, VerifyPreflight.Prepared prepared,
                         List<VerifyManifest.Failure> batchFailures) {
        try {
            int exit = capture.capture(
                state.job(), state.prepared.stagedInput(), state.prepared.stagedCapture());
            state.captureExit = exit;
            if (exit == 0
                    && Files.isRegularFile(
                        state.prepared.stagedCapture(), LinkOption.NOFOLLOW_LINKS)) {
                state.captureSha256 = sha256(state.prepared.stagedCapture());
                state.status = "captured";
                return;
            }
            state.fail(VerifyManifest.FailureCategory.CAPTURE,
                "capture returned exit " + exit);
        } catch (Exception failure) {
            state.fail(VerifyManifest.FailureCategory.CAPTURE,
                "capture failed: " + stableMessage(prepared, failure));
        }
        batchFailures.add(state.failure);
    }

    private void runCheck(VerifyPreflight.Prepared prepared, List<JobState> states,
                          List<VerifyManifest.Failure> batchFailures) {
        try {
            prepared.revalidateStagedGraph();
        } catch (VerifyPreflight.PreflightException changed) {
            VerifyManifest.Failure failure = changed.failure();
            batchFailures.add(failure);
            for (JobState state : states) {
                if (state.failure == null) {
                    state.fail(failure.category(), "batch preflight changed before diff");
                }
            }
            return;
        }

        List<Main.DiffJob> jobs = new ArrayList<>();
        List<JobState> runnable = new ArrayList<>();
        for (JobState state : states) {
            if (state.failure != null) {
                continue;
            }
            VerifyManifest.DiffOptions diff = state.job().diff();
            jobs.add(new Main.DiffJob(
                state.prepared.stagedBaselineInput(), state.prepared.stagedCapture(),
                diff.options(),
                diff.failOverPct(), diff.failPixels(), state.prepared.stagedHeatmap(),
                state.prepared.stagedReceipt()));
            runnable.add(state);
        }

        int diffExit = jobs.isEmpty() ? 0 : Main.runDiffJobs(jobs);
        for (JobState state : runnable) {
            readDiffResult(prepared, state, batchFailures);
        }
        if (diffExit == 2) {
            batchFailures.add(new VerifyManifest.Failure(
                VerifyManifest.FailureCategory.PREFLIGHT, null,
                "diff batch refused during image preflight"));
        } else if (diffExit == 1 && runnable.stream().noneMatch(s -> s.failure != null)) {
            batchFailures.add(new VerifyManifest.Failure(
                VerifyManifest.FailureCategory.DIFF, null,
                "diff batch reported an artifact or image failure"));
        }
        verifyCheckSourcesUnchanged(prepared, states, batchFailures);
    }

    private static void verifyCheckSourcesUnchanged(
            VerifyPreflight.Prepared prepared, List<JobState> states,
            List<VerifyManifest.Failure> batchFailures) {
        for (JobState state : states) {
            if (!contentAndMetadataUnchanged(state.job().baseline(),
                    state.originalBaselineSha256, state.originalBaselineMetadata)) {
                String message = "baseline changed during check: "
                    + relative(prepared, state.job().baseline());
                state.fail(VerifyManifest.FailureCategory.PREFLIGHT, message);
                batchFailures.add(new VerifyManifest.Failure(
                    VerifyManifest.FailureCategory.PREFLIGHT, state.job().id(), message));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void readDiffResult(VerifyPreflight.Prepared prepared, JobState state,
                                List<VerifyManifest.Failure> batchFailures) {
        if (!Files.isRegularFile(state.prepared.stagedReceipt(), LinkOption.NOFOLLOW_LINKS)) {
            state.fail(VerifyManifest.FailureCategory.DIFF,
                "diff did not produce a verdict receipt");
            batchFailures.add(state.failure);
            return;
        }
        try {
            Object parsed = MiniJson.parseStrict(
                Files.readString(state.prepared.stagedReceipt(), StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> raw)) {
                throw new IOException("diff receipt root is not an object");
            }
            state.diff = new LinkedHashMap<>((Map<String, Object>) raw);
            boolean exceeded = Boolean.TRUE.equals(state.diff.get("exceeded"));
            state.heatmapWritten = state.prepared.stagedHeatmap() != null
                && Files.isRegularFile(
                    state.prepared.stagedHeatmap(), LinkOption.NOFOLLOW_LINKS);
            boolean sizeMismatch = Boolean.TRUE.equals(state.diff.get("sizeMismatch"));
            if (state.prepared.stagedHeatmap() != null
                    && !state.heatmapWritten && !sizeMismatch) {
                state.fail(VerifyManifest.FailureCategory.DIFF,
                    "diff heatmap was not produced");
                batchFailures.add(state.failure);
            } else if (exceeded) {
                state.fail(VerifyManifest.FailureCategory.THRESHOLD,
                    "visual threshold exceeded");
                batchFailures.add(state.failure);
            } else {
                state.status = "passed";
            }
        } catch (IOException | IllegalArgumentException invalid) {
            state.fail(VerifyManifest.FailureCategory.DIFF,
                "cannot read diff receipt: " + stableMessage(prepared, invalid));
            batchFailures.add(state.failure);
        }
    }

    private void runUpdate(VerifyPreflight.Prepared prepared, List<JobState> states,
                           List<VerifyManifest.Failure> batchFailures) {
        if (states.stream().anyMatch(state -> state.failure != null)) {
            markBatchAborted(states, "capture batch failed before baseline preparation");
            return;
        }

        List<Main.DiffJob> validationJobs = new ArrayList<>(states.size());
        for (JobState state : states) {
            validationJobs.add(new Main.DiffJob(
                state.prepared.stagedCapture(), state.prepared.stagedCapture(),
                BrewShotDiff.Options.defaults(), null, null, null, null));
        }
        int validationExit = Main.runDiffJobs(validationJobs);
        if (validationExit != 0) {
            VerifyManifest.Failure failure = new VerifyManifest.Failure(
                validationExit == 2
                    ? VerifyManifest.FailureCategory.PREFLIGHT
                    : VerifyManifest.FailureCategory.DIFF,
                null, "captured baseline candidates failed image validation");
            batchFailures.add(failure);
            for (JobState state : states) {
                state.fail(failure.category(), "baseline candidate validation failed");
            }
            return;
        }

        try {
            for (JobState state : states) {
                Files.copy(state.prepared.stagedCapture(), state.prepared.stagedBaseline());
                state.candidateSha256 = sha256(state.prepared.stagedBaseline());
                state.status = "candidate-staged";
            }
            prepared.revalidateStagedGraph();
            for (JobState state : states) {
                if (!contentAndMetadataUnchanged(state.job().baseline(),
                        state.originalBaselineSha256, state.originalBaselineMetadata)) {
                    throw new IOException("baseline changed before commit: "
                        + relative(prepared, state.job().baseline()));
                }
            }
            for (JobState state : states) {
                ensureParent(prepared, state.prepared.commitTemporary());
                ArtifactWriter.prepareCopy(
                    state.prepared.stagedBaseline(), state.prepared.commitTemporary(),
                    state.job().baseline());
                state.status = "commit-prepared";
            }
            prepared.revalidateCommitGraph();
        } catch (IOException | VerifyPreflight.PreflightException preparationFailure) {
            VerifyManifest.Failure failure = preparationFailure
                instanceof VerifyPreflight.PreflightException preflight
                    ? preflight.failure()
                    : new VerifyManifest.Failure(
                        VerifyManifest.FailureCategory.COMMIT, null,
                        "cannot prepare complete baseline set: "
                            + stableMessage(prepared, preparationFailure));
            batchFailures.add(failure);
            for (JobState state : states) {
                if (!"commit-prepared".equals(state.status)) {
                    state.fail(failure.category(), "baseline set preparation failed");
                } else {
                    state.status = "batch-aborted";
                }
            }
            return;
        }

        for (int index = 0; index < states.size(); index++) {
            JobState state = states.get(index);
            if (index == 0 && !writeInProgressMarker(prepared, states, batchFailures)) {
                state.fail(VerifyManifest.FailureCategory.COMMIT,
                    "cannot invalidate the prior batch receipt before replacement");
                for (int remaining = 1; remaining < states.size(); remaining++) {
                    states.get(remaining).status = "not-attempted-without-run-marker";
                }
                break;
            }
            state.status = "commit-attempting";
            if (!writeLedger(prepared, states, batchFailures)) {
                state.fail(VerifyManifest.FailureCategory.COMMIT,
                    "commit ledger unavailable before replacement");
                for (int remaining = index + 1; remaining < states.size(); remaining++) {
                    states.get(remaining).status = "not-attempted-after-commit-failure";
                }
                break;
            }
            state.commitAttempted = true;
            try {
                ArtifactWriter.MoveOutcome outcome = mover == null
                    ? ArtifactWriter.commitPrepared(
                        state.prepared.commitTemporary(), state.job().baseline())
                    : ArtifactWriter.commitPrepared(
                        state.prepared.commitTemporary(), state.job().baseline(), mover);
                state.commitOutcome = outcome.receiptValue();
                state.status = "updated";
                if (!writeLedger(prepared, states, batchFailures)) {
                    state.note = "baseline replaced, but the staged ledger update failed";
                    for (int remaining = index + 1; remaining < states.size(); remaining++) {
                        states.get(remaining).status = "not-attempted-after-ledger-failure";
                    }
                    break;
                }
            } catch (IOException commitFailure) {
                state.commitOutcome = observeReplacementAfterFailure(state);
                state.fail(VerifyManifest.FailureCategory.COMMIT,
                    "baseline replacement failed: "
                        + stableMessage(prepared, commitFailure));
                batchFailures.add(state.failure);
                for (int remaining = index + 1; remaining < states.size(); remaining++) {
                    states.get(remaining).status = "not-attempted-after-commit-failure";
                }
                writeLedger(prepared, states, batchFailures);
                break;
            }
        }
    }

    private boolean writeLedger(VerifyPreflight.Prepared prepared, List<JobState> states,
                                List<VerifyManifest.Failure> batchFailures) {
        try {
            ArtifactWriter.writeString(prepared.commitLedger(),
                MiniJson.stringifyPretty(commitLedger(states)) + "\n",
                StandardCharsets.UTF_8);
            return true;
        } catch (IOException ledgerFailure) {
            batchFailures.add(new VerifyManifest.Failure(
                VerifyManifest.FailureCategory.COMMIT, null,
                "cannot update staged commit ledger: "
                    + stableMessage(prepared, ledgerFailure)));
            return false;
        }
    }

    private boolean writeInProgressMarker(
            VerifyPreflight.Prepared prepared, List<JobState> states,
            List<VerifyManifest.Failure> batchFailures) {
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("schemaVersion", 1);
        marker.put("manifestSha256", prepared.manifest().sourceSha256());
        marker.put("attemptId", prepared.stageId());
        marker.put("contentDigest", contentDigest(prepared, states));
        marker.put("state", "in-progress");
        marker.put("mode", "update");
        marker.put("powerLossDurable", false);
        marker.put("message", "verify evidence is incomplete"
            + (prepared.mode() == VerifyPreflight.Mode.UPDATE
                ? " and baseline replacement may be partial" : "")
            + "; per-job receipts are authoritative only when this batch receipt says "
            + "complete with the same attemptId");
        try {
            ensureParent(prepared, prepared.batchReceipt());
            ArtifactWriter.writeString(prepared.batchReceipt(),
                MiniJson.stringifyPretty(marker) + "\n", StandardCharsets.UTF_8);
            return true;
        } catch (IOException failure) {
            batchFailures.add(new VerifyManifest.Failure(
                VerifyManifest.FailureCategory.COMMIT, null,
                "cannot write in-progress batch marker: "
                    + stableMessage(prepared, failure)));
            return false;
        }
    }

    private void publishEvidence(VerifyPreflight.Prepared prepared, List<JobState> states,
                                 List<VerifyManifest.Failure> batchFailures) {
        try {
            prepared.revalidateEvidenceGraph();
        } catch (VerifyPreflight.PreflightException unsafe) {
            batchFailures.add(unsafe.failure());
            System.err.println("brewshot: refusing verify receipt publication after graph drift: "
                + unsafe.failure().message());
            return;
        }
        if (!writeInProgressMarker(prepared, states, batchFailures)) {
            return;
        }
        String attemptId = prepared.stageId();
        String contentDigest = contentDigest(prepared, states);
        for (JobState state : states) {
            if (state.heatmapWritten) {
                try {
                    ensureParent(prepared, state.job().heatmap());
                    ArtifactWriter.write(state.job().heatmap(), temporary ->
                        copyContents(state.prepared.stagedHeatmap(), temporary));
                    state.heatmapPublished = true;
                } catch (IOException failure) {
                    String message = "cannot publish heatmap: "
                        + stableMessage(prepared, failure);
                    state.fail(VerifyManifest.FailureCategory.DIFF, message);
                    batchFailures.add(new VerifyManifest.Failure(
                        VerifyManifest.FailureCategory.DIFF, state.job().id(), message));
                }
            }
            try {
                ensureParent(prepared, state.job().receipt());
                ArtifactWriter.writeString(state.prepared.stagedReceipt(),
                    MiniJson.stringifyPretty(
                        jobReceipt(prepared, state, attemptId, contentDigest)) + "\n",
                    StandardCharsets.UTF_8);
                ArtifactWriter.write(state.job().receipt(), temporary ->
                    copyContents(state.prepared.stagedReceipt(), temporary));
                state.receiptPublished = true;
            } catch (IOException failure) {
                String message = "cannot publish job receipt: "
                    + stableMessage(prepared, failure);
                state.fail(VerifyManifest.FailureCategory.DIFF, message);
                batchFailures.add(new VerifyManifest.Failure(
                    VerifyManifest.FailureCategory.DIFF, state.job().id(), message));
            }
        }

        if (states.stream().anyMatch(state -> !state.receiptPublished)) {
            System.err.println("brewshot: leaving verify batch in-progress because at least "
                + "one authoritative job receipt was not published");
            return;
        }

        int exit = finalExit(states, batchFailures);
        Map<String, Object> batch = batchReceipt(prepared, states, batchFailures, exit);
        try {
            ArtifactWriter.writeString(prepared.stagedBatchReceipt(),
                MiniJson.stringifyPretty(batch) + "\n", StandardCharsets.UTF_8);
            ensureParent(prepared, prepared.batchReceipt());
            ArtifactWriter.write(prepared.batchReceipt(), temporary ->
                copyContents(prepared.stagedBatchReceipt(), temporary));
            System.err.println("brewshot: wrote " + prepared.batchReceipt());
        } catch (IOException failure) {
            batchFailures.add(new VerifyManifest.Failure(
                VerifyManifest.FailureCategory.DIFF, null,
                "cannot publish batch receipt: " + stableMessage(prepared, failure)));
            System.err.println("brewshot: failed writing verify batch receipt: "
                + stableMessage(prepared, failure));
        }
    }

    private static Map<String, Object> jobReceipt(
            VerifyPreflight.Prepared prepared, JobState state, String attemptId,
            String contentDigest) {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("schemaVersion", 1);
        receipt.put("attemptId", attemptId);
        receipt.put("attemptIdExcludedFromDeterministicCore", true);
        receipt.put("authoritativeWhenBatchState", "complete");
        Map<String, Object> core = new LinkedHashMap<>();
        core.put("contentDigest", contentDigest);
        core.put("manifestSha256", prepared.manifest().sourceSha256());
        core.put("mode", prepared.mode().name().toLowerCase(java.util.Locale.ROOT));
        core.put("jobId", state.job().id());
        core.put("input", relative(prepared, state.job().input()));
        core.put("inputSha256", state.inputSha256);
        core.put("baseline", relative(prepared, state.job().baseline()));
        core.put("baselineInputSha256", state.originalBaselineSha256);
        core.put("baselineInputMetadataSha256", state.originalBaselineMetadata);
        core.put("status", state.status);
        core.put("note", state.note);
        Map<String, Object> captureFields = new LinkedHashMap<>();
        captureFields.put("exit", state.captureExit);
        captureFields.put("written", Files.isRegularFile(
            state.prepared.stagedCapture(), LinkOption.NOFOLLOW_LINKS));
        captureFields.put("sha256", state.captureSha256);
        core.put("capture", captureFields);
        core.put("diff", state.diff);
        Map<String, Object> heatmap = new LinkedHashMap<>();
        heatmap.put("requested", state.job().heatmap() != null);
        heatmap.put("applicable", prepared.mode() == VerifyPreflight.Mode.CHECK);
        heatmap.put("written", state.heatmapPublished);
        core.put("heatmap", heatmap);
        Map<String, Object> commit = new LinkedHashMap<>();
        commit.put("applicable", prepared.mode() == VerifyPreflight.Mode.UPDATE);
        commit.put("attempted", state.commitAttempted);
        commit.put("outcome", state.commitOutcome);
        commit.put("priorSha256", state.originalBaselineSha256);
        commit.put("candidateSha256", state.candidateSha256);
        core.put("baselineCommit", commit);
        core.put("failure", failureMap(state.failure));
        receipt.put("deterministicCore", core);
        return receipt;
    }

    private static Map<String, Object> batchReceipt(
            VerifyPreflight.Prepared prepared, List<JobState> states,
            List<VerifyManifest.Failure> batchFailures, int exit) {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("schemaVersion", 1);
        receipt.put("attemptId", prepared.stageId());
        receipt.put("attemptIdExcludedFromDeterministicCore", true);
        Map<String, Object> core = new LinkedHashMap<>();
        core.put("contentDigest", contentDigest(prepared, states));
        core.put("manifestSha256", prepared.manifest().sourceSha256());
        core.put("state", "complete");
        core.put("powerLossDurable", false);
        core.put("mode", prepared.mode().name().toLowerCase(java.util.Locale.ROOT));
        core.put("jobCount", states.size());
        List<Map<String, Object>> jobs = new ArrayList<>(states.size());
        for (JobState state : states) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("jobId", state.job().id());
            row.put("status", state.status);
            row.put("receiptWritten", state.receiptPublished);
            row.put("failure", failureMap(state.failure));
            row.put("commitAttempted", state.commitAttempted);
            row.put("commitOutcome", state.commitOutcome);
            jobs.add(row);
        }
        core.put("jobs", jobs);
        core.put("baselineCommit", prepared.mode() == VerifyPreflight.Mode.UPDATE
            ? commitSummary(states) : notApplicableCommitSummary());
        List<Map<String, Object>> failures = new ArrayList<>();
        for (VerifyManifest.Failure failure : batchFailures) {
            failures.add(failureMap(failure));
        }
        core.put("failures", failures);
        core.put("exit", exit);
        receipt.put("deterministicCore", core);
        return receipt;
    }

    private static Map<String, Object> commitSummary(List<JobState> states) {
        int attempted = 0;
        int replaced = 0;
        int atomic = 0;
        int nonAtomic = 0;
        int unknownAtomicity = 0;
        int failed = 0;
        int indeterminate = 0;
        for (JobState state : states) {
            if (state.commitAttempted) {
                attempted++;
            }
            if ("atomic-replace".equals(state.commitOutcome)) {
                atomic++;
                replaced++;
            } else if ("non-atomic-replace".equals(state.commitOutcome)) {
                nonAtomic++;
                replaced++;
            } else if ("replacement-observed-after-error".equals(state.commitOutcome)) {
                unknownAtomicity++;
                replaced++;
            } else if ("indeterminate-after-error".equals(state.commitOutcome)) {
                indeterminate++;
            }
            if (state.failure != null
                    && state.failure.category() == VerifyManifest.FailureCategory.COMMIT) {
                failed++;
            }
        }
        Map<String, Object> commit = new LinkedHashMap<>();
        commit.put("model", "logical-staged-commit");
        commit.put("crashAtomic", false);
        commit.put("attempted", attempted);
        commit.put("replaced", replaced);
        commit.put("atomicReplacements", atomic);
        commit.put("nonAtomicReplacements", nonAtomic);
        commit.put("unknownAtomicityReplacements", unknownAtomicity);
        commit.put("failed", failed);
        commit.put("indeterminate", indeterminate);
        commit.put("partial", (replaced > 0 && replaced < states.size()) || indeterminate > 0);
        return commit;
    }

    private static Map<String, Object> notApplicableCommitSummary() {
        Map<String, Object> commit = new LinkedHashMap<>();
        commit.put("model", "not-applicable-in-check-mode");
        return commit;
    }

    private static String contentDigest(
            VerifyPreflight.Prepared prepared, List<JobState> states) {
        StringBuilder material = new StringBuilder(prepared.manifest().sourceSha256())
            .append('\n').append(prepared.mode().name());
        for (JobState state : states) {
            material.append('\n').append(state.job().id())
                .append(':').append(state.inputSha256)
                .append(':').append(state.originalBaselineSha256)
                .append(':').append(state.captureSha256);
        }
        return VerifyManifest.sha256(material.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> commitLedger(List<JobState> states) {
        Map<String, Object> ledger = new LinkedHashMap<>();
        ledger.put("summary", commitSummary(states));
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (JobState state : states) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("jobId", state.job().id());
            row.put("status", state.status);
            row.put("attempted", state.commitAttempted);
            row.put("outcome", state.commitOutcome);
            row.put("failure", failureMap(state.failure));
            jobs.add(row);
        }
        ledger.put("jobs", jobs);
        return ledger;
    }

    private static String observeReplacementAfterFailure(JobState state) {
        try {
            if (!Files.isRegularFile(state.job().baseline(), LinkOption.NOFOLLOW_LINKS)) {
                if (state.originalBaselineSha256 == null
                        && !Files.exists(state.job().baseline(), LinkOption.NOFOLLOW_LINKS)) {
                    return "not-created-after-error";
                }
                return "indeterminate-after-error";
            }
            String observed = sha256(state.job().baseline());
            if (Objects.equals(state.candidateSha256, state.originalBaselineSha256)
                    && observed.equals(state.candidateSha256)) {
                return "indeterminate-after-error";
            }
            if (observed.equals(state.candidateSha256)) {
                return "replacement-observed-after-error";
            }
            if (Objects.equals(observed, state.originalBaselineSha256)) {
                return "preserved-after-error";
            }
            return "indeterminate-after-error";
        } catch (IOException unreadable) {
            return "indeterminate-after-error";
        }
    }

    private static Map<String, Object> failureMap(VerifyManifest.Failure failure) {
        if (failure == null) {
            return null;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("category", failure.category().name().toLowerCase(java.util.Locale.ROOT));
        fields.put("jobId", failure.jobId());
        fields.put("message", failure.message());
        return fields;
    }

    private static int finalExit(List<JobState> states,
                                 List<VerifyManifest.Failure> batchFailures) {
        List<VerifyManifest.Failure> failures = new ArrayList<>(batchFailures);
        for (JobState state : states) {
            if (state.failure != null && !failures.contains(state.failure)) {
                failures.add(state.failure);
            }
        }
        return VerifyManifest.worstExit(failures);
    }

    private static void markBatchAborted(List<JobState> states, String message) {
        for (JobState state : states) {
            if (state.failure == null) {
                state.status = "batch-aborted";
                state.note = message;
            }
        }
    }

    private static void ensureParent(VerifyPreflight.Prepared prepared, Path target)
            throws IOException {
        Path lexicalRoot = prepared.manifest().workspaceRoot();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(lexicalRoot)) {
            throw new IOException("output parent resolves outside the manifest directory");
        }
        Path relative = lexicalRoot.relativize(normalizedTarget.getParent());
        Path cursor = prepared.workspaceReal();
        for (Path component : relative) {
            cursor = cursor.resolve(component.toString());
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(cursor)
                        || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("output parent is not a plain directory: "
                        + component);
                }
            } else {
                Files.createDirectory(cursor);
            }
        }
    }

    private static String relative(VerifyPreflight.Prepared prepared, Path path) {
        return prepared.manifest().workspaceRoot().relativize(path).toString();
    }

    private static void copyContents(Path source, Path target) throws IOException {
        try (var input = Files.newInputStream(source);
             var output = Files.newOutputStream(target)) {
            input.transferTo(output);
        }
    }

    private static Snapshot snapshot(Path source, Path target) throws IOException {
        FileStamp before = stamp(source);
        String sourceBefore = sha256(source);
        copyContents(source, target);
        String staged = sha256(target);
        String sourceAfter = sha256(source);
        FileStamp after = stamp(source);
        if (!before.stabilityEquals(after)
                || !sourceBefore.equals(sourceAfter) || !sourceBefore.equals(staged)) {
            throw new IOException("source changed while it was being snapshotted: " + source);
        }
        return new Snapshot(staged, before.metadata());
    }

    private static Snapshot inspectStable(Path source) throws IOException {
        FileStamp before = stamp(source);
        String first = sha256(source);
        String second = sha256(source);
        FileStamp after = stamp(source);
        if (!before.stabilityEquals(after) || !first.equals(second)) {
            throw new IOException("source changed while it was being inspected: " + source);
        }
        return new Snapshot(first, before.metadata());
    }

    private static FileStamp stamp(Path path) throws IOException {
        BasicFileAttributes basic = Files.readAttributes(
            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        StringBuilder metadata = new StringBuilder()
            .append("size=").append(basic.size())
            .append(",mtimeMillis=").append(basic.lastModifiedTime().toMillis());
        try {
            PosixFileAttributes posix = Files.readAttributes(
                path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            List<String> permissions = posix.permissions().stream()
                .map(Enum::name).sorted().toList();
            metadata.append(",owner=").append(posix.owner().getName())
                .append(",group=").append(posix.group().getName())
                .append(",permissions=").append(String.join("+", permissions));
        } catch (UnsupportedOperationException ignored) {
            // The portable byte+mtime fingerprint remains available.
        }
        String metadataSha256 = VerifyManifest.sha256(
            metadata.toString().getBytes(StandardCharsets.UTF_8));
        return new FileStamp(
            basic.fileKey(), basic.size(), basic.lastModifiedTime().toMillis(),
            metadataSha256);
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK has no SHA-256 provider", impossible);
        }
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 0xf, 16));
            hex.append(Character.forDigit(value & 0xf, 16));
        }
        return hex.toString();
    }

    private static boolean contentAndMetadataUnchanged(
            Path path, String expectedSha256, String expectedMetadata) {
        try {
            if (expectedSha256 == null) {
                return !Files.exists(path, LinkOption.NOFOLLOW_LINKS);
            }
            return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && expectedSha256.equals(sha256(path))
                && Objects.equals(expectedMetadata, stamp(path).metadata());
        } catch (IOException | SecurityException unreadable) {
            return false;
        }
    }

    private record Snapshot(String sha256, String metadata) {
    }

    private record FileStamp(Object fileKey, long size, long modifiedMillis, String metadata) {
        boolean stabilityEquals(FileStamp other) {
            return Objects.equals(fileKey, other.fileKey)
                && size == other.size
                && modifiedMillis == other.modifiedMillis
                && Objects.equals(metadata, other.metadata);
        }
    }

    private static String stableMessage(VerifyPreflight.Prepared prepared, Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            message = failure.getClass().getSimpleName();
        }
        return message
            .replace(prepared.manifest().workspaceRoot().toString(), ".")
            .replace(prepared.workspaceReal().toString(), ".")
            .replace(prepared.stageId(), "<stage>");
    }

    private static void cleanupCommitTemporaries(List<JobState> states) {
        for (JobState state : states) {
            if (state.prepared.commitTemporary() == null) {
                continue;
            }
            try {
                Files.deleteIfExists(state.prepared.commitTemporary());
            } catch (IOException ignored) {
                // Receipts already name commit success/failure; cleanup is best effort.
            }
        }
    }

    private static void cleanupStaging(Path root) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup after durable receipts have been attempted.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup after durable receipts have been attempted.
        }
    }

    private static int captureWithChrome(VerifyManifest.Job job, Path input, Path output)
            throws Exception {
        if (!BrewShot.available()) {
            System.err.println("brewshot: no Chrome/Chromium found (set BREWSHOT_CHROME)");
            return 3;
        }
        VerifyManifest.CaptureOptions options = job.capture();
        try (BrewShot shot = BrewShot.launch(options.width(), options.height())) {
            if (options.colorScheme() != null) {
                shot.colorScheme(options.colorScheme());
            }
            if (options.media() != null) {
                shot.media(options.media());
            }
            if (options.reducedMotion()) {
                shot.reducedMotion("reduce");
            }
            shot.open(input.toUri().toString());
            if (options.waitJs() != null) {
                shot.waitFor(options.waitJs(), options.waitTimeoutMs());
            }
            shot.settle(options.settleMs());
            if (options.clipSelector() != null) {
                ArtifactWriter.writeBytes(output, shot.screenshotElement(
                    options.clipSelector(), options.scale(), options.clipPadding()));
            } else if (options.scale() != 1.0) {
                Object dimensions = shot.eval("[document.documentElement.scrollWidth,"
                    + "document.documentElement.scrollHeight].join(',')");
                String[] size = String.valueOf(dimensions).split(",");
                ArtifactWriter.writeBytes(output, shot.screenshotClip(
                    0, 0, Double.parseDouble(size[0]), Double.parseDouble(size[1]),
                    options.scale()));
            } else {
                shot.screenshot(output);
            }
        }
        System.err.println("brewshot: captured verify job " + job.id());
        return 0;
    }

    private static final class JobState {
        private final VerifyPreflight.PreparedJob prepared;
        private int captureExit = -1;
        private String status = "pending";
        private String note;
        private Map<String, Object> diff;
        private VerifyManifest.Failure failure;
        private boolean heatmapWritten;
        private boolean heatmapPublished;
        private boolean receiptPublished;
        private boolean commitAttempted;
        private String commitOutcome = "not-attempted";
        private String originalBaselineSha256;
        private String originalBaselineMetadata;
        private String candidateSha256;
        private String inputSha256;
        private String captureSha256;

        private JobState(VerifyPreflight.PreparedJob prepared) {
            this.prepared = prepared;
        }

        private VerifyManifest.Job job() {
            return prepared.job();
        }

        private void fail(VerifyManifest.FailureCategory category, String message) {
            if (failure != null && VerifyManifest.FailureCategory.worst(
                    failure.category(), category) == failure.category()) {
                return;
            }
            failure = new VerifyManifest.Failure(category, job().id(), message);
            status = switch (category) {
                case CAPTURE -> "capture-failed";
                case THRESHOLD -> "threshold-exceeded";
                case COMMIT -> "commit-failed";
                case PREFLIGHT, MANIFEST -> "preflight-failed";
                default -> "diff-failed";
            };
        }
    }
}
