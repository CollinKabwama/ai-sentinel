package dev.aisentinel.core.evaluation;

import dev.aisentinel.distributed.training.TrainingFingerprintHashes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Controlled lifecycle for Official Detection Reference Baseline candidates,
 * governance decisions, promotion, historical retention, and rollback.
 * <p>
 * Drift remains evidence. Approval and promotion are explicit human governance
 * actions. This tooling does not mutate baselines during verification and does
 * not claim verified human identity for supplied approver metadata.
 */
public final class DetectionReferenceBaselineLifecycle {

    private final DetectionReferenceBaselineLoader loader;
    private final DetectionReferenceBaselineCapture capture;
    private final DetectionReferenceBaselineVerifier verifier;

    public DetectionReferenceBaselineLifecycle() {
        this(
            new DetectionReferenceBaselineLoader(),
            new DetectionReferenceBaselineCapture(),
            new DetectionReferenceBaselineVerifier()
        );
    }

    DetectionReferenceBaselineLifecycle(DetectionReferenceBaselineLoader loader,
                                        DetectionReferenceBaselineCapture capture,
                                        DetectionReferenceBaselineVerifier verifier) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.capture = Objects.requireNonNull(capture, "capture");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    /**
     * Creates an isolated candidate from current repository evaluation inputs using
     * the official reference configuration (threshold 0.5).
     */
    public DetectionReferenceBaselineLifecycleResult createCandidate(Path officialBaselineDirectory,
                                                                     String candidateId) throws IOException {
        return createCandidate(
            officialBaselineDirectory,
            candidateId,
            DetectionReferenceBaselineConfiguration.officialReference()
        );
    }

    /**
     * Creates an isolated candidate using an explicit capture configuration.
     * Official maintainer tooling uses {@link #createCandidate(Path, String)}.
     */
    public DetectionReferenceBaselineLifecycleResult createCandidate(
        Path officialBaselineDirectory,
        String candidateId,
        DetectionReferenceBaselineConfiguration configuration
    ) throws IOException {
        String safeCandidateId = requireCandidateId(candidateId);
        Path officialRoot = requireOfficialRoot(officialBaselineDirectory);
        DetectionReferenceBaselineLoader.LoadedBaseline official = loadOfficial(officialRoot);

        Path candidateRoot = candidateRoot(officialRoot, safeCandidateId);
        if (Files.exists(candidateRoot)) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.CANDIDATE_ALREADY_EXISTS,
                "candidate already exists: " + safeCandidateId
            );
        }

        Path baselineDir = candidateRoot.resolve(DetectionReferenceBaselineLifecycleSchemas.BASELINE_SUBDIRECTORY);
        Files.createDirectories(candidateRoot);
        boolean completed = false;
        try {
            capture.capture(configuration, baselineDir);
            DetectionReferenceBaselineLoader.LoadedBaseline candidate = loadBaseline(
                baselineDir,
                DetectionReferenceBaselineLifecycleException.Code.CANDIDATE_INTEGRITY_FAILURE
            );

            DetectionReferenceBaselineVerificationResult comparison =
                verifier.comparePersisted(officialRoot, baselineDir);
            if (comparison.status() == DetectionReferenceBaselineVerificationStatus.BASELINE_INTEGRITY_FAILURE
                || comparison.status() == DetectionReferenceBaselineVerificationStatus.CURRENT_EVALUATION_FAILURE) {
                throw new DetectionReferenceBaselineLifecycleException(
                    DetectionReferenceBaselineLifecycleException.Code.COMPARISON_INTEGRITY_FAILURE,
                    "candidate comparison failed: " + comparison.detail()
                );
            }

            String comparisonJson = DetectionReferenceBaselineVerificationReports.writeJson(comparison) + "\n";
            String comparisonMarkdown =
                DetectionReferenceBaselineVerificationReports.writeMarkdown(comparison) + "\n";
            byte[] comparisonJsonBytes = comparisonJson.getBytes(StandardCharsets.UTF_8);
            byte[] comparisonMarkdownBytes = comparisonMarkdown.getBytes(StandardCharsets.UTF_8);
            writeNewFile(candidateRoot.resolve(DetectionReferenceBaselineLifecycleSchemas.COMPARISON_JSON_FILE),
                comparisonJsonBytes);
            writeNewFile(candidateRoot.resolve(DetectionReferenceBaselineLifecycleSchemas.COMPARISON_MARKDOWN_FILE),
                comparisonMarkdownBytes);

            String candidateRecord = DetectionReferenceBaselineLifecycleReports.writeCandidateRecord(
                safeCandidateId,
                official.manifest().baselineId(),
                official.manifestSha256(),
                official.evaluationJsonSha256(),
                official.evaluationMarkdownSha256(),
                candidate.manifest().baselineId(),
                candidate.manifestSha256(),
                candidate.evaluationJsonSha256(),
                candidate.evaluationMarkdownSha256(),
                comparison.status().name(),
                TrainingFingerprintHashes.sha256HexBytes(comparisonJsonBytes),
                TrainingFingerprintHashes.sha256HexBytes(comparisonMarkdownBytes),
                comparison.totalDriftEntries()
            ) + "\n";
            byte[] candidateRecordBytes = candidateRecord.getBytes(StandardCharsets.UTF_8);
            writeNewFile(
                candidateRoot.resolve(DetectionReferenceBaselineLifecycleSchemas.CANDIDATE_RECORD_FILE),
                candidateRecordBytes
            );
            completed = true;
            return new DetectionReferenceBaselineLifecycleResult(
                "CANDIDATE_CREATED",
                safeCandidateId,
                "",
                comparison.status(),
                comparison.totalDriftEntries(),
                "candidate created; official baseline unchanged",
                officialRoot,
                candidateRoot,
                null,
                TrainingFingerprintHashes.sha256HexBytes(candidateRecordBytes)
            );
        } catch (IOException | RuntimeException e) {
            if (!completed) {
                deleteRecursively(candidateRoot);
            }
            if (e instanceof DetectionReferenceBaselineLifecycleException lifecycleException) {
                throw lifecycleException;
            }
            if (e instanceof FileAlreadyExistsException) {
                throw new DetectionReferenceBaselineLifecycleException(
                    DetectionReferenceBaselineLifecycleException.Code.CANDIDATE_ALREADY_EXISTS,
                    e.getMessage(),
                    e
                );
            }
            throw e;
        }
    }

    public DetectionReferenceBaselineLifecycleResult approveCandidate(Path officialBaselineDirectory,
                                                                      String candidateId,
                                                                      String rationale,
                                                                      String approver) throws IOException {
        return decide(officialBaselineDirectory, candidateId, rationale, approver,
            DetectionReferenceBaselineDecisionState.APPROVED);
    }

    public DetectionReferenceBaselineLifecycleResult rejectCandidate(Path officialBaselineDirectory,
                                                                     String candidateId,
                                                                     String rationale,
                                                                     String approver) throws IOException {
        return decide(officialBaselineDirectory, candidateId, rationale, approver,
            DetectionReferenceBaselineDecisionState.REJECTED);
    }

    public DetectionReferenceBaselineLifecycleResult promoteCandidate(Path officialBaselineDirectory,
                                                                     String candidateId) throws IOException {
        String safeCandidateId = requireCandidateId(candidateId);
        Path officialRoot = requireOfficialRoot(officialBaselineDirectory);
        DetectionReferenceBaselineLoader.LoadedBaseline official = loadOfficial(officialRoot);
        Path candidateRoot = requireExistingCandidateRoot(officialRoot, safeCandidateId);
        CandidateBundle candidate = loadCandidateBundle(candidateRoot, safeCandidateId);
        assertCandidateArtifactsUnchanged(candidate);
        assertNotStale(official, candidate.record());
        assertComparisonArtifactsBound(officialRoot, candidate);

        DecisionBundle decision = loadDecision(candidateRoot);
        if (decision.state() == DetectionReferenceBaselineDecisionState.REJECTED) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.CANDIDATE_REJECTED,
                "rejected candidates are not promotable; create a new candidate"
            );
        }
        if (decision.state() != DetectionReferenceBaselineDecisionState.APPROVED) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.APPROVAL_REQUIRED,
                "candidate must be approved before promotion"
            );
        }
        assertApprovalBindsCandidate(decision, candidate);

        Path promotionPath = candidateRoot.resolve(DetectionReferenceBaselineLifecycleSchemas.PROMOTION_RECORD_FILE);
        if (Files.exists(promotionPath)) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.ALREADY_PROMOTED,
                "candidate already promoted: " + safeCandidateId
            );
        }

        if (candidate.record().comparisonStatus()
            == DetectionReferenceBaselineVerificationStatus.MATCH
            || candidate.comparison().status() == DetectionReferenceBaselineVerificationStatus.MATCH) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.NO_OP_IDENTICAL,
                "candidate is identical to the official baseline; promotion refused as no-op"
            );
        }

        String historyId = historyIdFor(official);
        Path historyRoot = historyRoot(officialRoot, historyId);
        if (Files.exists(historyRoot)) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.HISTORY_CONFLICT,
                "history destination already exists: " + historyId
            );
        }

        Path historyBaseline = historyRoot.resolve(DetectionReferenceBaselineLifecycleSchemas.BASELINE_SUBDIRECTORY);
        assertNoPublicationTemps(officialRoot);
        Path stagedOfficial = Files.createTempDirectory(
            officialRoot.getParent() == null ? officialRoot : officialRoot.getParent(),
            "detection-baseline-promote-stage-"
        );
        boolean historyPublished = false;
        boolean officialPublished = false;
        try {
            Files.createDirectories(historyBaseline);
            copyBaselineArtifacts(officialRoot, historyBaseline);
            DetectionReferenceBaselineLoader.LoadedBaseline retained = loadBaseline(
                historyBaseline,
                DetectionReferenceBaselineLifecycleException.Code.HISTORY_INTEGRITY_FAILURE
            );
            if (!retained.manifestSha256().equals(official.manifestSha256())
                || !retained.evaluationJsonSha256().equals(official.evaluationJsonSha256())
                || !retained.evaluationMarkdownSha256().equals(official.evaluationMarkdownSha256())) {
                throw new DetectionReferenceBaselineLifecycleException(
                    DetectionReferenceBaselineLifecycleException.Code.HISTORY_INTEGRITY_FAILURE,
                    "history snapshot diverged from current official baseline"
                );
            }

            Files.delete(stagedOfficial);
            Files.createDirectories(stagedOfficial);
            copyBaselineArtifacts(candidate.baselineDirectory(), stagedOfficial);
            DetectionReferenceBaselineLoader.LoadedBaseline staged = loadBaseline(
                stagedOfficial,
                DetectionReferenceBaselineLifecycleException.Code.CANDIDATE_INTEGRITY_FAILURE
            );
            if (!staged.manifestSha256().equals(candidate.baseline().manifestSha256())
                || !staged.evaluationJsonSha256().equals(candidate.baseline().evaluationJsonSha256())
                || !staged.evaluationMarkdownSha256().equals(candidate.baseline().evaluationMarkdownSha256())) {
                throw new DetectionReferenceBaselineLifecycleException(
                    DetectionReferenceBaselineLifecycleException.Code.PUBLICATION_FAILURE,
                    "staged official artifacts diverged from candidate"
                );
            }

            // History is retained before official mutation so a failed publish leaves recoverable evidence.
            String retentionJson = DetectionReferenceBaselineLifecycleReports.writeRetentionRecord(
                historyId,
                retained.manifest().baselineId(),
                retained.manifestSha256(),
                retained.evaluationJsonSha256(),
                retained.evaluationMarkdownSha256(),
                safeCandidateId,
                "",
                "BASELINE_SUPERSEDED"
            ) + "\n";
            byte[] retentionBytes = retentionJson.getBytes(StandardCharsets.UTF_8);
            String retentionSha256 = TrainingFingerprintHashes.sha256HexBytes(retentionBytes);
            writeNewFile(
                historyRoot.resolve(DetectionReferenceBaselineLifecycleSchemas.RETENTION_RECORD_FILE),
                retentionBytes
            );
            historyPublished = true;

            String promotionJson = DetectionReferenceBaselineLifecycleReports.writePromotionRecord(
                safeCandidateId,
                decision.sha256(),
                candidate.record().comparisonJsonSha256(),
                official.manifest().baselineId(),
                official.manifestSha256(),
                official.evaluationJsonSha256(),
                official.evaluationMarkdownSha256(),
                staged.manifest().baselineId(),
                staged.manifestSha256(),
                staged.evaluationJsonSha256(),
                staged.evaluationMarkdownSha256(),
                historyId,
                retentionSha256,
                decision.rationale(),
                decision.approver()
            ) + "\n";
            byte[] promotionBytes = promotionJson.getBytes(StandardCharsets.UTF_8);
            writeNewFile(promotionPath, promotionBytes);

            publishOfficialArtifacts(stagedOfficial, officialRoot);
            officialPublished = true;

            DetectionReferenceBaselineLoader.LoadedBaseline published = loadOfficial(officialRoot);
            if (!published.manifestSha256().equals(staged.manifestSha256())
                || !published.evaluationJsonSha256().equals(staged.evaluationJsonSha256())
                || !published.evaluationMarkdownSha256().equals(staged.evaluationMarkdownSha256())) {
                throw new DetectionReferenceBaselineLifecycleException(
                    DetectionReferenceBaselineLifecycleException.Code.PUBLICATION_FAILURE,
                    "published official baseline diverged after promotion"
                );
            }

            return new DetectionReferenceBaselineLifecycleResult(
                "PROMOTED",
                safeCandidateId,
                historyId,
                candidate.comparison().status(),
                candidate.comparison().totalDriftEntries(),
                "candidate promoted; previous official retained in history",
                officialRoot,
                candidateRoot,
                historyRoot,
                TrainingFingerprintHashes.sha256HexBytes(promotionBytes)
            );
        } catch (IOException | RuntimeException e) {
            if (!officialPublished) {
                // Official untouched or only partially published — attempt restore from history if we already
                // overwrote any official file. publishOfficialArtifacts is all-or-nothing via temp then move.
                if (historyPublished && !Files.exists(historyRoot.resolve(
                    DetectionReferenceBaselineLifecycleSchemas.RETENTION_RECORD_FILE))) {
                    deleteRecursively(historyRoot);
                }
                // If promotion record written but publish failed, leave candidate promotion file only if publish
                // succeeded. Remove orphan promotion record when official unchanged.
                if (Files.exists(promotionPath) && !officialPublished) {
                    Files.deleteIfExists(promotionPath);
                }
            }
            if (e instanceof DetectionReferenceBaselineLifecycleException lifecycleException) {
                throw lifecycleException;
            }
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.PUBLICATION_FAILURE,
                e.getMessage(),
                e
            );
        } finally {
            deleteRecursively(stagedOfficial);
        }
    }

    public DetectionReferenceBaselineLifecycleResult rollback(Path officialBaselineDirectory,
                                                              String historyTargetId,
                                                              String rationale,
                                                              String approver) throws IOException {
        String safeHistoryId = requireHistoryId(historyTargetId);
        String safeRationale = requireGovernanceText("rationale", rationale);
        String safeApprover = requireGovernanceText("approver", approver);
        Path officialRoot = requireOfficialRoot(officialBaselineDirectory);
        DetectionReferenceBaselineLoader.LoadedBaseline official = loadOfficial(officialRoot);

        Path historyRoot = historyRoot(officialRoot, safeHistoryId);
        if (!Files.isDirectory(historyRoot)) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.HISTORY_NOT_FOUND,
                "history target not found: " + safeHistoryId
            );
        }
        Path historyBaseline = historyRoot.resolve(DetectionReferenceBaselineLifecycleSchemas.BASELINE_SUBDIRECTORY);
        DetectionReferenceBaselineLoader.LoadedBaseline target = loadBaseline(
            historyBaseline,
            DetectionReferenceBaselineLifecycleException.Code.HISTORY_INTEGRITY_FAILURE
        );
        validateHistoryRetention(historyRoot, target);

        if (target.manifestSha256().equals(official.manifestSha256())
            && target.evaluationJsonSha256().equals(official.evaluationJsonSha256())
            && target.evaluationMarkdownSha256().equals(official.evaluationMarkdownSha256())) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.NO_OP_IDENTICAL,
                "rollback target is identical to the current official baseline"
            );
        }

        String retainHistoryId = historyIdFor(official);
        if (retainHistoryId.equals(safeHistoryId)) {
            // Distinct identity via content hash prefix already; collision means same official already retained.
            retainHistoryId = retainHistoryId + "-rollback-prior";
            requireHistoryId(retainHistoryId);
        }
        Path retainRoot = historyRoot(officialRoot, retainHistoryId);
        if (Files.exists(retainRoot)) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.HISTORY_CONFLICT,
                "history destination already exists for current official: " + retainHistoryId
            );
        }

        Path stagedOfficial = Files.createTempDirectory(
            officialRoot.getParent() == null ? officialRoot : officialRoot.getParent(),
            "detection-baseline-rollback-stage-"
        );
        Path governanceDir = officialRoot.resolve(DetectionReferenceBaselineLifecycleSchemas.GOVERNANCE_DIRECTORY);
        Path rollbackPath = governanceDir.resolve(
            "rollback-" + retainHistoryId + "-to-" + safeHistoryId + ".json"
        );
        boolean retainPublished = false;
        boolean officialPublished = false;
        try {
            assertNoPublicationTemps(officialRoot);
            Files.createDirectories(retainRoot.resolve(DetectionReferenceBaselineLifecycleSchemas.BASELINE_SUBDIRECTORY));
            copyBaselineArtifacts(
                officialRoot,
                retainRoot.resolve(DetectionReferenceBaselineLifecycleSchemas.BASELINE_SUBDIRECTORY)
            );
            DetectionReferenceBaselineLoader.LoadedBaseline retainedCurrent = loadBaseline(
                retainRoot.resolve(DetectionReferenceBaselineLifecycleSchemas.BASELINE_SUBDIRECTORY),
                DetectionReferenceBaselineLifecycleException.Code.HISTORY_INTEGRITY_FAILURE
            );
            String retentionJson = DetectionReferenceBaselineLifecycleReports.writeRetentionRecord(
                retainHistoryId,
                retainedCurrent.manifest().baselineId(),
                retainedCurrent.manifestSha256(),
                retainedCurrent.evaluationJsonSha256(),
                retainedCurrent.evaluationMarkdownSha256(),
                "",
                "",
                "BASELINE_ROLLED_BACK_PRIOR"
            ) + "\n";
            byte[] retentionBytes = retentionJson.getBytes(StandardCharsets.UTF_8);
            writeNewFile(
                retainRoot.resolve(DetectionReferenceBaselineLifecycleSchemas.RETENTION_RECORD_FILE),
                retentionBytes
            );
            retainPublished = true;

            Files.delete(stagedOfficial);
            Files.createDirectories(stagedOfficial);
            copyBaselineArtifacts(historyBaseline, stagedOfficial);
            DetectionReferenceBaselineLoader.LoadedBaseline staged = loadBaseline(
                stagedOfficial,
                DetectionReferenceBaselineLifecycleException.Code.HISTORY_INTEGRITY_FAILURE
            );
            if (!staged.manifestSha256().equals(target.manifestSha256())) {
                throw new DetectionReferenceBaselineLifecycleException(
                    DetectionReferenceBaselineLifecycleException.Code.PUBLICATION_FAILURE,
                    "staged rollback artifacts diverged from history target"
                );
            }

            Files.createDirectories(governanceDir);
            if (Files.exists(rollbackPath)) {
                throw new DetectionReferenceBaselineLifecycleException(
                    DetectionReferenceBaselineLifecycleException.Code.HISTORY_CONFLICT,
                    "rollback governance record already exists"
                );
            }

            String rollbackJson = DetectionReferenceBaselineLifecycleReports.writeRollbackRecord(
                safeHistoryId,
                target.manifestSha256(),
                official.manifestSha256(),
                official.evaluationJsonSha256(),
                official.evaluationMarkdownSha256(),
                staged.manifestSha256(),
                staged.evaluationJsonSha256(),
                staged.evaluationMarkdownSha256(),
                retainHistoryId,
                TrainingFingerprintHashes.sha256HexBytes(retentionBytes),
                safeRationale,
                safeApprover
            ) + "\n";
            byte[] rollbackBytes = rollbackJson.getBytes(StandardCharsets.UTF_8);
            writeNewFile(rollbackPath, rollbackBytes);

            publishOfficialArtifacts(stagedOfficial, officialRoot);
            officialPublished = true;
            loadOfficial(officialRoot);

            return new DetectionReferenceBaselineLifecycleResult(
                "ROLLBACK_COMPLETE",
                "",
                safeHistoryId,
                null,
                0,
                "historical baseline restored; prior official retained in history",
                officialRoot,
                null,
                historyRoot,
                TrainingFingerprintHashes.sha256HexBytes(rollbackBytes)
            );
        } catch (IOException | RuntimeException e) {
            if (!officialPublished) {
                if (Files.exists(rollbackPath)) {
                    Files.deleteIfExists(rollbackPath);
                }
                if (retainPublished && !officialPublished) {
                    // Keep retained prior snapshot if publish failed after retention — recoverable.
                }
            }
            if (e instanceof DetectionReferenceBaselineLifecycleException lifecycleException) {
                throw lifecycleException;
            }
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.PUBLICATION_FAILURE,
                e.getMessage(),
                e
            );
        } finally {
            deleteRecursively(stagedOfficial);
        }
    }

    public List<String> listHistoryIds(Path officialBaselineDirectory) throws IOException {
        Path officialRoot = requireOfficialRoot(officialBaselineDirectory);
        Path historyParent = officialRoot.resolve(DetectionReferenceBaselineLifecycleSchemas.HISTORY_DIRECTORY);
        if (!Files.isDirectory(historyParent)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        try (var stream = Files.list(historyParent)) {
            stream.filter(Files::isDirectory)
                .map(path -> path.getFileName().toString())
                .sorted()
                .forEach(ids::add);
        }
        return List.copyOf(ids);
    }

    private DetectionReferenceBaselineLifecycleResult decide(Path officialBaselineDirectory,
                                                             String candidateId,
                                                             String rationale,
                                                             String approver,
                                                             DetectionReferenceBaselineDecisionState state)
        throws IOException {
        String safeCandidateId = requireCandidateId(candidateId);
        String safeRationale = requireGovernanceText("rationale", rationale);
        String safeApprover = requireGovernanceText("approver", approver);
        Path officialRoot = requireOfficialRoot(officialBaselineDirectory);
        DetectionReferenceBaselineLoader.LoadedBaseline official = loadOfficial(officialRoot);
        Path candidateRoot = requireExistingCandidateRoot(officialRoot, safeCandidateId);
        CandidateBundle candidate = loadCandidateBundle(candidateRoot, safeCandidateId);
        assertCandidateArtifactsUnchanged(candidate);
        assertNotStale(official, candidate.record());
        assertComparisonArtifactsBound(officialRoot, candidate);

        Path decisionPath = candidateRoot.resolve(DetectionReferenceBaselineLifecycleSchemas.DECISION_RECORD_FILE);
        if (Files.exists(decisionPath)) {
            DecisionBundle existing = loadDecision(candidateRoot);
            if (existing.state() == state
                && existing.rationale().equals(safeRationale)
                && existing.approver().equals(safeApprover)
                && existing.candidateManifestSha256().equals(candidate.baseline().manifestSha256())
                && existing.comparisonJsonSha256().equals(candidate.record().comparisonJsonSha256())
                && existing.sourceOfficialManifestSha256().equals(candidate.record().sourceOfficialManifestSha256())) {
                return new DetectionReferenceBaselineLifecycleResult(
                    state == DetectionReferenceBaselineDecisionState.APPROVED
                        ? "ALREADY_APPROVED"
                        : "ALREADY_REJECTED",
                    safeCandidateId,
                    "",
                    candidate.comparison().status(),
                    candidate.comparison().totalDriftEntries(),
                    "identical decision record already present",
                    officialRoot,
                    candidateRoot,
                    null,
                    existing.sha256()
                );
            }
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.ALREADY_DECIDED,
                "candidate already has a governance decision"
            );
        }

        String decisionJson = DetectionReferenceBaselineLifecycleReports.writeDecisionRecord(
            state,
            safeCandidateId,
            candidate.baseline().manifestSha256(),
            candidate.record().comparisonJsonSha256(),
            candidate.record().sourceOfficialManifestSha256(),
            safeRationale,
            safeApprover
        ) + "\n";
        byte[] decisionBytes = decisionJson.getBytes(StandardCharsets.UTF_8);
        String decisionSha256 = TrainingFingerprintHashes.sha256HexBytes(decisionBytes);
        writeNewFile(decisionPath, decisionBytes);
        writeNewFile(
            candidateRoot.resolve(DetectionReferenceBaselineLifecycleSchemas.DECISION_HASH_FILE),
            (decisionSha256 + "\n").getBytes(StandardCharsets.UTF_8)
        );
        return new DetectionReferenceBaselineLifecycleResult(
            state == DetectionReferenceBaselineDecisionState.APPROVED ? "APPROVED" : "REJECTED",
            safeCandidateId,
            "",
            candidate.comparison().status(),
            candidate.comparison().totalDriftEntries(),
            state == DetectionReferenceBaselineDecisionState.APPROVED
                ? "candidate approved; not promoted"
                : "candidate rejected; not promotable",
            officialRoot,
            candidateRoot,
            null,
            decisionSha256
        );
    }

    private static void assertApprovalBindsCandidate(DecisionBundle decision, CandidateBundle candidate) {
        if (!decision.candidateManifestSha256().equals(candidate.baseline().manifestSha256())
            || !decision.comparisonJsonSha256().equals(candidate.record().comparisonJsonSha256())
            || !decision.sourceOfficialManifestSha256().equals(candidate.record().sourceOfficialManifestSha256())
            || !decision.candidateId().equals(candidate.record().candidateId())) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.GOVERNANCE_INTEGRITY_FAILURE,
                "approval record does not bind the current candidate/comparison/source official hashes"
            );
        }
    }

    private void assertComparisonArtifactsBound(Path officialRoot, CandidateBundle candidate) throws IOException {
        DetectionReferenceBaselineVerificationResult expected =
            verifier.comparePersisted(officialRoot, candidate.baselineDirectory());
        if (expected.status() == DetectionReferenceBaselineVerificationStatus.BASELINE_INTEGRITY_FAILURE
            || expected.status() == DetectionReferenceBaselineVerificationStatus.CURRENT_EVALUATION_FAILURE) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.COMPARISON_INTEGRITY_FAILURE,
                "candidate comparison could not be regenerated: " + expected.detail()
            );
        }
        String expectedJson = DetectionReferenceBaselineVerificationReports.writeJson(expected) + "\n";
        String expectedMarkdown = DetectionReferenceBaselineVerificationReports.writeMarkdown(expected) + "\n";
        byte[] comparisonJson = Files.readAllBytes(
            candidate.candidateDirectory().resolve(DetectionReferenceBaselineLifecycleSchemas.COMPARISON_JSON_FILE)
        );
        byte[] comparisonMarkdown = Files.readAllBytes(
            candidate.candidateDirectory().resolve(DetectionReferenceBaselineLifecycleSchemas.COMPARISON_MARKDOWN_FILE)
        );
        String expectedJsonSha256 =
            TrainingFingerprintHashes.sha256HexBytes(expectedJson.getBytes(StandardCharsets.UTF_8));
        String expectedMarkdownSha256 =
            TrainingFingerprintHashes.sha256HexBytes(expectedMarkdown.getBytes(StandardCharsets.UTF_8));
        if (!expected.equals(candidate.comparison())
            || !expectedJsonSha256.equals(candidate.record().comparisonJsonSha256())
            || !expectedMarkdownSha256.equals(candidate.record().comparisonMarkdownSha256())
            || !TrainingFingerprintHashes.sha256HexBytes(comparisonJson).equals(expectedJsonSha256)
            || !TrainingFingerprintHashes.sha256HexBytes(comparisonMarkdown).equals(expectedMarkdownSha256)
            || candidate.record().comparisonStatus() != expected.status()
            || candidate.record().totalDriftEntries() != expected.totalDriftEntries()) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.COMPARISON_INTEGRITY_FAILURE,
                "candidate comparison artifacts do not match regenerated comparison"
            );
        }
    }

    private static void assertNotStale(DetectionReferenceBaselineLoader.LoadedBaseline official,
                                       CandidateRecord record) {
        if (!official.manifest().baselineId().equals(record.sourceOfficialBaselineId())
            || !official.manifestSha256().equals(record.sourceOfficialManifestSha256())
            || !official.evaluationJsonSha256().equals(record.sourceOfficialEvaluationJsonSha256())
            || !official.evaluationMarkdownSha256().equals(record.sourceOfficialEvaluationMarkdownSha256())) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.CANDIDATE_STALE,
                "candidate source official binding no longer matches the current official baseline"
            );
        }
    }

    private static void assertCandidateArtifactsUnchanged(CandidateBundle candidate) throws IOException {
        byte[] comparisonJson = Files.readAllBytes(
            candidate.candidateDirectory().resolve(DetectionReferenceBaselineLifecycleSchemas.COMPARISON_JSON_FILE)
        );
        byte[] comparisonMarkdown = Files.readAllBytes(
            candidate.candidateDirectory().resolve(DetectionReferenceBaselineLifecycleSchemas.COMPARISON_MARKDOWN_FILE)
        );
        if (!TrainingFingerprintHashes.sha256HexBytes(comparisonJson)
            .equals(candidate.record().comparisonJsonSha256())
            || !TrainingFingerprintHashes.sha256HexBytes(comparisonMarkdown)
            .equals(candidate.record().comparisonMarkdownSha256())) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.COMPARISON_INTEGRITY_FAILURE,
                "candidate comparison artifacts do not match candidate record hashes"
            );
        }
        if (!candidate.baseline().manifestSha256().equals(candidate.record().candidateManifestSha256())
            || !candidate.baseline().evaluationJsonSha256().equals(candidate.record().candidateEvaluationJsonSha256())
            || !candidate.baseline().evaluationMarkdownSha256()
            .equals(candidate.record().candidateEvaluationMarkdownSha256())
            || !candidate.baseline().manifest().baselineId().equals(candidate.record().candidateBaselineId())) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.CANDIDATE_INTEGRITY_FAILURE,
                "candidate baseline artifacts do not match candidate record hashes"
            );
        }
    }

    private DetectionReferenceBaselineLoader.LoadedBaseline loadOfficial(Path officialRoot) {
        return loadBaseline(
            officialRoot,
            DetectionReferenceBaselineLifecycleException.Code.OFFICIAL_INTEGRITY_FAILURE
        );
    }

    private DetectionReferenceBaselineLoader.LoadedBaseline loadBaseline(
        Path directory,
        DetectionReferenceBaselineLifecycleException.Code code
    ) {
        try {
            return loader.load(directory);
        } catch (DetectionReferenceBaselineIntegrityException e) {
            throw new DetectionReferenceBaselineLifecycleException(code, e.getMessage(), e);
        }
    }

    private CandidateBundle loadCandidateBundle(Path candidateRoot, String candidateId) throws IOException {
        Path baselineDir = candidateRoot.resolve(DetectionReferenceBaselineLifecycleSchemas.BASELINE_SUBDIRECTORY);
        DetectionReferenceBaselineLoader.LoadedBaseline baseline = loadBaseline(
            baselineDir,
            DetectionReferenceBaselineLifecycleException.Code.CANDIDATE_INTEGRITY_FAILURE
        );
        Path candidateRecordPath =
            candidateRoot.resolve(DetectionReferenceBaselineLifecycleSchemas.CANDIDATE_RECORD_FILE);
        CandidateRecord record = parseCandidateRecord(Files.readString(candidateRecordPath, StandardCharsets.UTF_8));
        if (!candidateId.equals(record.candidateId())) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.CANDIDATE_INTEGRITY_FAILURE,
                "candidate record id mismatch"
            );
        }
        Path comparisonJson =
            candidateRoot.resolve(DetectionReferenceBaselineLifecycleSchemas.COMPARISON_JSON_FILE);
        DetectionReferenceBaselineVerificationResult comparison;
        try {
            comparison = parseComparison(Files.readString(comparisonJson, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.COMPARISON_INTEGRITY_FAILURE,
                "candidate comparison artifact is invalid: " + e.getMessage(),
                e
            );
        }
        return new CandidateBundle(candidateRoot, baselineDir, baseline, record, comparison);
    }

    private DecisionBundle loadDecision(Path candidateRoot) throws IOException {
        Path decisionPath = candidateRoot.resolve(DetectionReferenceBaselineLifecycleSchemas.DECISION_RECORD_FILE);
        if (!Files.isRegularFile(decisionPath)) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.APPROVAL_REQUIRED,
                "candidate decision record not found"
            );
        }
        byte[] bytes = Files.readAllBytes(decisionPath);
        String actualSha256 = TrainingFingerprintHashes.sha256HexBytes(bytes);
        Path hashPath = candidateRoot.resolve(DetectionReferenceBaselineLifecycleSchemas.DECISION_HASH_FILE);
        if (!Files.isRegularFile(hashPath)) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.GOVERNANCE_INTEGRITY_FAILURE,
                "candidate decision hash receipt missing"
            );
        }
        String expectedSha256 = Files.readString(hashPath, StandardCharsets.UTF_8).strip();
        if (!actualSha256.equals(expectedSha256)) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.GOVERNANCE_INTEGRITY_FAILURE,
                "candidate decision record does not match hash receipt"
            );
        }
        Map<String, Object> root = DeterministicJson.requireObject(
            DeterministicJson.parse(new String(bytes, StandardCharsets.UTF_8).stripTrailing()),
            "decision"
        );
        requireExact(
            "governanceSchemaVersion",
            DeterministicJson.requireString(root, "governanceSchemaVersion"),
            DetectionReferenceBaselineLifecycleSchemas.GOVERNANCE_SCHEMA_VERSION
        );
        requireExact(
            "recordKind",
            DeterministicJson.requireString(root, "recordKind"),
            DetectionReferenceBaselineLifecycleSchemas.DECISION_RECORD_KIND
        );
        DetectionReferenceBaselineDecisionState state = DetectionReferenceBaselineDecisionState.valueOf(
            DeterministicJson.requireString(root, "decision")
        );
        return new DecisionBundle(
            state,
            DeterministicJson.requireString(root, "candidateId"),
            DeterministicJson.requireString(root, "candidateManifestSha256"),
            DeterministicJson.requireString(root, "comparisonJsonSha256"),
            DeterministicJson.requireString(root, "sourceOfficialManifestSha256"),
            DeterministicJson.requireString(root, "rationale"),
            DeterministicJson.requireString(root, "approver"),
            actualSha256
        );
    }

    private static void validateHistoryRetention(Path historyRoot,
                                                 DetectionReferenceBaselineLoader.LoadedBaseline target)
        throws IOException {
        Path retentionPath = historyRoot.resolve(DetectionReferenceBaselineLifecycleSchemas.RETENTION_RECORD_FILE);
        if (!Files.isRegularFile(retentionPath)) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.HISTORY_INTEGRITY_FAILURE,
                "history retention record missing"
            );
        }
        Map<String, Object> root = DeterministicJson.requireObject(
            DeterministicJson.parse(Files.readString(retentionPath, StandardCharsets.UTF_8).stripTrailing()),
            "retention"
        );
        requireExact(
            "governanceSchemaVersion",
            DeterministicJson.requireString(root, "governanceSchemaVersion"),
            DetectionReferenceBaselineLifecycleSchemas.GOVERNANCE_SCHEMA_VERSION
        );
        requireExact(
            "recordKind",
            DeterministicJson.requireString(root, "recordKind"),
            DetectionReferenceBaselineLifecycleSchemas.RETENTION_RECORD_KIND
        );
        if (!target.manifest().baselineId().equals(DeterministicJson.requireString(root, "retainedBaselineId"))
            || !target.manifestSha256().equals(DeterministicJson.requireString(root, "retainedManifestSha256"))
            || !target.evaluationJsonSha256()
            .equals(DeterministicJson.requireString(root, "retainedEvaluationJsonSha256"))
            || !target.evaluationMarkdownSha256()
            .equals(DeterministicJson.requireString(root, "retainedEvaluationMarkdownSha256"))) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.HISTORY_INTEGRITY_FAILURE,
                "history retention record does not match retained baseline artifacts"
            );
        }
    }

    private static CandidateRecord parseCandidateRecord(String text) {
        Map<String, Object> root = DeterministicJson.requireObject(
            DeterministicJson.parse(text.stripTrailing()),
            "candidate"
        );
        requireExact(
            "lifecycleSchemaVersion",
            DeterministicJson.requireString(root, "lifecycleSchemaVersion"),
            DetectionReferenceBaselineLifecycleSchemas.LIFECYCLE_SCHEMA_VERSION
        );
        requireExact(
            "recordKind",
            DeterministicJson.requireString(root, "recordKind"),
            DetectionReferenceBaselineLifecycleSchemas.CANDIDATE_RECORD_KIND
        );
        return new CandidateRecord(
            DeterministicJson.requireString(root, "candidateId"),
            DeterministicJson.requireString(root, "sourceOfficialBaselineId"),
            DeterministicJson.requireString(root, "sourceOfficialManifestSha256"),
            DeterministicJson.requireString(root, "sourceOfficialEvaluationJsonSha256"),
            DeterministicJson.requireString(root, "sourceOfficialEvaluationMarkdownSha256"),
            DeterministicJson.requireString(root, "candidateBaselineId"),
            DeterministicJson.requireString(root, "candidateManifestSha256"),
            DeterministicJson.requireString(root, "candidateEvaluationJsonSha256"),
            DeterministicJson.requireString(root, "candidateEvaluationMarkdownSha256"),
            DetectionReferenceBaselineVerificationStatus.valueOf(
                DeterministicJson.requireString(root, "comparisonStatus")
            ),
            DeterministicJson.requireString(root, "comparisonJsonSha256"),
            DeterministicJson.requireString(root, "comparisonMarkdownSha256"),
            DeterministicJson.requireInt(root, "totalDriftEntries")
        );
    }

    private static DetectionReferenceBaselineVerificationResult parseComparison(String text) {
        Map<String, Object> root = DeterministicJson.requireObject(
            DeterministicJson.parse(text.stripTrailing()),
            "comparison"
        );
        DetectionReferenceBaselineVerificationStatus status =
            DetectionReferenceBaselineVerificationStatus.valueOf(DeterministicJson.requireString(root, "status"));
        List<Object> entriesRaw = DeterministicJson.requireArray(root.get("driftEntries"), "driftEntries");
        List<DetectionReferenceBaselineDriftEntry> entries = new ArrayList<>();
        for (Object entryObj : entriesRaw) {
            Map<String, Object> entry = DeterministicJson.requireObject(entryObj, "driftEntry");
            entries.add(new DetectionReferenceBaselineDriftEntry(
                DetectionReferenceBaselineDriftCategory.valueOf(DeterministicJson.requireString(entry, "category")),
                DeterministicJson.requireString(entry, "field"),
                DeterministicJson.requireString(entry, "baselineValue"),
                DeterministicJson.requireString(entry, "currentValue")
            ));
        }
        return new DetectionReferenceBaselineVerificationResult(
            DeterministicJson.requireString(root, "verificationSchemaVersion"),
            DeterministicJson.requireString(root, "reportKind"),
            status,
            DeterministicJson.requireString(root, "baselineId"),
            DeterministicJson.requireString(root, "baselineSchemaVersion"),
            DeterministicJson.requireString(root, "baselineManifestSha256"),
            DeterministicJson.requireString(root, "baselineEvaluationJsonSha256"),
            DeterministicJson.requireString(root, "baselineEvaluationMarkdownSha256"),
            DeterministicJson.requireString(root, "currentEvaluationJsonSha256"),
            DeterministicJson.requireString(root, "currentEvaluationMarkdownSha256"),
            DeterministicJson.requireBoolean(root, "evaluationJsonBytesEqual"),
            DeterministicJson.requireBoolean(root, "evaluationMarkdownBytesEqual"),
            DeterministicJson.requireString(root, "detail"),
            DetectionReferenceBaselineVerificationResult.sorted(entries)
        );
    }

    private static Path requireOfficialRoot(Path officialBaselineDirectory) {
        Path root = Objects.requireNonNull(officialBaselineDirectory, "officialBaselineDirectory")
            .toAbsolutePath()
            .normalize();
        if (!Files.isDirectory(root)) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.OFFICIAL_INTEGRITY_FAILURE,
                "official baseline directory does not exist: " + root
            );
        }
        if (Files.isSymbolicLink(root)) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.OFFICIAL_INTEGRITY_FAILURE,
                "official baseline directory must not be a symbolic link"
            );
        }
        return root;
    }

    private static Path candidateRoot(Path officialRoot, String candidateId) {
        Path root = officialRoot
            .resolve(DetectionReferenceBaselineLifecycleSchemas.CANDIDATES_DIRECTORY)
            .resolve(candidateId)
            .normalize();
        if (!root.startsWith(officialRoot.resolve(DetectionReferenceBaselineLifecycleSchemas.CANDIDATES_DIRECTORY))) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.INVALID_INPUT,
                "candidate path escapes candidates directory"
            );
        }
        return root;
    }

    private static Path historyRoot(Path officialRoot, String historyId) {
        Path root = officialRoot
            .resolve(DetectionReferenceBaselineLifecycleSchemas.HISTORY_DIRECTORY)
            .resolve(historyId)
            .normalize();
        if (!root.startsWith(officialRoot.resolve(DetectionReferenceBaselineLifecycleSchemas.HISTORY_DIRECTORY))) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.INVALID_INPUT,
                "history path escapes history directory"
            );
        }
        return root;
    }

    private static Path requireExistingCandidateRoot(Path officialRoot, String candidateId) {
        Path root = candidateRoot(officialRoot, candidateId);
        if (!Files.isDirectory(root)) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.CANDIDATE_NOT_FOUND,
                "candidate not found: " + candidateId
            );
        }
        return root;
    }

    private static String requireCandidateId(String candidateId) {
        if (candidateId == null || !DetectionReferenceBaselineLifecycleSchemas.CANDIDATE_ID_PATTERN
            .matcher(candidateId).matches()) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.INVALID_INPUT,
                "candidateId must match " + DetectionReferenceBaselineLifecycleSchemas.CANDIDATE_ID_PATTERN.pattern()
            );
        }
        if (candidateId.contains("..") || candidateId.contains("/") || candidateId.contains("\\")) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.INVALID_INPUT,
                "candidateId must not contain path elements"
            );
        }
        return candidateId;
    }

    private static String requireHistoryId(String historyId) {
        if (historyId == null || !DetectionReferenceBaselineLifecycleSchemas.HISTORY_ID_PATTERN
            .matcher(historyId).matches()) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.INVALID_INPUT,
                "historyId must match " + DetectionReferenceBaselineLifecycleSchemas.HISTORY_ID_PATTERN.pattern()
            );
        }
        if (historyId.contains("..") || historyId.contains("/") || historyId.contains("\\")) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.INVALID_INPUT,
                "historyId must not contain path elements"
            );
        }
        return historyId;
    }

    private static String requireGovernanceText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.INVALID_INPUT,
                field + " is required"
            );
        }
        String trimmed = value.trim();
        if (trimmed.length() > 2000) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.INVALID_INPUT,
                field + " exceeds maximum length"
            );
        }
        return trimmed;
    }

    private static String historyIdFor(DetectionReferenceBaselineLoader.LoadedBaseline official) {
        return official.manifest().baselineId() + "-" + official.manifestSha256().substring(0, 16);
    }

    private static void copyBaselineArtifacts(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        for (String name : List.of(
            DetectionReferenceBaselineSchemas.MANIFEST_FILE_NAME,
            DetectionEvaluationEvidenceWriter.JSON_FILE_NAME,
            DetectionEvaluationEvidenceWriter.MARKDOWN_FILE_NAME
        )) {
            Path from = source.resolve(name);
            Path to = target.resolve(name);
            if (Files.isSymbolicLink(from)) {
                throw new DetectionReferenceBaselineLifecycleException(
                    DetectionReferenceBaselineLifecycleException.Code.PUBLICATION_FAILURE,
                    "refusing to copy symlink artifact: " + name
                );
            }
            Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    /**
     * Publishes staged baseline artifacts into the official directory without deleting
     * lifecycle sibling directories. Each file is written to a temp sibling then moved into place.
     * If a move fails mid-way, previously moved files may already be updated; history snapshot
     * remains the recovery source.
     */
    private static void publishOfficialArtifacts(Path stagedOfficial, Path officialRoot) throws IOException {
        List<String> names = List.of(
            DetectionReferenceBaselineSchemas.MANIFEST_FILE_NAME,
            DetectionEvaluationEvidenceWriter.JSON_FILE_NAME,
            DetectionEvaluationEvidenceWriter.MARKDOWN_FILE_NAME
        );
        List<Path> temps = new ArrayList<>();
        List<Path> backups = new ArrayList<>();
        try {
            for (String name : names) {
                Path staged = stagedOfficial.resolve(name);
                Path temp = officialRoot.resolve(name + ".promoting");
                Path backup = officialRoot.resolve(name + ".promoting-backup");
                if (Files.exists(temp)) {
                    throw new DetectionReferenceBaselineLifecycleException(
                        DetectionReferenceBaselineLifecycleException.Code.PUBLICATION_FAILURE,
                        "promotion temp artifact already exists: " + temp.getFileName()
                    );
                }
                if (Files.exists(backup)) {
                    throw new DetectionReferenceBaselineLifecycleException(
                        DetectionReferenceBaselineLifecycleException.Code.PUBLICATION_FAILURE,
                        "promotion backup artifact already exists: " + backup.getFileName()
                    );
                }
                Files.copy(staged, temp, StandardCopyOption.COPY_ATTRIBUTES);
                Files.copy(officialRoot.resolve(name), backup, StandardCopyOption.COPY_ATTRIBUTES);
                temps.add(temp);
                backups.add(backup);
            }
            for (int i = 0; i < names.size(); i++) {
                Path temp = temps.get(i);
                Path destination = officialRoot.resolve(names.get(i));
                try {
                    Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            temps.clear();
            for (Path backup : backups) {
                Files.deleteIfExists(backup);
            }
            backups.clear();
        } catch (IOException | RuntimeException e) {
            IOException restoreFailure = null;
            for (int i = 0; i < backups.size(); i++) {
                Path backup = backups.get(i);
                if (Files.exists(backup)) {
                    Path destination = officialRoot.resolve(names.get(i));
                    try {
                        Files.move(backup, destination, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException restoreError) {
                        if (restoreFailure == null) {
                            restoreFailure = restoreError;
                        } else {
                            restoreFailure.addSuppressed(restoreError);
                        }
                    }
                }
            }
            if (restoreFailure != null) {
                e.addSuppressed(restoreFailure);
            }
            throw e;
        } finally {
            for (Path temp : temps) {
                Files.deleteIfExists(temp);
            }
            for (Path backup : backups) {
                Files.deleteIfExists(backup);
            }
        }
    }

    private static void assertNoPublicationTemps(Path officialRoot) {
        for (String name : List.of(
            DetectionReferenceBaselineSchemas.MANIFEST_FILE_NAME,
            DetectionEvaluationEvidenceWriter.JSON_FILE_NAME,
            DetectionEvaluationEvidenceWriter.MARKDOWN_FILE_NAME
        )) {
            for (String suffix : List.of(".promoting", ".promoting-backup")) {
                Path temp = officialRoot.resolve(name + suffix);
                if (Files.exists(temp)) {
                    throw new DetectionReferenceBaselineLifecycleException(
                        DetectionReferenceBaselineLifecycleException.Code.PUBLICATION_FAILURE,
                        "publication temp artifact already exists: " + temp.getFileName()
                    );
                }
            }
        }
    }

    private static void requireExact(String field, String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new DetectionReferenceBaselineLifecycleException(
                DetectionReferenceBaselineLifecycleException.Code.GOVERNANCE_INTEGRITY_FAILURE,
                field + " must be " + expected
            );
        }
    }

    private static void writeNewFile(Path path, byte[] bytes) throws IOException {
        Files.write(
            path,
            bytes,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        );
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                .forEach(candidate -> {
                    try {
                        Files.deleteIfExists(candidate);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }

    private record CandidateRecord(
        String candidateId,
        String sourceOfficialBaselineId,
        String sourceOfficialManifestSha256,
        String sourceOfficialEvaluationJsonSha256,
        String sourceOfficialEvaluationMarkdownSha256,
        String candidateBaselineId,
        String candidateManifestSha256,
        String candidateEvaluationJsonSha256,
        String candidateEvaluationMarkdownSha256,
        DetectionReferenceBaselineVerificationStatus comparisonStatus,
        String comparisonJsonSha256,
        String comparisonMarkdownSha256,
        int totalDriftEntries
    ) {
    }

    private record CandidateBundle(
        Path candidateDirectory,
        Path baselineDirectory,
        DetectionReferenceBaselineLoader.LoadedBaseline baseline,
        CandidateRecord record,
        DetectionReferenceBaselineVerificationResult comparison
    ) {
    }

    private record DecisionBundle(
        DetectionReferenceBaselineDecisionState state,
        String candidateId,
        String candidateManifestSha256,
        String comparisonJsonSha256,
        String sourceOfficialManifestSha256,
        String rationale,
        String approver,
        String sha256
    ) {
    }
}
