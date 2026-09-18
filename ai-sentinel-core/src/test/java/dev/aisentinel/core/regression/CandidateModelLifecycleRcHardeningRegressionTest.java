package dev.aisentinel.core.regression;

import dev.aisentinel.core.baseline.BaselineLifecycle;
import dev.aisentinel.core.baseline.ConfigurableBaselineUpdatePolicy;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetGenerator;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetScenarioCategory;
import dev.aisentinel.core.decision.RiskDecision;
import dev.aisentinel.core.decision.SentinelDecisionEngine;
import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import dev.aisentinel.core.evaluation.CandidateDetectionEvaluationRunner;
import dev.aisentinel.core.evaluation.CandidateDetectionEvaluationStatus;
import dev.aisentinel.core.evaluation.CandidateEvaluationAcceptancePolicy;
import dev.aisentinel.core.evaluation.CandidateEvaluationAcceptanceStatus;
import dev.aisentinel.core.evaluation.DetectionClassificationConfiguration;
import dev.aisentinel.core.evaluation.DetectionConfusionMatrix;
import dev.aisentinel.core.evaluation.DetectionEvaluationMetrics;
import dev.aisentinel.core.evaluation.DetectionMetrics;
import dev.aisentinel.core.evaluation.DetectionReferenceBaselineSchemas;
import dev.aisentinel.core.evaluation.ScenarioDetectionMetrics;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.http.MapHttpRequestView;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.FeatureSchema;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.NoopTrustPolicyAdjuster;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.scoring.IsolationForestModel;
import dev.aisentinel.core.scoring.IsolationForestModelCodec;
import dev.aisentinel.core.scoring.IsolationForestTrainer;
import dev.aisentinel.core.scoring.StatisticalScorer;
import dev.aisentinel.core.scoring.artifact.ArtifactDigest;
import dev.aisentinel.core.scoring.artifact.CandidateScorerLoader;
import dev.aisentinel.core.scoring.artifact.LoadedCandidateScorer;
import dev.aisentinel.core.scoring.artifact.ScorerArtifactCapabilities;
import dev.aisentinel.core.scoring.artifact.ScorerArtifactDescriptor;
import dev.aisentinel.core.scoring.artifact.ScorerOutputRange;
import dev.aisentinel.core.scoring.lifecycle.ChallengerDesignation;
import dev.aisentinel.core.scoring.lifecycle.ChampionChallengerComparison;
import dev.aisentinel.core.scoring.lifecycle.ChampionChallengerComparisonStatus;
import dev.aisentinel.core.scoring.lifecycle.EvaluationEvidenceBinding;
import dev.aisentinel.core.scoring.lifecycle.ModelLifecycleException;
import dev.aisentinel.core.scoring.lifecycle.ModelLifecycleHistoryEntry;
import dev.aisentinel.core.scoring.lifecycle.ModelLifecycleIdentity;
import dev.aisentinel.core.scoring.lifecycle.ModelLifecycleManager;
import dev.aisentinel.core.scoring.lifecycle.ModelPromotionDecisionStatus;
import dev.aisentinel.core.scoring.lifecycle.PromotionEligibilityAssessment;
import dev.aisentinel.core.scoring.lifecycle.PromotionEligibilityPolicy;
import dev.aisentinel.core.scoring.lifecycle.ShadowObservationSummary;
import dev.aisentinel.core.scoring.shadow.AcceptedCandidateIdentity;
import dev.aisentinel.core.scoring.shadow.ShadowCandidateBinding;
import dev.aisentinel.core.scoring.shadow.ShadowIneligibilityReason;
import dev.aisentinel.core.scoring.shadow.ShadowObservationSink;
import dev.aisentinel.core.scoring.shadow.ShadowScoringConfiguration;
import dev.aisentinel.core.scoring.shadow.ShadowScoringExecutor;
import dev.aisentinel.core.scoring.shadow.ShadowScoringObservation;
import dev.aisentinel.core.scoring.shadow.ShadowScoringStatus;
import dev.aisentinel.distributed.training.TrainingFingerprintHashes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Release-candidate integrated regressions spanning candidate load → evaluation →
 * acceptance → shadow → challenger → approval → promotion → rollback, while proving
 * lifecycle designation never rewires the authoritative production scorer.
 * <p>
 * {@code PROMOTED LIFECYCLE CHAMPION != RUNNING PRODUCTION SCORER}
 */
class CandidateModelLifecycleRcHardeningRegressionTest {

    private static final DetectionClassificationConfiguration THRESHOLD =
        new DetectionClassificationConfiguration(DetectionReferenceBaselineSchemas.REFERENCE_CLASSIFICATION_THRESHOLD);

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

    @TempDir
    Path tempDir;

    @Test
    void fullLifecycleSmokeChangesChampionOnlyAndLeavesProductionAuthorityUnchanged() throws Exception {
        byte[] payload = encodeIsolationForest(42L);
        ScorerArtifactDescriptor descriptor = ifDescriptor("rc-cand-a", "1.0.0", "artifact-a", payload);
        CandidateEvaluationAcceptancePolicy acceptancePolicy = CandidateEvaluationAcceptancePolicy.builder()
            .minimumEvaluableObservations(1L)
            .build();

        CandidateDetectionEvaluationRunner.CandidateDetectionEvaluationResult evaluated =
            new CandidateDetectionEvaluationRunner().evaluate(
                descriptor,
                payload,
                trackedDatasetDirectory(),
                trackedAnnotationsFile(),
                THRESHOLD,
                acceptancePolicy,
                tempDir.resolve("rc-full-lifecycle")
            );

        assertThat(evaluated.status()).isEqualTo(CandidateDetectionEvaluationStatus.COMPLETED);
        assertThat(evaluated.evidence().acceptance().status())
            .isEqualTo(CandidateEvaluationAcceptanceStatus.ACCEPTED);
        assertThat(evaluated.evidence().candidate().verifiedDigestHex())
            .isEqualTo(descriptor.artifactDigest().digestHex());
        assertThat(evaluated.evidence().candidate().configurationFingerprintSha256Hex())
            .isEqualTo(descriptor.configurationFingerprintSha256Hex());
        assertThat(evaluated.evidence().candidate().verifiedDigestHex())
            .isNotEqualTo(evaluated.evidence().candidate().configurationFingerprintSha256Hex());

        LoadedCandidateScorer loaded = CandidateScorerLoader.load(descriptor, payload).loaded().orElseThrow();
        assertThat(loaded.provenance().verifiedDigestHex())
            .isEqualTo(evaluated.evidence().candidate().verifiedDigestHex());
        AcceptedCandidateIdentity accepted =
            AcceptedCandidateIdentity.fromAcceptedProvenance(loaded.provenance());

        AtomicInteger authoritativeCalls = new AtomicInteger();
        AnomalyScorer authoritative = countingScorer(0.15, authoritativeCalls);
        List<ShadowScoringObservation> observations = new ArrayList<>();
        ShadowScoringExecutor shadow = ShadowScoringExecutor.of(
            ShadowScoringConfiguration.enabled(accepted, OptionalDouble.of(0.5)),
            ShadowCandidateBinding.of(loaded.scorer(), loaded.provenance()),
            observations::add
        );

        RiskDecision beforePromotion = evaluate(authoritative, shadow);
        assertThat(beforePromotion.action()).isEqualTo(EnforcementAction.ALLOW);
        assertThat(beforePromotion.anomalyScore()).isEqualTo(0.15);
        assertThat(observations).isNotEmpty();
        assertThat(observations.get(0).status()).isEqualTo(ShadowScoringStatus.SCORED);

        ModelLifecycleIdentity champion = ModelLifecycleIdentity.designatedReference(
            "statistical-reference", "rc-v1", sha("fingerprint-rc-v1"));
        ModelLifecycleIdentity challenger = ModelLifecycleIdentity.fromAccepted(accepted);
        ModelLifecycleManager manager = new ModelLifecycleManager(tempDir.resolve("lifecycle-smoke"));
        manager.designateChampion(champion);
        manager.designateChallenger(new ChallengerDesignation(
            challenger, champion, "rc-smoke-challenge", "rc-operator"));

        ChampionChallengerComparison comparison = comparable(
            champion, challenger, matrix(2, 2, 1, 1), matrix(3, 2, 0, 1),
            new ShadowObservationSummary(1, 1, 0, 0, 1, 0, 0.01));
        assertThat(comparison.status()).isEqualTo(ChampionChallengerComparisonStatus.COMPARABLE);
        manager.bindComparison(comparison);

        PromotionEligibilityAssessment eligibility = manager.assessEligibility(
            PromotionEligibilityPolicy.defaults(),
            comparison);
        assertThat(eligibility.eligible()).isTrue();
        assertThat(manager.currentChampion().orElseThrow()).isEqualTo(champion);

        manager.approvePromotion(comparison, PromotionEligibilityPolicy.defaults(),
            "explicit rc approve", "rc-operator");
        assertThat(manager.pendingDecision().orElseThrow().status())
            .isEqualTo(ModelPromotionDecisionStatus.APPROVED);
        assertThat(manager.currentChampion().orElseThrow()).isEqualTo(champion);

        manager.promote(champion);
        assertThat(manager.currentChampion().orElseThrow()).isEqualTo(challenger);
        assertThat(manager.history()).isNotEmpty();
        assertThat(manager.history().get(0).kind()).isEqualTo(ModelLifecycleHistoryEntry.Kind.PROMOTION);

        RiskDecision afterPromotion = evaluate(authoritative, shadow);
        assertThat(afterPromotion.anomalyScore()).isEqualTo(beforePromotion.anomalyScore());
        assertThat(afterPromotion.action()).isEqualTo(beforePromotion.action());
        assertThat(afterPromotion.policyScore()).isEqualTo(beforePromotion.policyScore());
        assertThat(authoritativeCalls.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void candidateIdentitySubstitutionRejectedAtShadowAndChallengerBoundaries() throws Exception {
        LoadedCandidateScorer loadedA = loadReady("cand-a", "1.0.0", "artifact-a", 11L);
        LoadedCandidateScorer loadedB = loadReady("cand-b", "1.0.0", "artifact-b", 99L);
        AcceptedCandidateIdentity acceptedA =
            AcceptedCandidateIdentity.fromAcceptedProvenance(loadedA.provenance());

        AtomicInteger callsB = new AtomicInteger();
        ShadowScoringObservation digestMismatch = ShadowScoringExecutor.of(
            ShadowScoringConfiguration.enabled(acceptedA),
            ShadowCandidateBinding.of(countingScorer(0.9, callsB), loadedB.provenance()),
            ShadowObservationSink.NOOP
        ).observe(0.2, features(), "corr-sub");

        assertThat(digestMismatch.status()).isEqualTo(ShadowScoringStatus.NOT_ELIGIBLE);
        assertThat(digestMismatch.ineligibilityReason()).contains(ShadowIneligibilityReason.IDENTITY_MISMATCH);
        assertThat(callsB.get()).isZero();

        AcceptedCandidateIdentity fingerprintMismatch = new AcceptedCandidateIdentity(
            loadedA.provenance().scorerId(),
            loadedA.provenance().scorerVersion(),
            loadedA.provenance().artifactId(),
            loadedA.provenance().verifiedDigestHex(),
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        );
        AtomicInteger callsA = new AtomicInteger();
        ShadowScoringObservation fpObs = ShadowScoringExecutor.of(
            ShadowScoringConfiguration.enabled(fingerprintMismatch),
            ShadowCandidateBinding.of(countingScorer(0.8, callsA), loadedA.provenance()),
            ShadowObservationSink.NOOP
        ).observe(0.2, features(), "corr-fp");
        assertThat(fpObs.ineligibilityReason()).contains(ShadowIneligibilityReason.IDENTITY_MISMATCH);
        assertThat(callsA.get()).isZero();

        ModelLifecycleIdentity champion = ModelLifecycleIdentity.designatedReference(
            "statistical-reference", "sub-v1", sha("fingerprint-sub-v1"));
        ModelLifecycleIdentity challengerA = ModelLifecycleIdentity.fromAccepted(acceptedA);
        ModelLifecycleIdentity challengerB = ModelLifecycleIdentity.fromProvenance(loadedB.provenance());
        ModelLifecycleManager manager = new ModelLifecycleManager();
        manager.designateChampion(champion);
        manager.designateChallenger(new ChallengerDesignation(
            challengerA, champion, "challenge-a", "operator"));

        ChampionChallengerComparison comparisonForB = comparable(
            champion, challengerB, matrix(1, 1, 1, 1), matrix(2, 2, 0, 1));
        assertThatThrownBy(() -> manager.bindComparison(comparisonForB))
            .isInstanceOf(ModelLifecycleException.class)
            .extracting(e -> ((ModelLifecycleException) e).code())
            .isEqualTo(ModelLifecycleException.Code.IDENTITY_MISMATCH);

        ChampionChallengerComparison comparisonForA = comparable(
            champion, challengerA, matrix(1, 1, 1, 1), matrix(2, 2, 0, 1));
        manager.bindComparison(comparisonForA);
        manager.approvePromotion(comparisonForA, PromotionEligibilityPolicy.defaults(),
            "approve-a", "operator");
        manager.promote(champion);

        // After promote, champion is A. Substitution of B was rejected at bindComparison.
        assertThat(manager.currentChampion().orElseThrow()).isEqualTo(challengerA);
        assertThat(manager.currentChampion().orElseThrow().verifiedDigestHex())
            .isNotEqualTo(challengerB.verifiedDigestHex());
        assertThat(manager.currentChampion().orElseThrow().configurationFingerprintSha256Hex())
            .isNotEqualTo(challengerB.configurationFingerprintSha256Hex());
    }

    @Test
    void promotedLifecycleChampionDoesNotDriveAuthoritativeRiskDecision() throws Exception {
        LoadedCandidateScorer loaded = loadReady("auth-cand", "1.0.0", "artifact-auth", 7L);
        AcceptedCandidateIdentity accepted =
            AcceptedCandidateIdentity.fromAcceptedProvenance(loaded.provenance());

        AnomalyScorer productionScorer = fixedScorer(0.12);
        AtomicReference<ShadowScoringObservation> observation = new AtomicReference<>();
        ShadowScoringExecutor shadow = ShadowScoringExecutor.of(
            ShadowScoringConfiguration.enabled(accepted),
            ShadowCandidateBinding.of(fixedScorer(0.99), loaded.provenance()),
            observation::set
        );

        ModelLifecycleIdentity championA = ModelLifecycleIdentity.designatedReference(
            "statistical-reference", "auth-v1", sha("fingerprint-auth-v1"));
        ModelLifecycleIdentity challengerB = ModelLifecycleIdentity.fromAccepted(accepted);
        ModelLifecycleManager manager = new ModelLifecycleManager();
        manager.designateChampion(championA);
        promotePath(manager, championA, challengerB);
        assertThat(manager.currentChampion().orElseThrow()).isEqualTo(challengerB);

        RiskDecision decision = evaluate(productionScorer, shadow);
        assertThat(decision.anomalyScore()).isEqualTo(0.12);
        assertThat(decision.policyScore()).isEqualTo(0.12);
        assertThat(decision.action()).isEqualTo(EnforcementAction.ALLOW);
        assertThat(new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8)
            .evaluate(0.99, features(), features().endpoint()))
            .isEqualTo(EnforcementAction.QUARANTINE);
        assertThat(decision.action()).isNotIn(EnforcementAction.BLOCK, EnforcementAction.QUARANTINE);
        assertThat(observation.get()).isNotNull();
        assertThat(observation.get().status()).isEqualTo(ShadowScoringStatus.SCORED);
        assertThat(observation.get().candidateScore()).hasValue(0.99);
    }

    @Test
    void lifecycleAndShadowActivityDoesNotContaminateAuthoritativeBaselineState() throws Exception {
        LoadedCandidateScorer loaded = loadReady("state-cand", "1.0.0", "artifact-state", 3L);
        AcceptedCandidateIdentity accepted =
            AcceptedCandidateIdentity.fromAcceptedProvenance(loaded.provenance());

        StatisticalScorer control = new StatisticalScorer(1_000, 60_000L, 5, 0.0);
        StatisticalScorer treated = new StatisticalScorer(1_000, 60_000L, 5, 0.0);

        List<RequestFeatures> sequence = List.of(
            featuresWithRate(2),
            featuresWithRate(2),
            featuresWithRate(3),
            featuresWithRate(3),
            featuresWithRate(4),
            featuresWithRate(10)
        );

        List<Double> controlScores = new ArrayList<>();
        for (RequestFeatures f : sequence) {
            controlScores.add(evaluate(control, ShadowScoringExecutor.disabled(), f).anomalyScore());
        }

        ShadowScoringExecutor shadow = ShadowScoringExecutor.of(
            ShadowScoringConfiguration.enabled(accepted, OptionalDouble.of(0.5)),
            ShadowCandidateBinding.of(loaded.scorer(), loaded.provenance()),
            ShadowObservationSink.NOOP
        );
        ModelLifecycleIdentity champion = ModelLifecycleIdentity.designatedReference(
            "statistical-reference", "state-v1", sha("fingerprint-state-v1"));
        ModelLifecycleIdentity challenger = ModelLifecycleIdentity.fromAccepted(accepted);
        ModelLifecycleManager manager = new ModelLifecycleManager();
        manager.designateChampion(champion);

        List<Double> treatedScores = new ArrayList<>();
        for (int i = 0; i < sequence.size(); i++) {
            treatedScores.add(evaluate(treated, shadow, sequence.get(i)).anomalyScore());
            if (i == 2) {
                manager.designateChallenger(new ChallengerDesignation(
                    challenger, champion, "state-challenge", "operator"));
            }
            if (i == 4) {
                ChampionChallengerComparison comparison = comparable(
                    champion, challenger, matrix(1, 1, 1, 1), matrix(2, 2, 0, 1));
                manager.bindComparison(comparison);
                manager.approvePromotion(comparison, PromotionEligibilityPolicy.defaults(),
                    "state-approve", "operator");
                manager.promote(champion);
            }
        }

        assertThat(treatedScores).containsExactlyElementsOf(controlScores);
        assertThat(manager.currentChampion().orElseThrow()).isEqualTo(challenger);
    }

    @Test
    void candidateShadowFailureDoesNotPunishAuthoritativeDecision() throws Exception {
        LoadedCandidateScorer loaded = loadReady("fail-cand", "1.0.0", "artifact-fail", 5L);
        AcceptedCandidateIdentity accepted =
            AcceptedCandidateIdentity.fromAcceptedProvenance(loaded.provenance());

        AnomalyScorer failingCandidate = new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                throw new IllegalStateException("candidate boom");
            }

            @Override
            public void update(RequestFeatures features) {
            }
        };

        AtomicReference<ShadowScoringObservation> observation = new AtomicReference<>();
        RiskDecision decision = evaluate(
            fixedScorer(0.18),
            ShadowScoringExecutor.of(
                ShadowScoringConfiguration.enabled(accepted),
                ShadowCandidateBinding.of(failingCandidate, loaded.provenance()),
                observation::set
            )
        );

        assertThat(decision).isNotNull();
        assertThat(decision.anomalyScore()).isEqualTo(0.18);
        assertThat(decision.action()).isEqualTo(EnforcementAction.ALLOW);
        assertThat(decision.action()).isNotIn(EnforcementAction.BLOCK, EnforcementAction.QUARANTINE);
        assertThat(observation.get()).isNotNull();
        assertThat(observation.get().status()).isEqualTo(ShadowScoringStatus.CANDIDATE_EXECUTION_FAILED);
        assertThat(observation.get().candidateScore()).isEmpty();
    }

    @Test
    void invalidCandidateShadowScoreDoesNotBecomeAuthoritativeOrPunitive() throws Exception {
        LoadedCandidateScorer loaded = loadReady("invalid-cand", "1.0.0", "artifact-invalid", 8L);
        AcceptedCandidateIdentity accepted =
            AcceptedCandidateIdentity.fromAcceptedProvenance(loaded.provenance());

        for (double invalid : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.5)) {
            AtomicReference<ShadowScoringObservation> last = new AtomicReference<>();
            RiskDecision decision = evaluate(
                fixedScorer(0.22),
                ShadowScoringExecutor.of(
                    ShadowScoringConfiguration.enabled(accepted),
                    ShadowCandidateBinding.of(fixedScorer(invalid), loaded.provenance()),
                    last::set
                )
            );
            assertThat(decision.anomalyScore()).isEqualTo(0.22);
            assertThat(decision.action()).isEqualTo(EnforcementAction.MONITOR);
            assertThat(decision.action()).isNotIn(EnforcementAction.BLOCK, EnforcementAction.QUARANTINE);
            assertThat(last.get()).isNotNull();
            assertThat(last.get().status()).isEqualTo(ShadowScoringStatus.CANDIDATE_INVALID_SCORE);
            assertThat(last.get().candidateScore()).isEmpty();
        }
    }

    @Test
    void eligibilityApprovalPromotionRemainDistinctAndRuntimeInert() {
        ModelLifecycleIdentity champion = ModelLifecycleIdentity.designatedReference(
            "statistical-reference", "gov-v1", sha("fingerprint-gov-v1"));
        ModelLifecycleIdentity challenger = ModelLifecycleIdentity.fromAccepted(new AcceptedCandidateIdentity(
            "gov-cand",
            "1.0.0",
            "gov-artifact",
            sha("digest-gov"),
            sha("config-gov")
        ));
        ModelLifecycleManager manager = new ModelLifecycleManager();
        AnomalyScorer production = fixedScorer(0.11);

        manager.designateChampion(champion);
        manager.designateChallenger(new ChallengerDesignation(
            challenger, champion, "gov-challenge", "operator"));
        ChampionChallengerComparison comparison = comparable(
            champion, challenger, matrix(1, 1, 1, 1), matrix(2, 2, 0, 1));
        manager.bindComparison(comparison);

        PromotionEligibilityAssessment eligibility = manager.assessEligibility(
            PromotionEligibilityPolicy.defaults(), comparison);
        assertThat(eligibility.eligible()).isTrue();
        assertThat(manager.currentChampion().orElseThrow()).isEqualTo(champion);
        assertThat(evaluate(production, ShadowScoringExecutor.disabled()).anomalyScore()).isEqualTo(0.11);

        manager.approvePromotion(comparison, PromotionEligibilityPolicy.defaults(),
            "gov-approve", "operator");
        assertThat(manager.pendingDecision().orElseThrow().status())
            .isEqualTo(ModelPromotionDecisionStatus.APPROVED);
        assertThat(manager.currentChampion().orElseThrow()).isEqualTo(champion);
        assertThat(evaluate(production, ShadowScoringExecutor.disabled()).anomalyScore()).isEqualTo(0.11);

        manager.promote(champion);
        assertThat(manager.currentChampion().orElseThrow()).isEqualTo(challenger);
        assertThat(evaluate(production, ShadowScoringExecutor.disabled()).anomalyScore()).isEqualTo(0.11);
        assertThat(evaluate(production, ShadowScoringExecutor.disabled()).action())
            .isEqualTo(EnforcementAction.ALLOW);
    }

    @Test
    void rollbackRestoresLifecycleChampionWithoutChangingRuntimeScorer(@TempDir Path root) {
        ModelLifecycleIdentity championA = ModelLifecycleIdentity.designatedReference(
            "statistical-reference", "rb-v1", sha("fingerprint-rb-v1"));
        ModelLifecycleIdentity challengerB = ModelLifecycleIdentity.fromAccepted(new AcceptedCandidateIdentity(
            "rb-cand",
            "1.0.0",
            "rb-artifact",
            sha("digest-rb"),
            sha("config-rb")
        ));
        ModelLifecycleManager manager = new ModelLifecycleManager(root);
        AnomalyScorer production = fixedScorer(0.14);

        manager.designateChampion(championA);
        promotePath(manager, championA, challengerB);
        assertThat(manager.currentChampion().orElseThrow()).isEqualTo(challengerB);
        assertThat(evaluate(production, ShadowScoringExecutor.disabled()).anomalyScore()).isEqualTo(0.14);

        String promotionId = manager.history().get(0).entryId();
        manager.rollback(promotionId, challengerB, "explicit rollback", "operator");
        assertThat(manager.currentChampion().orElseThrow()).isEqualTo(championA);
        assertThat(manager.history().stream().map(ModelLifecycleHistoryEntry::kind))
            .contains(ModelLifecycleHistoryEntry.Kind.PROMOTION, ModelLifecycleHistoryEntry.Kind.ROLLBACK);
        assertThat(evaluate(production, ShadowScoringExecutor.disabled()).anomalyScore()).isEqualTo(0.14);
        assertThat(evaluate(production, ShadowScoringExecutor.disabled()).action())
            .isEqualTo(EnforcementAction.ALLOW);
    }

    @Test
    void filesystemRestartPreservesLifecycleAndRejectsStaleApprovalWithoutRuntimeActivation(
        @TempDir Path root
    ) throws Exception {
        ModelLifecycleIdentity championA = ModelLifecycleIdentity.designatedReference(
            "statistical-reference", "restart-v1", sha("fingerprint-restart-v1"));
        ModelLifecycleIdentity challengerB = ModelLifecycleIdentity.fromAccepted(new AcceptedCandidateIdentity(
            "restart-cand",
            "1.0.0",
            "restart-artifact",
            sha("digest-restart"),
            sha("config-restart")
        ));
        AnomalyScorer production = fixedScorer(0.16);

        ModelLifecycleManager manager = new ModelLifecycleManager(root);
        manager.designateChampion(championA);
        manager.designateChallenger(new ChallengerDesignation(
            challengerB, championA, "restart-challenge", "operator"));
        ChampionChallengerComparison comparison = comparable(
            championA, challengerB, matrix(1, 1, 1, 1), matrix(2, 2, 0, 1));
        manager.bindComparison(comparison);
        manager.approvePromotion(comparison, PromotionEligibilityPolicy.defaults(),
            "restart-approve", "operator");

        ModelLifecycleManager reloaded = new ModelLifecycleManager(root);
        assertThat(reloaded.currentChampion().orElseThrow()).isEqualTo(championA);
        assertThat(reloaded.currentChallenger().orElseThrow().challenger()).isEqualTo(challengerB);
        assertThat(reloaded.pendingDecision().orElseThrow().status())
            .isEqualTo(ModelPromotionDecisionStatus.APPROVED);
        assertThat(evaluate(production, ShadowScoringExecutor.disabled()).anomalyScore()).isEqualTo(0.16);

        // Full comparison evidence must be re-bound after restart (by design).
        reloaded.bindComparison(comparison);
        reloaded.promote(championA);
        assertThat(reloaded.currentChampion().orElseThrow()).isEqualTo(challengerB);
        assertThat(evaluate(production, ShadowScoringExecutor.disabled()).anomalyScore()).isEqualTo(0.16);

        ModelLifecycleManager afterPromote = new ModelLifecycleManager(root);
        assertThat(afterPromote.currentChampion().orElseThrow()).isEqualTo(challengerB);
        assertThat(afterPromote.history()).isNotEmpty();
        assertThat(evaluate(production, ShadowScoringExecutor.disabled()).action())
            .isEqualTo(EnforcementAction.ALLOW);
    }

    @Test
    void acceptedCandidateDoesNotAutoEnableShadowOrDesignateChallenger() throws Exception {
        LoadedCandidateScorer loaded = loadReady("opt-in-cand", "1.0.0", "artifact-opt-in", 13L);
        AcceptedCandidateIdentity.fromAcceptedProvenance(loaded.provenance());

        AtomicInteger candidateCalls = new AtomicInteger();
        RiskDecision decision = evaluate(
            fixedScorer(0.2),
            ShadowScoringExecutor.of(
                ShadowScoringConfiguration.disabled(),
                ShadowCandidateBinding.of(countingScorer(0.95, candidateCalls), loaded.provenance()),
                ShadowObservationSink.NOOP
            )
        );
        assertThat(decision.anomalyScore()).isEqualTo(0.2);
        assertThat(candidateCalls.get()).isZero();

        ModelLifecycleManager manager = new ModelLifecycleManager();
        manager.designateChampion(ModelLifecycleIdentity.designatedReference(
            "statistical-reference", "opt-v1", sha("fingerprint-opt-v1")));
        assertThat(manager.currentChallenger()).isEmpty();
    }

    private static void promotePath(
        ModelLifecycleManager manager,
        ModelLifecycleIdentity champion,
        ModelLifecycleIdentity challenger
    ) {
        manager.designateChallenger(new ChallengerDesignation(
            challenger, champion, "challenge", "operator"));
        ChampionChallengerComparison comparison = comparable(
            champion, challenger, matrix(1, 1, 1, 1), matrix(2, 2, 0, 1));
        manager.bindComparison(comparison);
        manager.approvePromotion(comparison, PromotionEligibilityPolicy.defaults(),
            "explicit approve", "operator");
        manager.promote(champion);
    }

    private static ChampionChallengerComparison comparable(
        ModelLifecycleIdentity champion,
        ModelLifecycleIdentity challenger,
        DetectionConfusionMatrix championMatrix,
        DetectionConfusionMatrix challengerMatrix
    ) {
        return comparable(champion, challenger, championMatrix, challengerMatrix, null);
    }

    private static ChampionChallengerComparison comparable(
        ModelLifecycleIdentity champion,
        ModelLifecycleIdentity challenger,
        DetectionConfusionMatrix championMatrix,
        DetectionConfusionMatrix challengerMatrix,
        ShadowObservationSummary shadowSummary
    ) {
        return ChampionChallengerComparison.compare(
            champion,
            challenger,
            metrics("dataset-1", 0.5, championMatrix),
            metrics("dataset-1", 0.5, challengerMatrix),
            EvaluationEvidenceBinding.of("dataset-1", 0.5, CandidateEvaluationAcceptanceStatus.NOT_ASSESSED),
            EvaluationEvidenceBinding.of("dataset-1", 0.5, CandidateEvaluationAcceptanceStatus.ACCEPTED),
            shadowSummary
        );
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

    private static RiskDecision evaluate(AnomalyScorer authoritative, ShadowScoringExecutor shadow) {
        return evaluate(authoritative, shadow, features());
    }

    private static RiskDecision evaluate(
        AnomalyScorer authoritative,
        ShadowScoringExecutor shadow,
        RequestFeatures requestFeatures
    ) {
        SentinelDecisionEngine engine = new SentinelDecisionEngine(
            authoritative,
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
            shadow
        );
        RiskDecision decision = engine.evaluate(
            new MapHttpRequestView(),
            "identity-hash",
            requestFeatures,
            new RequestContext()
        );
        assertThat(decision).isNotNull();
        return decision;
    }

    private static LoadedCandidateScorer loadReady(
        String scorerId,
        String version,
        String artifactId,
        long seed
    ) throws Exception {
        byte[] payload = encodeIsolationForest(seed);
        ScorerArtifactDescriptor descriptor = ifDescriptor(scorerId, version, artifactId, payload);
        return CandidateScorerLoader.load(descriptor, payload).loaded().orElseThrow();
    }

    private static ScorerArtifactDescriptor ifDescriptor(
        String scorerId,
        String version,
        String artifactId,
        byte[] payload
    ) {
        return ScorerArtifactDescriptor.builder()
            .scorerId(scorerId)
            .scorerVersion(version)
            .artifactId(artifactId)
            .artifactFormat(ScorerArtifactDescriptor.FORMAT_AIF1)
            .scorerType(ScorerArtifactDescriptor.TYPE_ISOLATION_FOREST_V1)
            .artifactDigest(ArtifactDigest.sha256Hex(TrainingFingerprintHashes.sha256HexBytes(payload)))
            .featureSchemaVersion(FeatureSchema.VERSION_ID)
            .requiredFeatureNames(FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES)
            .declaredFeatureDimension(FeatureSchema.ISOLATION_FOREST_DIMENSION)
            .outputRange(ScorerOutputRange.unitInterval())
            .capabilities(new ScorerArtifactCapabilities(false, false, true))
            .build();
    }

    private static byte[] encodeIsolationForest(long seed) throws Exception {
        IsolationForestTrainer trainer = new IsolationForestTrainer(5, 3, seed);
        IsolationForestModel model = trainer.train(List.of(
            new double[] {1, 2, 3, 4, 5},
            new double[] {2, 2, 2, 2, 2},
            new double[] {3, 3, 3, 3, 3}
        ));
        return IsolationForestModelCodec.encode(model);
    }

    private static AnomalyScorer fixedScorer(double value) {
        return new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                return value;
            }

            @Override
            public void update(RequestFeatures features) {
            }
        };
    }

    private static AnomalyScorer countingScorer(double value, AtomicInteger calls) {
        return new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                calls.incrementAndGet();
                return value;
            }

            @Override
            public void update(RequestFeatures features) {
            }
        };
    }

    private static RequestFeatures features() {
        return featuresWithRate(2);
    }

    private static RequestFeatures featuresWithRate(int requestsPerWindow) {
        return RequestFeatures.builder()
            .identityHash("id")
            .endpoint("/api")
            .timestampMillis(1_700_000_000_000L)
            .requestsPerWindow(requestsPerWindow)
            .endpointEntropy(0.5)
            .endpointConcentration(0.5)
            .tokenAgeSeconds(60)
            .parameterCount(1)
            .payloadSizeBytes(100)
            .headerFingerprintHash(0)
            .ipBucket(0)
            .build();
    }

    private static String sha(String material) {
        return TrainingFingerprintHashes.sha256HexUtf8(material);
    }

    private static Path trackedDatasetDirectory() {
        return locateTrackedPath(ReferenceDatasetGenerator.TRACKED_DATASET_DIRECTORY);
    }

    private static Path trackedAnnotationsFile() {
        return locateTrackedPath(ReferenceDatasetGenerator.TRACKED_ANNOTATIONS_FILE);
    }

    private static Path locateTrackedPath(Path relativePath) {
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
