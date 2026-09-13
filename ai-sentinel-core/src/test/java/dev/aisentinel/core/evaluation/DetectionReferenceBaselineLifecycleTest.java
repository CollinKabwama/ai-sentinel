package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.dataset.reference.ReferenceDatasetAnnotations;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetGenerator;
import dev.aisentinel.core.replay.ReplayConfiguration;
import dev.aisentinel.distributed.training.TrainingFingerprintHashes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lifecycle and governance coverage for the Official Detection Reference Baseline.
 * Uses temporary lifecycle roots only — never promotes the tracked repository baseline.
 */
class DetectionReferenceBaselineLifecycleTest {

    @TempDir
    Path tempDir;

    @Test
    void promotionRollbackE2ERetainsHistoryAndLineage() throws Exception {
        Path root = newLifecycleRoot("e2e");
        DetectionReferenceBaselineLifecycle lifecycle = new DetectionReferenceBaselineLifecycle();

        DetectionReferenceBaselineLifecycleResult created =
            lifecycle.createCandidate(root, "cand-b", driftedAnnotationConfig("ann-e2e"));
        assertThat(created.action()).isEqualTo("CANDIDATE_CREATED");
        assertThat(created.comparisonStatus()).isEqualTo(DetectionReferenceBaselineVerificationStatus.DRIFT_DETECTED);
        assertThat(created.driftEntries()).isGreaterThan(0);

        byte[] officialBefore = Files.readAllBytes(root.resolve("manifest.json"));

        DetectionReferenceBaselineLifecycleResult approved = lifecycle.approveCandidate(
            root,
            "cand-b",
            "intentional annotation correction for lifecycle test",
            "reviewer-test"
        );
        assertThat(approved.action()).isEqualTo("APPROVED");

        DetectionReferenceBaselineLifecycleResult promoted = lifecycle.promoteCandidate(root, "cand-b");
        assertThat(promoted.action()).isEqualTo("PROMOTED");
        assertThat(promoted.historyId()).isNotBlank();
        assertThat(Files.readAllBytes(root.resolve("manifest.json"))).isNotEqualTo(officialBefore);

        Path historyBaseline = root.resolve("history").resolve(promoted.historyId()).resolve("baseline");
        assertThat(Files.readAllBytes(historyBaseline.resolve("manifest.json"))).containsExactly(officialBefore);
        assertThat(lifecycle.listHistoryIds(root)).contains(promoted.historyId());

        DetectionReferenceBaselineLifecycleResult rolled = lifecycle.rollback(
            root,
            promoted.historyId(),
            "restore prior official after lifecycle exercise",
            "reviewer-test"
        );
        assertThat(rolled.action()).isEqualTo("ROLLBACK_COMPLETE");
        assertThat(Files.readAllBytes(root.resolve("manifest.json"))).containsExactly(officialBefore);
        assertThat(lifecycle.listHistoryIds(root).size()).isGreaterThanOrEqualTo(2);
        assertThat(Files.exists(root.resolve("candidates").resolve("cand-b").resolve("promotion.json"))).isTrue();
    }

    @Test
    void unapprovedAndRejectedCandidatesCannotBePromoted() throws Exception {
        Path root = newLifecycleRoot("gates");
        DetectionReferenceBaselineLifecycle lifecycle = new DetectionReferenceBaselineLifecycle();
        lifecycle.createCandidate(root, "cand-unapproved", driftedAnnotationConfig("ann-unapproved"));
        byte[] official = Files.readAllBytes(root.resolve("manifest.json"));

        assertThatThrownBy(() -> lifecycle.promoteCandidate(root, "cand-unapproved"))
            .isInstanceOf(DetectionReferenceBaselineLifecycleException.class)
            .extracting(ex -> ((DetectionReferenceBaselineLifecycleException) ex).code())
            .isEqualTo(DetectionReferenceBaselineLifecycleException.Code.APPROVAL_REQUIRED);
        assertThat(Files.readAllBytes(root.resolve("manifest.json"))).containsExactly(official);

        lifecycle.createCandidate(root, "cand-rejected", driftedAnnotationConfig("ann-rejected"));
        lifecycle.rejectCandidate(root, "cand-rejected", "not accepted", "reviewer-test");
        assertThatThrownBy(() -> lifecycle.promoteCandidate(root, "cand-rejected"))
            .isInstanceOf(DetectionReferenceBaselineLifecycleException.class)
            .extracting(ex -> ((DetectionReferenceBaselineLifecycleException) ex).code())
            .isEqualTo(DetectionReferenceBaselineLifecycleException.Code.CANDIDATE_REJECTED);
        assertThat(Files.readAllBytes(root.resolve("manifest.json"))).containsExactly(official);
    }

    @Test
    void staleCandidateCannotBeApprovedOrPromoted() throws Exception {
        Path root = newLifecycleRoot("stale");
        DetectionReferenceBaselineLifecycle lifecycle = new DetectionReferenceBaselineLifecycle();
        lifecycle.createCandidate(root, "cand-stale", driftedAnnotationConfig("ann-stale-a"));
        lifecycle.approveCandidate(root, "cand-stale", "first change", "reviewer-test");
        lifecycle.promoteCandidate(root, "cand-stale");

        // Candidate bound to previous official A; current official is B.
        lifecycle.createCandidate(root, "cand-late", driftedAnnotationConfig("ann-stale-b"));
        // Force stale by rewriting source binding after creation against current B — simulate by
        // creating against A via copying old history baseline as temporary official side-channel:
        // Instead: create candidate before promotion was already done for cand-late against B.
        // Make it stale by restoring official from history without going through lifecycle binding update.
        Path historyId = Path.of(lifecycle.listHistoryIds(root).get(0));
        Path oldOfficial = root.resolve("history").resolve(historyId.toString()).resolve("baseline");
        // Create a second lifecycle root with old official, create candidate, then swap official to B.
        Path root2 = newLifecycleRoot("stale2");
        copyBaselineFiles(oldOfficial, root2);
        lifecycle.createCandidate(root2, "cand-against-a", driftedAnnotationConfig("ann-against-a"));
        // Replace official with B (current from root)
        copyBaselineFiles(root, root2);
        assertThatThrownBy(() -> lifecycle.approveCandidate(
            root2, "cand-against-a", "stale attempt", "reviewer-test"
        )).isInstanceOf(DetectionReferenceBaselineLifecycleException.class)
            .extracting(ex -> ((DetectionReferenceBaselineLifecycleException) ex).code())
            .isEqualTo(DetectionReferenceBaselineLifecycleException.Code.CANDIDATE_STALE);

        lifecycle.approveCandidate(root, "cand-late", "ok on current", "reviewer-test");
        // Rebuild stale approved candidate path: approve on root2 fails; for promote stale:
        Path root3 = newLifecycleRoot("stale3");
        copyBaselineFiles(oldOfficial, root3);
        lifecycle.createCandidate(root3, "cand-promote-stale", driftedAnnotationConfig("ann-ps"));
        lifecycle.approveCandidate(root3, "cand-promote-stale", "approved against A", "reviewer-test");
        copyBaselineFiles(root, root3);
        assertThatThrownBy(() -> lifecycle.promoteCandidate(root3, "cand-promote-stale"))
            .isInstanceOf(DetectionReferenceBaselineLifecycleException.class)
            .extracting(ex -> ((DetectionReferenceBaselineLifecycleException) ex).code())
            .isEqualTo(DetectionReferenceBaselineLifecycleException.Code.CANDIDATE_STALE);
    }

    @Test
    void identicalCandidatePromotionIsNoOp() throws Exception {
        Path root = newLifecycleRoot("noop");
        DetectionReferenceBaselineLifecycle lifecycle = new DetectionReferenceBaselineLifecycle();
        DetectionReferenceBaselineLifecycleResult created =
            lifecycle.createCandidate(root, "cand-identical");
        assertThat(created.comparisonStatus()).isEqualTo(DetectionReferenceBaselineVerificationStatus.MATCH);
        lifecycle.approveCandidate(root, "cand-identical", "should still refuse promote", "reviewer-test");
        assertThatThrownBy(() -> lifecycle.promoteCandidate(root, "cand-identical"))
            .isInstanceOf(DetectionReferenceBaselineLifecycleException.class)
            .extracting(ex -> ((DetectionReferenceBaselineLifecycleException) ex).code())
            .isEqualTo(DetectionReferenceBaselineLifecycleException.Code.NO_OP_IDENTICAL);
        assertThat(lifecycle.listHistoryIds(root)).isEmpty();
    }

    @Test
    void tamperedCandidateComparisonApprovalAndHistoryAreRejected() throws Exception {
        Path root = newLifecycleRoot("tamper");
        DetectionReferenceBaselineLifecycle lifecycle = new DetectionReferenceBaselineLifecycle();
        lifecycle.createCandidate(root, "cand-tamper", driftedAnnotationConfig("ann-tamper"));

        Path candidateBaseline = root.resolve("candidates/cand-tamper/baseline/evaluation.json");
        Files.writeString(candidateBaseline, Files.readString(candidateBaseline) + " ", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> lifecycle.approveCandidate(root, "cand-tamper", "x", "y"))
            .isInstanceOf(DetectionReferenceBaselineLifecycleException.class);

        Path root2 = newLifecycleRoot("tamper2");
        lifecycle.createCandidate(root2, "cand-cmp", driftedAnnotationConfig("ann-cmp"));
        Path comparison = root2.resolve("candidates/cand-cmp/comparison.json");
        Files.writeString(comparison, Files.readString(comparison).replace("DRIFT_DETECTED", "MATCH"),
            StandardCharsets.UTF_8);
        assertThatThrownBy(() -> lifecycle.approveCandidate(root2, "cand-cmp", "x", "y"))
            .isInstanceOf(DetectionReferenceBaselineLifecycleException.class);

        Path root3 = newLifecycleRoot("tamper3");
        lifecycle.createCandidate(root3, "cand-appr", driftedAnnotationConfig("ann-appr"));
        lifecycle.approveCandidate(root3, "cand-appr", "ok", "reviewer-test");
        Path decision = root3.resolve("candidates/cand-appr/decision.json");
        Files.writeString(decision, Files.readString(decision).replace("reviewer-test", "tampered"),
            StandardCharsets.UTF_8);
        assertThatThrownBy(() -> lifecycle.promoteCandidate(root3, "cand-appr"))
            .isInstanceOf(DetectionReferenceBaselineLifecycleException.class);

        Path root4 = newLifecycleRoot("tamper4");
        lifecycle.createCandidate(root4, "cand-hist", driftedAnnotationConfig("ann-hist"));
        lifecycle.approveCandidate(root4, "cand-hist", "ok", "reviewer-test");
        DetectionReferenceBaselineLifecycleResult promoted = lifecycle.promoteCandidate(root4, "cand-hist");
        Path histEval = root4.resolve("history").resolve(promoted.historyId())
            .resolve("baseline/evaluation.json");
        Files.writeString(histEval, Files.readString(histEval) + " ", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> lifecycle.rollback(
            root4, promoted.historyId(), "rollback", "reviewer-test"
        )).isInstanceOf(DetectionReferenceBaselineLifecycleException.class);
    }

    @Test
    void comparisonTamperIsRejectedEvenWhenCandidateRecordIsUpdatedToMatch() throws Exception {
        Path root = newLifecycleRoot("cmp-bound");
        DetectionReferenceBaselineLifecycle lifecycle = new DetectionReferenceBaselineLifecycle();
        lifecycle.createCandidate(root, "cand-bound", driftedAnnotationConfig("ann-bound"));

        Path candidateRoot = root.resolve("candidates/cand-bound");
        Path comparison = candidateRoot.resolve("comparison.json");
        String comparisonText = Files.readString(comparison, StandardCharsets.UTF_8);
        String tamperedComparison = comparisonText.replaceFirst("ANNOTATION_PROVENANCE", "DATASET_PROVENANCE");
        assertThat(tamperedComparison).isNotEqualTo(comparisonText);
        Files.writeString(comparison, tamperedComparison, StandardCharsets.UTF_8);

        Path candidateRecord = candidateRoot.resolve("candidate.json");
        String recordText = Files.readString(candidateRecord, StandardCharsets.UTF_8);
        String tamperedComparisonSha256 =
            TrainingFingerprintHashes.sha256HexBytes(Files.readAllBytes(comparison));
        String rewrittenRecord = recordText.replaceFirst(
            "\"comparisonJsonSha256\":\"[0-9a-f]{64}\"",
            "\"comparisonJsonSha256\":\"" + tamperedComparisonSha256 + "\""
        );
        assertThat(rewrittenRecord).isNotEqualTo(recordText);
        Files.writeString(candidateRecord, rewrittenRecord, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> lifecycle.approveCandidate(root, "cand-bound", "tampered", "reviewer-test"))
            .isInstanceOf(DetectionReferenceBaselineLifecycleException.class)
            .extracting(ex -> ((DetectionReferenceBaselineLifecycleException) ex).code())
            .isEqualTo(DetectionReferenceBaselineLifecycleException.Code.COMPARISON_INTEGRITY_FAILURE);
    }

    @Test
    void decisionCandidateIdTamperIsRejectedEvenWhenReceiptIsUpdated() throws Exception {
        Path root = newLifecycleRoot("decision-bound");
        DetectionReferenceBaselineLifecycle lifecycle = new DetectionReferenceBaselineLifecycle();
        lifecycle.createCandidate(root, "cand-decision", driftedAnnotationConfig("ann-decision"));
        lifecycle.approveCandidate(root, "cand-decision", "approve candidate", "reviewer-test");

        Path candidateRoot = root.resolve("candidates/cand-decision");
        Path decision = candidateRoot.resolve("decision.json");
        String decisionText = Files.readString(decision, StandardCharsets.UTF_8);
        String tamperedDecision = decisionText.replace("\"candidateId\":\"cand-decision\"", "\"candidateId\":\"other\"");
        assertThat(tamperedDecision).isNotEqualTo(decisionText);
        Files.writeString(decision, tamperedDecision, StandardCharsets.UTF_8);
        Files.writeString(
            candidateRoot.resolve("decision.sha256"),
            TrainingFingerprintHashes.sha256HexBytes(Files.readAllBytes(decision)) + "\n",
            StandardCharsets.UTF_8
        );

        assertThatThrownBy(() -> lifecycle.promoteCandidate(root, "cand-decision"))
            .isInstanceOf(DetectionReferenceBaselineLifecycleException.class)
            .extracting(ex -> ((DetectionReferenceBaselineLifecycleException) ex).code())
            .isEqualTo(DetectionReferenceBaselineLifecycleException.Code.GOVERNANCE_INTEGRITY_FAILURE);
    }

    @Test
    void candidateCollisionAndDecisionCollisionAreRefused() throws Exception {
        Path root = newLifecycleRoot("collision");
        DetectionReferenceBaselineLifecycle lifecycle = new DetectionReferenceBaselineLifecycle();
        lifecycle.createCandidate(root, "cand-x", driftedAnnotationConfig("ann-x"));
        assertThatThrownBy(() -> lifecycle.createCandidate(root, "cand-x", driftedAnnotationConfig("ann-x2")))
            .isInstanceOf(DetectionReferenceBaselineLifecycleException.class)
            .extracting(ex -> ((DetectionReferenceBaselineLifecycleException) ex).code())
            .isEqualTo(DetectionReferenceBaselineLifecycleException.Code.CANDIDATE_ALREADY_EXISTS);

        lifecycle.approveCandidate(root, "cand-x", "first", "reviewer-test");
        assertThatThrownBy(() -> lifecycle.approveCandidate(root, "cand-x", "second", "reviewer-test"))
            .isInstanceOf(DetectionReferenceBaselineLifecycleException.class)
            .extracting(ex -> ((DetectionReferenceBaselineLifecycleException) ex).code())
            .isEqualTo(DetectionReferenceBaselineLifecycleException.Code.ALREADY_DECIDED);
    }

    @Test
    void candidateAndApprovalRecordsAreDeterministicAndPrivate() throws Exception {
        Path rootA = newLifecycleRoot("det-a");
        Path rootB = newLifecycleRoot("det-b");
        DetectionReferenceBaselineLifecycle lifecycle = new DetectionReferenceBaselineLifecycle();
        DetectionReferenceBaselineConfiguration config = driftedAnnotationConfig("ann-det");

        lifecycle.createCandidate(rootA, "cand-det", config);
        lifecycle.createCandidate(rootB, "cand-det", config);
        assertByteIdentical(
            rootA.resolve("candidates/cand-det/baseline"),
            rootB.resolve("candidates/cand-det/baseline")
        );
        assertThat(Files.readAllBytes(rootA.resolve("candidates/cand-det/candidate.json")))
            .containsExactly(Files.readAllBytes(rootB.resolve("candidates/cand-det/candidate.json")));
        assertThat(Files.readAllBytes(rootA.resolve("candidates/cand-det/comparison.json")))
            .containsExactly(Files.readAllBytes(rootB.resolve("candidates/cand-det/comparison.json")));

        lifecycle.approveCandidate(rootA, "cand-det", "same rationale", "approver-1");
        lifecycle.approveCandidate(rootB, "cand-det", "same rationale", "approver-1");
        assertThat(Files.readAllBytes(rootA.resolve("candidates/cand-det/decision.json")))
            .containsExactly(Files.readAllBytes(rootB.resolve("candidates/cand-det/decision.json")));

        String joined = Files.readString(rootA.resolve("candidates/cand-det/candidate.json"), StandardCharsets.UTF_8)
            + Files.readString(rootA.resolve("candidates/cand-det/decision.json"), StandardCharsets.UTF_8)
            + Files.readString(rootA.resolve("candidates/cand-det/comparison.json"), StandardCharsets.UTF_8);
        assertThat(joined).doesNotContain(tempDir.toString());
        assertThat(joined).doesNotContain("Authorization");
        assertThat(joined).doesNotContain("FeatureSnapshot");
        assertThat(joined.toLowerCase()).doesNotContain("cookie");
    }

    @Test
    void promotionAndRollbackRecordsAreDeterministic() throws Exception {
        Path rootA = newLifecycleRoot("gov-det-a");
        Path rootB = newLifecycleRoot("gov-det-b");
        DetectionReferenceBaselineLifecycle lifecycle = new DetectionReferenceBaselineLifecycle();
        DetectionReferenceBaselineConfiguration config = driftedAnnotationConfig("ann-gov-det");

        lifecycle.createCandidate(rootA, "cand-gov", config);
        lifecycle.createCandidate(rootB, "cand-gov", config);
        lifecycle.approveCandidate(rootA, "cand-gov", "same rationale", "approver-1");
        lifecycle.approveCandidate(rootB, "cand-gov", "same rationale", "approver-1");

        DetectionReferenceBaselineLifecycleResult promotedA = lifecycle.promoteCandidate(rootA, "cand-gov");
        DetectionReferenceBaselineLifecycleResult promotedB = lifecycle.promoteCandidate(rootB, "cand-gov");
        assertThat(promotedA.historyId()).isEqualTo(promotedB.historyId());
        assertThat(Files.readAllBytes(rootA.resolve("candidates/cand-gov/promotion.json")))
            .containsExactly(Files.readAllBytes(rootB.resolve("candidates/cand-gov/promotion.json")));
        assertThat(Files.readAllBytes(rootA.resolve("history").resolve(promotedA.historyId()).resolve("retention.json")))
            .containsExactly(Files.readAllBytes(rootB.resolve("history").resolve(promotedB.historyId()).resolve("retention.json")));

        DetectionReferenceBaselineLoader.LoadedBaseline currentA = new DetectionReferenceBaselineLoader().load(rootA);
        DetectionReferenceBaselineLoader.LoadedBaseline currentB = new DetectionReferenceBaselineLoader().load(rootB);
        String retainedCurrentA = currentA.manifest().baselineId() + "-" + currentA.manifestSha256().substring(0, 16);
        String retainedCurrentB = currentB.manifest().baselineId() + "-" + currentB.manifestSha256().substring(0, 16);
        assertThat(retainedCurrentA).isEqualTo(retainedCurrentB);

        DetectionReferenceBaselineLifecycleResult rollbackA =
            lifecycle.rollback(rootA, promotedA.historyId(), "same rollback", "approver-1");
        DetectionReferenceBaselineLifecycleResult rollbackB =
            lifecycle.rollback(rootB, promotedB.historyId(), "same rollback", "approver-1");
        assertThat(rollbackA.governanceRecordSha256()).isEqualTo(rollbackB.governanceRecordSha256());
        Path rollbackRecordA = rootA.resolve("governance")
            .resolve("rollback-" + retainedCurrentA + "-to-" + promotedA.historyId() + ".json");
        Path rollbackRecordB = rootB.resolve("governance")
            .resolve("rollback-" + retainedCurrentB + "-to-" + promotedB.historyId() + ".json");
        assertThat(Files.readAllBytes(rollbackRecordA)).containsExactly(Files.readAllBytes(rollbackRecordB));
    }

    @Test
    void historySnapshotFailureLeavesOfficialUnchanged() throws Exception {
        Path root = newLifecycleRoot("hist-fail");
        DetectionReferenceBaselineLifecycle lifecycle = new DetectionReferenceBaselineLifecycle();
        lifecycle.createCandidate(root, "cand-hf", driftedAnnotationConfig("ann-hf"));
        lifecycle.approveCandidate(root, "cand-hf", "ok", "reviewer-test");
        byte[] official = Files.readAllBytes(root.resolve("manifest.json"));

        // Pre-create conflicting history destination using the expected history id.
        DetectionReferenceBaselineLoader.LoadedBaseline loaded =
            new DetectionReferenceBaselineLoader().load(root);
        String historyId = loaded.manifest().baselineId() + "-" + loaded.manifestSha256().substring(0, 16);
        Files.createDirectories(root.resolve("history").resolve(historyId));

        assertThatThrownBy(() -> lifecycle.promoteCandidate(root, "cand-hf"))
            .isInstanceOf(DetectionReferenceBaselineLifecycleException.class)
            .extracting(ex -> ((DetectionReferenceBaselineLifecycleException) ex).code())
            .isEqualTo(DetectionReferenceBaselineLifecycleException.Code.HISTORY_CONFLICT);
        assertThat(Files.readAllBytes(root.resolve("manifest.json"))).containsExactly(official);
        assertThat(Files.exists(root.resolve("candidates/cand-hf/promotion.json"))).isFalse();
    }

    @Test
    void promotionTempConflictFailsBeforeHistoryOrOfficialMutation() throws Exception {
        Path root = newLifecycleRoot("temp-conflict");
        DetectionReferenceBaselineLifecycle lifecycle = new DetectionReferenceBaselineLifecycle();
        lifecycle.createCandidate(root, "cand-temp", driftedAnnotationConfig("ann-temp"));
        lifecycle.approveCandidate(root, "cand-temp", "ok", "reviewer-test");
        byte[] official = Files.readAllBytes(root.resolve("manifest.json"));

        Files.writeString(root.resolve("manifest.json.promoting"), "existing", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> lifecycle.promoteCandidate(root, "cand-temp"))
            .isInstanceOf(DetectionReferenceBaselineLifecycleException.class)
            .extracting(ex -> ((DetectionReferenceBaselineLifecycleException) ex).code())
            .isEqualTo(DetectionReferenceBaselineLifecycleException.Code.PUBLICATION_FAILURE);
        assertThat(Files.readAllBytes(root.resolve("manifest.json"))).containsExactly(official);
        assertThat(Files.exists(root.resolve("history"))).isFalse();
        assertThat(Files.exists(root.resolve("candidates/cand-temp/promotion.json"))).isFalse();
    }

    @Test
    void pathTraversalCandidateIdsAreRejected() throws Exception {
        Path root = newLifecycleRoot("path");
        DetectionReferenceBaselineLifecycle lifecycle = new DetectionReferenceBaselineLifecycle();
        assertThatThrownBy(() -> lifecycle.createCandidate(root, "../escape"))
            .isInstanceOf(DetectionReferenceBaselineLifecycleException.class)
            .extracting(ex -> ((DetectionReferenceBaselineLifecycleException) ex).code())
            .isEqualTo(DetectionReferenceBaselineLifecycleException.Code.INVALID_INPUT);
        assertThatThrownBy(() -> lifecycle.createCandidate(root, "a/b"))
            .isInstanceOf(DetectionReferenceBaselineLifecycleException.class);
    }

    private Path newLifecycleRoot(String name) throws Exception {
        Path root = tempDir.resolve(name);
        Files.createDirectories(root);
        copyBaselineFiles(locate(DetectionReferenceBaselineCapture.TRACKED_BASELINE_DIRECTORY), root);
        return root;
    }

    private DetectionReferenceBaselineConfiguration driftedAnnotationConfig(String name) throws Exception {
        Path datasetCopy = copyTrackedDataset(name);
        Path annotations = datasetCopy.resolve("annotations.json");
        String original = Files.readString(annotations, StandardCharsets.UTF_8);
        String mutated = original.replaceFirst(
            "\"expectedClass\":\"NORMAL\",\"anomalyExpected\":false",
            "\"expectedClass\":\"SYNTHETIC_ANOMALOUS\",\"anomalyExpected\":true"
        );
        assertThat(mutated).isNotEqualTo(original);
        Files.writeString(annotations, mutated, StandardCharsets.UTF_8);
        return new DetectionReferenceBaselineConfiguration(
            new DetectionClassificationConfiguration(0.5),
            ReplayConfiguration.referenceDefaults(),
            ReferenceDatasetGenerator.DATASET_ID,
            ReferenceDatasetAnnotations.SCHEMA_VERSION,
            datasetCopy,
            annotations
        );
    }

    private Path copyTrackedDataset(String name) throws Exception {
        Path source = locate(ReferenceDatasetGenerator.TRACKED_DATASET_DIRECTORY);
        Path target = tempDir.resolve("datasets").resolve(name);
        Files.createDirectories(target);
        try (var walk = Files.walk(source)) {
            walk.forEach(path -> {
                try {
                    Path relative = source.relativize(path);
                    Path destination = target.resolve(relative.toString());
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        return target;
    }

    private static void copyBaselineFiles(Path source, Path target) throws Exception {
        Files.createDirectories(target);
        for (String file : List.of("manifest.json", "evaluation.json", "evaluation.md")) {
            Files.copy(source.resolve(file), target.resolve(file), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void assertByteIdentical(Path left, Path right) throws Exception {
        for (String file : List.of("manifest.json", "evaluation.json", "evaluation.md")) {
            assertThat(Files.readAllBytes(left.resolve(file)))
                .containsExactly(Files.readAllBytes(right.resolve(file)));
        }
    }

    private static Path locate(Path relativePath) {
        Path candidate = relativePath.toAbsolutePath().normalize();
        if (Files.exists(candidate)) {
            return candidate;
        }
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            candidate = current.resolve(relativePath).normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("tracked path not found: " + relativePath);
    }
}
