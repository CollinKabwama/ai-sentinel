package dev.aisentinel.core.scoring.lifecycle;

import dev.aisentinel.core.baseline.BaselineLifecycle;
import dev.aisentinel.core.baseline.ConfigurableBaselineUpdatePolicy;
import dev.aisentinel.core.decision.SentinelDecisionEngine;
import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetScenarioCategory;
import dev.aisentinel.core.evaluation.CandidateEvaluationAcceptanceStatus;
import dev.aisentinel.core.evaluation.DetectionClassificationConfiguration;
import dev.aisentinel.core.evaluation.DetectionConfusionMatrix;
import dev.aisentinel.core.evaluation.DetectionEvaluationMetrics;
import dev.aisentinel.core.evaluation.DetectionMetrics;
import dev.aisentinel.core.evaluation.ScenarioDetectionMetrics;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.http.MapHttpRequestView;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.NoopTrustPolicyAdjuster;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.scoring.shadow.AcceptedCandidateIdentity;
import dev.aisentinel.distributed.training.TrainingFingerprintHashes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelLifecycleGovernanceTest {

    private static final EnforcementHandler NEVER_QUARANTINED = new EnforcementHandler() {
        @Override
        public boolean apply(EnforcementAction action, HttpRequestView request,
                             EnforcementResponse response,
                             String identityHash, String endpoint) {
            return true;
        }

        @Override
        public boolean isQuarantined(String identityHash, String endpoint) {
            return false;
        }
    };

    @Test
    void comparableSameDatasetAndThresholdProducesObjectiveDeltas() {
        ModelLifecycleIdentity champion = referenceChampion("stat-v1");
        ModelLifecycleIdentity challenger = artifact("cand-a", "1.0.0");
        ChampionChallengerComparison comparison = comparable(champion, challenger,
            matrix(2, 2, 1, 1), matrix(3, 2, 0, 1));

        assertThat(comparison.status()).isEqualTo(ChampionChallengerComparisonStatus.COMPARABLE);
        assertThat(comparison.metricDeltas()).isNotEmpty();
        MetricDelta recall = comparison.metricDeltas().stream()
            .filter(d -> d.metricName().equals("recall")).findFirst().orElseThrow();
        assertThat(recall.defined()).isTrue();
        assertThat(recall.delta().orElseThrow())
            .isEqualTo(challengerMetrics(matrix(3, 2, 0, 1)).metrics().recall().value()
                - championMetrics(matrix(2, 2, 1, 1)).metrics().recall().value());
        assertThat(comparison.limitations()).anyMatch(l -> l.contains("METRIC DELTA != GOVERNANCE DECISION"));
        assertThat(comparison.limitations()).anyMatch(l -> l.contains("COMPARISON != WINNER SELECTION"));
        assertThat(comparison.limitations()).noneMatch(l ->
            l.toLowerCase().contains("superior") || l.toLowerCase().contains("best model"));
    }

    @Test
    void incompatibleDifferentDataset() {
        ModelLifecycleIdentity champion = referenceChampion("stat-v1");
        ModelLifecycleIdentity challenger = artifact("cand-a", "1.0.0");
        EvaluationEvidenceBinding champEv = EvaluationEvidenceBinding.of(
            "dataset-a", 0.5, CandidateEvaluationAcceptanceStatus.NOT_ASSESSED);
        EvaluationEvidenceBinding challEv = EvaluationEvidenceBinding.of(
            "dataset-b", 0.5, CandidateEvaluationAcceptanceStatus.ACCEPTED);
        ChampionChallengerComparison comparison = ChampionChallengerComparison.compare(
            champion, challenger,
            metrics("dataset-a", 0.5, matrix(1, 1, 0, 0)),
            metrics("dataset-b", 0.5, matrix(1, 1, 0, 0)),
            champEv, challEv, null
        );
        assertThat(comparison.status()).isEqualTo(ChampionChallengerComparisonStatus.INCOMPATIBLE_EVIDENCE);
        assertThat(comparison.metricDeltas()).isEmpty();
    }

    @Test
    void sameDatasetLabelWithDifferentEvidenceHashIsIncompatible() {
        ModelLifecycleIdentity champion = referenceChampion("stat-v1");
        ModelLifecycleIdentity challenger = artifact("cand-a", "1.0.0");
        EvaluationEvidenceBinding champEv = new EvaluationEvidenceBinding(
            "dataset-1", 0.5, CandidateEvaluationAcceptanceStatus.NOT_ASSESSED,
            sha("dataset-content-a"), "feature-schema-v1", "holdout");
        EvaluationEvidenceBinding challEv = new EvaluationEvidenceBinding(
            "dataset-1", 0.5, CandidateEvaluationAcceptanceStatus.ACCEPTED,
            sha("dataset-content-b"), "feature-schema-v1", "holdout");

        ChampionChallengerComparison comparison = ChampionChallengerComparison.compare(
            champion, challenger,
            metrics("dataset-1", 0.5, matrix(1, 1, 0, 0)),
            metrics("dataset-1", 0.5, matrix(1, 1, 0, 0)),
            champEv, challEv, null
        );

        assertThat(comparison.status()).isEqualTo(ChampionChallengerComparisonStatus.INCOMPATIBLE_EVIDENCE);
        assertThat(comparison.incompatibilityDetail()).contains("evaluation evidence hash mismatch");
    }

    @Test
    void oneSidedEvidenceHashOrSchemaIdentityIsIncompatible() {
        ModelLifecycleIdentity champion = referenceChampion("stat-v1");
        ModelLifecycleIdentity challenger = artifact("cand-a", "1.0.0");
        EvaluationEvidenceBinding champEv = new EvaluationEvidenceBinding(
            "dataset-1", 0.5, CandidateEvaluationAcceptanceStatus.NOT_ASSESSED,
            sha("dataset-content-a"), "feature-schema-v1", "holdout");
        EvaluationEvidenceBinding challEv = EvaluationEvidenceBinding.of(
            "dataset-1", 0.5, CandidateEvaluationAcceptanceStatus.ACCEPTED);

        ChampionChallengerComparison comparison = ChampionChallengerComparison.compare(
            champion, challenger,
            metrics("dataset-1", 0.5, matrix(1, 1, 0, 0)),
            metrics("dataset-1", 0.5, matrix(1, 1, 0, 0)),
            champEv, challEv, null
        );

        assertThat(comparison.status()).isEqualTo(ChampionChallengerComparisonStatus.INCOMPATIBLE_EVIDENCE);
        assertThat(comparison.incompatibilityDetail().orElseThrow())
            .contains("evaluation evidence hash mismatch")
            .contains("feature schema version mismatch")
            .contains("evaluation split identity mismatch");
    }

    @Test
    void incompatibleDifferentThreshold() {
        ModelLifecycleIdentity champion = referenceChampion("stat-v1");
        ModelLifecycleIdentity challenger = artifact("cand-a", "1.0.0");
        ChampionChallengerComparison comparison = ChampionChallengerComparison.compare(
            champion, challenger,
            metrics("dataset-1", 0.5, matrix(1, 1, 0, 0)),
            metrics("dataset-1", 0.7, matrix(1, 1, 0, 0)),
            EvaluationEvidenceBinding.of("dataset-1", 0.5, CandidateEvaluationAcceptanceStatus.NOT_ASSESSED),
            EvaluationEvidenceBinding.of("dataset-1", 0.7, CandidateEvaluationAcceptanceStatus.ACCEPTED),
            null
        );
        assertThat(comparison.status()).isEqualTo(ChampionChallengerComparisonStatus.INCOMPATIBLE_EVIDENCE);
        assertThat(comparison.metricDeltas()).isEmpty();
    }

    @Test
    void incompatibleDifferentEvaluationSplit() {
        ModelLifecycleIdentity champion = referenceChampion("stat-v1");
        ModelLifecycleIdentity challenger = artifact("cand-a", "1.0.0");
        EvaluationEvidenceBinding champEv = new EvaluationEvidenceBinding(
            "dataset-1", 0.5, CandidateEvaluationAcceptanceStatus.NOT_ASSESSED,
            null, null, "split-train");
        EvaluationEvidenceBinding challEv = new EvaluationEvidenceBinding(
            "dataset-1", 0.5, CandidateEvaluationAcceptanceStatus.ACCEPTED,
            null, null, "split-holdout");
        ChampionChallengerComparison comparison = ChampionChallengerComparison.compare(
            champion, challenger,
            metrics("dataset-1", 0.5, matrix(1, 1, 0, 0)),
            metrics("dataset-1", 0.5, matrix(1, 1, 0, 0)),
            champEv, challEv, null
        );
        assertThat(comparison.status()).isEqualTo(ChampionChallengerComparisonStatus.INCOMPATIBLE_EVIDENCE);
    }

    @Test
    void insufficientWhenMetricsMissing() {
        ModelLifecycleIdentity champion = referenceChampion("stat-v1");
        ModelLifecycleIdentity challenger = artifact("cand-a", "1.0.0");
        ChampionChallengerComparison comparison = ChampionChallengerComparison.compare(
            champion, challenger, null, championMetrics(matrix(1, 1, 0, 0)),
            EvaluationEvidenceBinding.of("dataset-1", 0.5, CandidateEvaluationAcceptanceStatus.NOT_ASSESSED),
            EvaluationEvidenceBinding.of("dataset-1", 0.5, CandidateEvaluationAcceptanceStatus.ACCEPTED),
            null
        );
        assertThat(comparison.status()).isEqualTo(ChampionChallengerComparisonStatus.INSUFFICIENT_EVIDENCE);
        assertThat(comparison.metricDeltas()).isEmpty();
    }

    @Test
    void undefinedMetricDeltaIsUndefinedNotZero() {
        DetectionConfusionMatrix noPositives = matrix(0, 4, 0, 0);
        DetectionConfusionMatrix withPositives = matrix(2, 2, 1, 1);
        ChampionChallengerComparison comparison = comparable(
            referenceChampion("stat-v1"), artifact("cand-a", "1.0.0"),
            noPositives, withPositives
        );
        MetricDelta recall = comparison.metricDeltas().stream()
            .filter(d -> d.metricName().equals("recall")).findFirst().orElseThrow();
        assertThat(recall.defined()).isFalse();
        assertThat(recall.delta()).isEmpty();
    }

    @Test
    void equalMetricsProduceZeroDelta() {
        DetectionConfusionMatrix same = matrix(2, 2, 1, 1);
        ChampionChallengerComparison comparison = comparable(
            referenceChampion("stat-v1"), artifact("cand-a", "1.0.0"), same, same);
        for (MetricDelta delta : comparison.metricDeltas()) {
            assertThat(delta.defined()).isTrue();
            assertThat(delta.delta().orElseThrow()).isEqualTo(0.0);
        }
    }

    @Test
    void eligibilityDoesNotPromote() {
        ModelLifecycleManager manager = new ModelLifecycleManager();
        ModelLifecycleIdentity champion = referenceChampion("stat-v1");
        ModelLifecycleIdentity challenger = artifact("cand-a", "1.0.0");
        manager.designateChampion(champion);
        manager.designateChallenger(new ChallengerDesignation(
            challenger, champion, "challenge", "operator-1"));
        ChampionChallengerComparison comparison = comparable(
            champion, challenger, matrix(1, 1, 1, 1), matrix(2, 2, 0, 1));
        PromotionEligibilityAssessment assessment =
            manager.assessEligibility(PromotionEligibilityPolicy.defaults(), comparison);
        assertThat(assessment.eligible()).isTrue();
        assertThat(manager.currentChampion().orElseThrow()).isEqualTo(champion);
    }

    @Test
    void eligibilityFailsWhenNotAccepted() {
        ModelLifecycleIdentity champion = referenceChampion("stat-v1");
        ModelLifecycleIdentity challenger = artifact("cand-a", "1.0.0");
        ChampionChallengerComparison comparison = ChampionChallengerComparison.compare(
            champion, challenger,
            championMetrics(matrix(1, 1, 1, 1)),
            challengerMetrics(matrix(2, 2, 0, 1)),
            EvaluationEvidenceBinding.of("dataset-1", 0.5, CandidateEvaluationAcceptanceStatus.NOT_ASSESSED),
            EvaluationEvidenceBinding.of("dataset-1", 0.5, CandidateEvaluationAcceptanceStatus.REJECTED),
            null
        );
        PromotionEligibilityAssessment assessment =
            PromotionEligibilityPolicy.defaults().assess(comparison);
        assertThat(assessment.eligible()).isFalse();
        assertThat(assessment.issues()).anyMatch(i ->
            i.code() == PromotionEligibilityIssueCode.ACCEPTANCE_REQUIRED);
    }

    @Test
    void eligibilityFailsInsufficientShadow() {
        ModelLifecycleIdentity champion = referenceChampion("stat-v1");
        ModelLifecycleIdentity challenger = artifact("cand-a", "1.0.0");
        ShadowObservationSummary shadow = new ShadowObservationSummary(5, 2, 0, 0, 1, 1, 0.1);
        ChampionChallengerComparison comparison = ChampionChallengerComparison.compare(
            champion, challenger,
            championMetrics(matrix(1, 1, 1, 1)),
            challengerMetrics(matrix(2, 2, 0, 1)),
            EvaluationEvidenceBinding.of("dataset-1", 0.5, CandidateEvaluationAcceptanceStatus.NOT_ASSESSED),
            EvaluationEvidenceBinding.of("dataset-1", 0.5, CandidateEvaluationAcceptanceStatus.ACCEPTED),
            shadow
        );
        PromotionEligibilityPolicy policy = PromotionEligibilityPolicy.of(true, true, 10, 0, true);
        assertThat(policy.assess(comparison).eligible()).isFalse();
    }

    @Test
    void finiteCandidateFailureLimitRequiresShadowSummary() {
        ModelLifecycleIdentity champion = referenceChampion("stat-v1");
        ModelLifecycleIdentity challenger = artifact("cand-a", "1.0.0");
        ChampionChallengerComparison comparison = comparable(
            champion, challenger, matrix(1, 1, 1, 1), matrix(2, 2, 0, 1));

        PromotionEligibilityPolicy policy = PromotionEligibilityPolicy.of(true, true, 0, 0, false);

        PromotionEligibilityAssessment assessment = policy.assess(comparison);

        assertThat(assessment.eligible()).isFalse();
        assertThat(assessment.issues()).anyMatch(i ->
            i.code() == PromotionEligibilityIssueCode.SHADOW_SUMMARY_REQUIRED);
    }

    @Test
    void acceptanceDoesNotAutoCreateChallenger() {
        ModelLifecycleManager manager = new ModelLifecycleManager();
        manager.designateChampion(referenceChampion("stat-v1"));
        assertThat(manager.currentChallenger()).isEmpty();
    }

    @Test
    void approvalLeavesChampionUnchanged() {
        ModelLifecycleManager manager = primedApprovedManager();
        ModelLifecycleIdentity championBefore = manager.currentChampion().orElseThrow();
        assertThat(manager.pendingDecision().orElseThrow().status())
            .isEqualTo(ModelPromotionDecisionStatus.APPROVED);
        assertThat(manager.currentChampion().orElseThrow()).isEqualTo(championBefore);
    }

    @Test
    void rejectedCannotPromote() {
        ModelLifecycleManager manager = new ModelLifecycleManager();
        ModelLifecycleIdentity champion = referenceChampion("stat-v1");
        ModelLifecycleIdentity challenger = artifact("cand-a", "1.0.0");
        manager.designateChampion(champion);
        manager.designateChallenger(new ChallengerDesignation(
            challenger, champion, "challenge", "operator-1"));
        ChampionChallengerComparison comparison = comparable(
            champion, challenger, matrix(1, 1, 1, 1), matrix(2, 2, 0, 1));
        manager.rejectPromotion(comparison, "not ready", "operator-1");
        assertThatThrownBy(() -> manager.promote(champion))
            .isInstanceOf(ModelLifecycleException.class)
            .extracting(e -> ((ModelLifecycleException) e).code())
            .isEqualTo(ModelLifecycleException.Code.REJECTED_NOT_PROMOTABLE);
        assertThat(manager.currentChampion().orElseThrow()).isEqualTo(champion);
    }

    @Test
    void undecidedCannotPromote() {
        ModelLifecycleManager manager = new ModelLifecycleManager();
        ModelLifecycleIdentity champion = referenceChampion("stat-v1");
        ModelLifecycleIdentity challenger = artifact("cand-a", "1.0.0");
        manager.designateChampion(champion);
        manager.designateChallenger(new ChallengerDesignation(
            challenger, champion, "challenge", "operator-1"));
        assertThatThrownBy(() -> manager.promote(champion))
            .isInstanceOf(ModelLifecycleException.class)
            .extracting(e -> ((ModelLifecycleException) e).code())
            .isEqualTo(ModelLifecycleException.Code.APPROVAL_REQUIRED);
    }

    @Test
    void explicitPromoteChangesLifecycleChampionOnly() {
        ModelLifecycleManager manager = primedApprovedManager();
        ModelLifecycleIdentity previous = manager.currentChampion().orElseThrow();
        ModelLifecycleIdentity challenger = manager.currentChallenger().orElseThrow().challenger();
        ModelLifecycleOperationResult result = manager.promote(previous);
        assertThat(result.operation()).isEqualTo("PROMOTED");
        assertThat(manager.currentChampion().orElseThrow()).isEqualTo(challenger);
        assertThat(manager.currentChallenger()).isEmpty();
        assertThat(manager.history()).hasSize(1);
        assertThat(manager.history().get(0).kind()).isEqualTo(ModelLifecycleHistoryEntry.Kind.PROMOTION);
    }

    @Test
    void staleChampionRejectsPromotion(@TempDir Path root) throws Exception {
        ModelLifecycleManager manager = new ModelLifecycleManager(root);
        ModelLifecycleIdentity championA = referenceChampion("stat-v1");
        ModelLifecycleIdentity challenger = artifact("cand-a", "1.0.0");
        manager.designateChampion(championA);
        manager.designateChallenger(new ChallengerDesignation(
            challenger, championA, "challenge", "operator-1"));
        ChampionChallengerComparison comparison = comparable(
            championA, challenger, matrix(1, 1, 1, 1), matrix(2, 2, 0, 1));
        manager.approvePromotion(comparison, PromotionEligibilityPolicy.defaults(),
            "ok", "operator-1");

        ModelLifecycleIdentity championC = referenceChampion("stat-v2");
        Files.writeString(root.resolve(ModelLifecycleSchemas.CHAMPION_FILE),
            ModelLifecycleEvidenceFormats.championJson(championC), StandardCharsets.UTF_8);

        ModelLifecycleManager reloaded = new ModelLifecycleManager(root);
        assertThat(reloaded.currentChampion().orElseThrow()).isEqualTo(championC);
        assertThatThrownBy(() -> reloaded.promote(championC))
            .isInstanceOf(ModelLifecycleException.class)
            .extracting(e -> ((ModelLifecycleException) e).code())
            .isEqualTo(ModelLifecycleException.Code.STALE_APPROVAL);
    }

    @Test
    void evidenceSubstitutionRejectedAfterApproval() {
        ModelLifecycleManager manager = primedApprovedManager();
        ModelLifecycleIdentity champion = manager.currentChampion().orElseThrow();
        ModelLifecycleIdentity challenger = manager.currentChallenger().orElseThrow().challenger();
        ChampionChallengerComparison other = comparable(
            champion, challenger, matrix(1, 1, 1, 1), matrix(4, 1, 0, 0));
        assertThatThrownBy(() -> manager.bindComparison(other))
            .isInstanceOf(ModelLifecycleException.class)
            .extracting(e -> ((ModelLifecycleException) e).code())
            .isEqualTo(ModelLifecycleException.Code.EVIDENCE_MISMATCH);
    }

    @Test
    void rollbackRestoresPreviousChampionAndPreservesHistory() {
        ModelLifecycleManager manager = primedApprovedManager();
        ModelLifecycleIdentity previous = manager.currentChampion().orElseThrow();
        manager.promote(previous);
        ModelLifecycleIdentity promoted = manager.currentChampion().orElseThrow();
        String promotionId = manager.history().get(0).entryId();

        ModelLifecycleOperationResult rollback = manager.rollback(
            promotionId, promoted, "revert", "operator-1");
        assertThat(rollback.operation()).isEqualTo("ROLLED_BACK");
        assertThat(manager.currentChampion().orElseThrow()).isEqualTo(previous);
        assertThat(manager.history()).hasSize(2);
        assertThat(manager.history().get(1).kind()).isEqualTo(ModelLifecycleHistoryEntry.Kind.ROLLBACK);
    }

    @Test
    void staleRollbackRejected() {
        ModelLifecycleManager manager = primedApprovedManager();
        ModelLifecycleIdentity previous = manager.currentChampion().orElseThrow();
        manager.promote(previous);
        String promotionId = manager.history().get(0).entryId();
        assertThatThrownBy(() -> manager.rollback(
            promotionId, previous, "stale", "operator-1"))
            .isInstanceOf(ModelLifecycleException.class)
            .extracting(e -> ((ModelLifecycleException) e).code())
            .isEqualTo(ModelLifecycleException.Code.STALE_ROLLBACK);
    }

    @Test
    void unknownPromotionRollbackRejected() {
        ModelLifecycleManager manager = primedApprovedManager();
        ModelLifecycleIdentity previous = manager.currentChampion().orElseThrow();
        manager.promote(previous);
        assertThatThrownBy(() -> manager.rollback(
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            manager.currentChampion().orElseThrow(), "nope", "operator-1"))
            .isInstanceOf(ModelLifecycleException.class)
            .extracting(e -> ((ModelLifecycleException) e).code())
            .isEqualTo(ModelLifecycleException.Code.HISTORY_NOT_FOUND);
    }

    @Test
    void historyImmutableAcrossMultiplePromotions() {
        ModelLifecycleManager manager = new ModelLifecycleManager();
        ModelLifecycleIdentity a = referenceChampion("stat-v1");
        ModelLifecycleIdentity b = artifact("cand-a", "1.0.0");
        ModelLifecycleIdentity c = artifact("cand-b", "2.0.0");
        manager.designateChampion(a);
        promotePath(manager, a, b);
        List<ModelLifecycleHistoryEntry> afterFirst = manager.history();
        String firstId = afterFirst.get(0).entryId();
        promotePath(manager, b, c);
        assertThat(manager.history()).hasSize(2);
        assertThat(manager.history().get(0).entryId()).isEqualTo(firstId);
        assertThat(manager.history().get(0).promotion().orElseThrow().previousChampion()).isEqualTo(a);
    }

    @Test
    void determinismOfComparisonAndDecision() {
        ModelLifecycleIdentity champion = referenceChampion("stat-v1");
        ModelLifecycleIdentity challenger = artifact("cand-a", "1.0.0");
        ChampionChallengerComparison c1 = comparable(
            champion, challenger, matrix(1, 1, 1, 1), matrix(2, 2, 0, 1));
        ChampionChallengerComparison c2 = comparable(
            champion, challenger, matrix(1, 1, 1, 1), matrix(2, 2, 0, 1));
        assertThat(c1.comparisonSha256Hex()).isEqualTo(c2.comparisonSha256Hex());

        ModelPromotionDecision d1 = ModelPromotionDecision.approved(
            champion, challenger, c1.comparisonSha256Hex(), "ok", "op");
        ModelPromotionDecision d2 = ModelPromotionDecision.approved(
            champion, challenger, c2.comparisonSha256Hex(), "ok", "op");
        assertThat(d1.decisionSha256Hex()).isEqualTo(d2.decisionSha256Hex());
    }

    @Test
    void promotionDoesNotRewireDecisionEngineScorer() {
        AtomicInteger scoreCalls = new AtomicInteger();
        AtomicReference<AnomalyScorer> wired = new AtomicReference<>();
        AnomalyScorer production = new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                scoreCalls.incrementAndGet();
                return 0.11;
            }

            @Override
            public void update(RequestFeatures features) {
            }
        };
        wired.set(production);

        SentinelDecisionEngine engine = new SentinelDecisionEngine(
            production,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            NEVER_QUARANTINED,
            event -> {
            },
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE,
            EnforcementAction.MONITOR,
            ConfigurableBaselineUpdatePolicy.allowOrMonitor(),
            BaselineLifecycle.disabled(),
            null
        );

        double before = engine.evaluate(
            new MapHttpRequestView(), "id", features(), new RequestContext()
        ).anomalyScore();

        ModelLifecycleManager manager = primedApprovedManager();
        ModelLifecycleIdentity previous = manager.currentChampion().orElseThrow();
        manager.promote(previous);

        double after = engine.evaluate(
            new MapHttpRequestView(), "id", features(), new RequestContext()
        ).anomalyScore();

        assertThat(after).isEqualTo(before);
        assertThat(scoreCalls.get()).isEqualTo(2);
        assertThat(wired.get()).isSameAs(production);
    }

    @Test
    void filesystemHistoryCreateOnly(@TempDir Path root) {
        ModelLifecycleManager manager = new ModelLifecycleManager(root);
        ModelLifecycleIdentity a = referenceChampion("stat-v1");
        ModelLifecycleIdentity b = artifact("cand-a", "1.0.0");
        manager.designateChampion(a);
        promotePath(manager, a, b);
        assertThat(Files.isDirectory(root.resolve(ModelLifecycleSchemas.HISTORY_DIRECTORY))).isTrue();
        ModelLifecycleManager reloaded = new ModelLifecycleManager(root);
        assertThat(reloaded.currentChampion().orElseThrow()).isEqualTo(b);
        assertThat(reloaded.history()).hasSize(1);
    }

    @Test
    void decisionRequiresRationaleAndApprover() {
        ModelLifecycleIdentity champion = referenceChampion("stat-v1");
        ModelLifecycleIdentity challenger = artifact("cand-a", "1.0.0");
        assertThatThrownBy(() -> ModelPromotionDecision.approved(
            champion, challenger, "a".repeat(64), " ", "op"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelPromotionDecision.approved(
            champion, challenger, "a".repeat(64), "ok", " "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shadowSummaryRejectsGroundTruthFieldsByDesign() {
        ShadowObservationSummary summary = new ShadowObservationSummary(10, 8, 1, 1, 5, 3, 0.05);
        assertThat(summary.validComparisonCount()).isEqualTo(8);
        assertThat(summary.disagreementCount()).isEqualTo(3);
    }

    @Test
    void shadowSummaryRejectsContradictoryTotals() {
        assertThatThrownBy(() -> new ShadowObservationSummary(10, 8, 2, 1, 5, 3, 0.05))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot exceed attemptedShadowExecutions");
        assertThatThrownBy(() -> new ShadowObservationSummary(10, 2, 0, 11, 1, 1, 0.05))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("failure counts");
    }

    @Test
    void lifecycleIdentityRejectsAmbiguousTokensAndMalformedHex() {
        assertThatThrownBy(() -> ModelLifecycleIdentity.designatedReference(
            "stat|reference", "v1", sha("fingerprint")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scorerId");
        assertThatThrownBy(() -> ModelLifecycleIdentity.designatedReference(
            "statistical-reference", "v1", "z".repeat(64)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("configurationFingerprintSha256Hex");
        assertThatThrownBy(() -> new EvaluationEvidenceBinding(
            "dataset-1", 0.5, CandidateEvaluationAcceptanceStatus.ACCEPTED,
            "z".repeat(64), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("evaluationJsonSha256Hex");
    }

    @Test
    void lifecycleHashesAreLengthPrefixedNotDelimiterAmbiguous() {
        ModelLifecycleIdentity champion = referenceChampion("stat-v1");
        ModelLifecycleIdentity challenger = artifact("cand-a", "1.0.0");
        ModelPromotionDecision first = ModelPromotionDecision.approved(
            champion, challenger, "a".repeat(64), "ab\nc", "op");
        ModelPromotionDecision second = ModelPromotionDecision.approved(
            champion, challenger, "a".repeat(64), "a\nbc", "op");

        assertThat(first.decisionSha256Hex()).isNotEqualTo(second.decisionSha256Hex());
    }

    @Test
    void filesystemLoadRejectsUnsupportedSchemaVersion(@TempDir Path root) throws Exception {
        ModelLifecycleManager manager = new ModelLifecycleManager(root);
        ModelLifecycleIdentity champion = referenceChampion("stat-v1");
        manager.designateChampion(champion);

        Path championFile = root.resolve(ModelLifecycleSchemas.CHAMPION_FILE);
        String tampered = Files.readString(championFile, StandardCharsets.UTF_8)
            .replace(ModelLifecycleSchemas.SCHEMA_VERSION, "model-lifecycle-governance-v999");
        Files.writeString(championFile, tampered, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new ModelLifecycleManager(root))
            .isInstanceOf(ModelLifecycleException.class)
            .extracting(e -> ((ModelLifecycleException) e).code())
            .isEqualTo(ModelLifecycleException.Code.EVIDENCE_MISMATCH);
    }

    @Test
    void filesystemLoadRejectsMalformedHistoryFilename(@TempDir Path root) throws Exception {
        ModelLifecycleManager manager = new ModelLifecycleManager(root);
        ModelLifecycleIdentity previous = referenceChampion("stat-v1");
        ModelLifecycleIdentity challenger = artifact("cand-a", "1.0.0");
        manager.designateChampion(previous);
        promotePath(manager, previous, challenger);

        Path history = root.resolve(ModelLifecycleSchemas.HISTORY_DIRECTORY);
        Path valid;
        try (var files = Files.list(history)) {
            valid = files.findFirst().orElseThrow();
        }
        Files.move(valid, history.resolve("promotion-not-a-number-" + valid.getFileName()));

        assertThatThrownBy(() -> new ModelLifecycleManager(root))
            .isInstanceOf(ModelLifecycleException.class)
            .extracting(e -> ((ModelLifecycleException) e).code())
            .isEqualTo(ModelLifecycleException.Code.EVIDENCE_MISMATCH);
    }

    @Test
    void impossibleHistoryStatesRejected() {
        assertThatThrownBy(() -> ModelLifecycleHistoryEntry.promotion(null, 0))
            .isInstanceOf(NullPointerException.class);
        ModelPromotionRecord promo = new ModelPromotionRecord(
            referenceChampion("a"), artifact("b", "1"),
            "d".repeat(64), "c".repeat(64), "ok", "op");
        assertThat(ModelLifecycleHistoryEntry.promotion(promo, 0).promotion()).isPresent();
        assertThat(ModelLifecycleHistoryEntry.promotion(promo, 0).rollback()).isEmpty();
    }

    private static ModelLifecycleManager primedApprovedManager() {
        ModelLifecycleManager manager = new ModelLifecycleManager();
        ModelLifecycleIdentity champion = referenceChampion("stat-v1");
        ModelLifecycleIdentity challenger = artifact("cand-a", "1.0.0");
        manager.designateChampion(champion);
        manager.designateChallenger(new ChallengerDesignation(
            challenger, champion, "challenge", "operator-1"));
        ChampionChallengerComparison comparison = comparable(
            champion, challenger, matrix(1, 1, 1, 1), matrix(2, 2, 0, 1));
        manager.bindComparison(comparison);
        manager.approvePromotion(comparison, PromotionEligibilityPolicy.defaults(),
            "explicit approve", "operator-1");
        return manager;
    }

    private static void promotePath(
        ModelLifecycleManager manager,
        ModelLifecycleIdentity champion,
        ModelLifecycleIdentity challenger
    ) {
        manager.designateChallenger(new ChallengerDesignation(
            challenger, champion, "challenge", "operator-1"));
        ChampionChallengerComparison comparison = comparable(
            champion, challenger, matrix(1, 1, 1, 1), matrix(2, 2, 0, 1));
        manager.approvePromotion(comparison, PromotionEligibilityPolicy.defaults(),
            "explicit approve", "operator-1");
        manager.promote(champion);
    }

    private static ChampionChallengerComparison comparable(
        ModelLifecycleIdentity champion,
        ModelLifecycleIdentity challenger,
        DetectionConfusionMatrix championMatrix,
        DetectionConfusionMatrix challengerMatrix
    ) {
        return ChampionChallengerComparison.compare(
            champion,
            challenger,
            championMetrics(championMatrix),
            challengerMetrics(challengerMatrix),
            EvaluationEvidenceBinding.of("dataset-1", 0.5, CandidateEvaluationAcceptanceStatus.NOT_ASSESSED),
            EvaluationEvidenceBinding.of("dataset-1", 0.5, CandidateEvaluationAcceptanceStatus.ACCEPTED),
            null
        );
    }

    private static DetectionEvaluationMetrics championMetrics(DetectionConfusionMatrix confusion) {
        return metrics("dataset-1", 0.5, confusion);
    }

    private static DetectionEvaluationMetrics challengerMetrics(DetectionConfusionMatrix confusion) {
        return metrics("dataset-1", 0.5, confusion);
    }

    private static DetectionEvaluationMetrics metrics(
        String datasetId,
        double threshold,
        DetectionConfusionMatrix confusion
    ) {
        long evaluable = confusion.totalCount();
        DetectionMetrics detectionMetrics = DetectionMetrics.from(confusion);
        return new DetectionEvaluationMetrics(
            datasetId,
            "replay-" + datasetId,
            new DetectionClassificationConfiguration(threshold),
            evaluable,
            evaluable,
            0L,
            confusion,
            detectionMetrics,
            List.of(new ScenarioDetectionMetrics(
                "scenario-1",
                ReferenceDatasetScenarioCategory.ESTABLISHED_NORMAL_BASELINE,
                evaluable,
                evaluable,
                0L,
                confusion,
                detectionMetrics
            ))
        );
    }

    private static DetectionConfusionMatrix matrix(long tp, long tn, long fp, long fn) {
        return new DetectionConfusionMatrix(tp, tn, fp, fn);
    }

    private static ModelLifecycleIdentity referenceChampion(String version) {
        return ModelLifecycleIdentity.designatedReference(
            "statistical-reference",
            version,
            sha("fingerprint-" + version)
        );
    }

    private static ModelLifecycleIdentity artifact(String id, String version) {
        return ModelLifecycleIdentity.fromAccepted(new AcceptedCandidateIdentity(
            id,
            version,
            id + "-artifact",
            sha("digest-" + id + "-" + version),
            sha("config-" + id + "-" + version)
        ));
    }

    private static String sha(String material) {
        return TrainingFingerprintHashes.sha256HexUtf8(material);
    }

    private static RequestFeatures features() {
        return RequestFeatures.builder()
            .identityHash("id")
            .endpoint("/api")
            .timestampMillis(1_700_000_000_000L)
            .requestsPerWindow(2)
            .endpointEntropy(0.5)
            .endpointConcentration(0.5)
            .tokenAgeSeconds(60)
            .parameterCount(1)
            .payloadSizeBytes(100)
            .headerFingerprintHash(0)
            .ipBucket(0)
            .build();
    }
}
