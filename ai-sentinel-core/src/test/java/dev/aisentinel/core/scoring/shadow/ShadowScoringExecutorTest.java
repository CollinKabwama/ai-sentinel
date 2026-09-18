package dev.aisentinel.core.scoring.shadow;

import dev.aisentinel.core.baseline.BaselineLifecycle;
import dev.aisentinel.core.baseline.ConfigurableBaselineUpdatePolicy;
import dev.aisentinel.core.SentinelPipeline;
import dev.aisentinel.core.decision.RiskDecision;
import dev.aisentinel.core.decision.SentinelDecisionEngine;
import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementResponse;
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
import dev.aisentinel.distributed.training.TrainingFingerprintHashes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Authority, eligibility, comparison, isolation, and telemetry regressions for shadow scoring.
 */
class ShadowScoringExecutorTest {

    @Test
    void disabledNeverInvokesCandidate() {
        AtomicInteger calls = new AtomicInteger();
        AnomalyScorer candidate = countingScorer(0.9, calls);
        LoadedCandidateScorer loaded = loadReady();
        ShadowScoringExecutor executor = ShadowScoringExecutor.of(
            ShadowScoringConfiguration.disabled(),
            ShadowCandidateBinding.of(candidate, loaded.provenance()),
            ShadowObservationSink.NOOP
        );

        ShadowScoringObservation obs = executor.observe(0.1, features(), "corr-1");

        assertThat(obs.status()).isEqualTo(ShadowScoringStatus.DISABLED);
        assertThat(obs.candidateScore()).isEmpty();
        assertThat(calls.get()).isZero();
    }

    @Test
    void acceptedIdentityPresentButShadowDisabledDoesNotExecuteCandidate() {
        AtomicInteger calls = new AtomicInteger();
        LoadedCandidateScorer loaded = loadReady();
        // Operator has an ACCEPTED identity binding available, but shadow config stays off.
        AcceptedCandidateIdentity.fromAcceptedProvenance(loaded.provenance());
        ShadowScoringExecutor executor = ShadowScoringExecutor.of(
            ShadowScoringConfiguration.disabled(),
            ShadowCandidateBinding.of(countingScorer(0.99, calls), loaded.provenance()),
            ShadowObservationSink.NOOP
        );

        ShadowScoringObservation obs = executor.observe(0.2, features(), "corr");

        assertThat(obs.status()).isEqualTo(ShadowScoringStatus.DISABLED);
        assertThat(calls.get()).isZero();
    }

    @Test
    void enabledRequiresAcceptedIdentity() {
        assertThatThrownBy(() -> ShadowScoringConfiguration.enabled(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void eligibleCandidateProducesComparison() {
        LoadedCandidateScorer loaded = loadReady();
        AnomalyScorer fixed = fixedScorer(0.8);
        ShadowScoringExecutor executor = enabledExecutor(
            ShadowCandidateBinding.of(fixed, loaded.provenance()),
            AcceptedCandidateIdentity.fromAcceptedProvenance(loaded.provenance()),
            OptionalDouble.of(0.5),
            ShadowObservationSink.NOOP
        );

        ShadowScoringObservation obs = executor.observe(0.2, features(), "corr");

        assertThat(obs.status()).isEqualTo(ShadowScoringStatus.SCORED);
        assertThat(obs.candidateScore()).hasValue(0.8);
        assertThat(obs.comparison()).isPresent();
        ShadowScoreComparison comparison = obs.comparison().orElseThrow();
        assertThat(comparison.authoritativeScore()).isEqualTo(0.2);
        assertThat(comparison.candidateScore()).isEqualTo(0.8);
        assertThat(comparison.delta()).isCloseTo(0.6, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(comparison.absoluteDelta()).isCloseTo(0.6, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(comparison.classificationAgreement())
            .contains(ShadowClassificationAgreement.CANDIDATE_ONLY_ANOMALOUS);
        assertThat(obs.candidateProvenance()).isPresent();
        assertThat(obs.candidateProvenance().orElseThrow().scorerId()).isEqualTo(loaded.provenance().scorerId());
        assertThat(obs.candidateProvenance().orElseThrow().verifiedDigestHex())
            .isEqualTo(loaded.provenance().verifiedDigestHex());
        assertThat(obs.candidateProvenance().orElseThrow().configurationFingerprintSha256Hex())
            .isEqualTo(loaded.provenance().configurationFingerprintSha256Hex());
    }

    @Test
    void classificationAgreements() {
        LoadedCandidateScorer loaded = loadReady();
        AcceptedCandidateIdentity identity = AcceptedCandidateIdentity.fromAcceptedProvenance(loaded.provenance());

        assertAgreement(loaded, identity, 0.1, 0.2, ShadowClassificationAgreement.AGREE_NORMAL);
        assertAgreement(loaded, identity, 0.8, 0.9, ShadowClassificationAgreement.AGREE_ANOMALOUS);
        assertAgreement(loaded, identity, 0.8, 0.1, ShadowClassificationAgreement.AUTHORITATIVE_ONLY_ANOMALOUS);
        assertAgreement(loaded, identity, 0.1, 0.8, ShadowClassificationAgreement.CANDIDATE_ONLY_ANOMALOUS);
    }

    @Test
    void diagnosticClassificationThresholdUsesInclusiveBoundary() {
        LoadedCandidateScorer loaded = loadReady();
        AcceptedCandidateIdentity identity = AcceptedCandidateIdentity.fromAcceptedProvenance(loaded.provenance());

        assertAgreement(loaded, identity, 0.0, 0.0, ShadowClassificationAgreement.AGREE_ANOMALOUS, 0.0);
        assertAgreement(loaded, identity, 1.0, 1.0, ShadowClassificationAgreement.AGREE_ANOMALOUS, 1.0);
        assertAgreement(loaded, identity, 0.5, 0.5, ShadowClassificationAgreement.AGREE_ANOMALOUS, 0.5);
        assertAgreement(loaded, identity, 0.499999, 0.5,
            ShadowClassificationAgreement.CANDIDATE_ONLY_ANOMALOUS, 0.5);
        assertAgreement(loaded, identity, 0.5, 0.499999,
            ShadowClassificationAgreement.AUTHORITATIVE_ONLY_ANOMALOUS, 0.5);
    }

    @Test
    void invalidDiagnosticThresholdsAreRejectedWithoutClamping() {
        LoadedCandidateScorer loaded = loadReady();
        AcceptedCandidateIdentity identity = AcceptedCandidateIdentity.fromAcceptedProvenance(loaded.provenance());

        for (double invalid : new double[] {
            -0.1,
            1.1,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY
        }) {
            assertThatThrownBy(() -> ShadowScoringConfiguration.enabled(identity, OptionalDouble.of(invalid)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("diagnosticAnomalyThreshold");
        }
    }

    @Test
    void boundaryScoresZeroAndOne() {
        LoadedCandidateScorer loaded = loadReady();
        ShadowScoringExecutor executor = enabledExecutor(
            ShadowCandidateBinding.of(fixedScorer(0.0), loaded.provenance()),
            AcceptedCandidateIdentity.fromAcceptedProvenance(loaded.provenance()),
            OptionalDouble.empty(),
            ShadowObservationSink.NOOP
        );

        ShadowScoreComparison c0 = executor.observe(0.0, features(), null).comparison().orElseThrow();
        assertThat(c0.delta()).isEqualTo(0.0);
        assertThat(c0.absoluteDelta()).isEqualTo(0.0);

        ShadowScoringExecutor high = enabledExecutor(
            ShadowCandidateBinding.of(fixedScorer(1.0), loaded.provenance()),
            AcceptedCandidateIdentity.fromAcceptedProvenance(loaded.provenance()),
            OptionalDouble.empty(),
            ShadowObservationSink.NOOP
        );
        ShadowScoreComparison c1 = high.observe(1.0, features(), null).comparison().orElseThrow();
        assertThat(c1.delta()).isEqualTo(0.0);
    }

    @Test
    void finiteCandidateScoreAboveOneIsRangeClampedLikeAuthoritativePath() {
        LoadedCandidateScorer loaded = loadReady();
        ShadowScoringObservation obs = enabledExecutor(
            ShadowCandidateBinding.of(fixedScorer(1.7), loaded.provenance()),
            AcceptedCandidateIdentity.fromAcceptedProvenance(loaded.provenance()),
            OptionalDouble.of(0.5),
            ShadowObservationSink.NOOP
        ).observe(2.4, features(), null);

        assertThat(obs.status()).isEqualTo(ShadowScoringStatus.SCORED);
        assertThat(obs.candidateScore()).hasValue(1.0);
        ShadowScoreComparison comparison = obs.comparison().orElseThrow();
        assertThat(comparison.authoritativeScore()).isEqualTo(1.0);
        assertThat(comparison.candidateScore()).isEqualTo(1.0);
        assertThat(comparison.delta()).isEqualTo(0.0);
    }

    @Test
    void identityMismatchDoesNotScoreCandidate() {
        LoadedCandidateScorer loadedA = loadReady("cand-a", "1.0.0", "artifact-a");
        LoadedCandidateScorer loadedB = loadReady("cand-b", "1.0.0", "artifact-b");
        AtomicInteger calls = new AtomicInteger();
        ShadowScoringExecutor executor = enabledExecutor(
            ShadowCandidateBinding.of(countingScorer(0.9, calls), loadedB.provenance()),
            AcceptedCandidateIdentity.fromAcceptedProvenance(loadedA.provenance()),
            OptionalDouble.empty(),
            ShadowObservationSink.NOOP
        );

        ShadowScoringObservation obs = executor.observe(0.1, features(), "corr");

        assertThat(obs.status()).isEqualTo(ShadowScoringStatus.NOT_ELIGIBLE);
        assertThat(obs.ineligibilityReason()).contains(ShadowIneligibilityReason.IDENTITY_MISMATCH);
        assertThat(obs.candidateScore()).isEmpty();
        assertThat(calls.get()).isZero();
    }

    @Test
    void versionMismatchDoesNotScoreCandidate() {
        LoadedCandidateScorer loadedA = loadReady("cand-a", "1.0.0", "artifact-a");
        LoadedCandidateScorer loadedB = loadReady("cand-a", "2.0.0", "artifact-a");
        AtomicInteger calls = new AtomicInteger();
        ShadowScoringExecutor executor = enabledExecutor(
            ShadowCandidateBinding.of(countingScorer(0.9, calls), loadedB.provenance()),
            AcceptedCandidateIdentity.fromAcceptedProvenance(loadedA.provenance()),
            OptionalDouble.empty(),
            ShadowObservationSink.NOOP
        );

        assertThat(executor.observe(0.1, features(), "corr").status())
            .isEqualTo(ShadowScoringStatus.NOT_ELIGIBLE);
        assertThat(calls.get()).isZero();
    }

    @Test
    void digestMismatchDoesNotScoreCandidate() {
        LoadedCandidateScorer loaded = loadReady();
        AcceptedCandidateIdentity mismatched = new AcceptedCandidateIdentity(
            loaded.provenance().scorerId(),
            loaded.provenance().scorerVersion(),
            loaded.provenance().artifactId(),
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            loaded.provenance().configurationFingerprintSha256Hex()
        );
        AtomicInteger calls = new AtomicInteger();
        ShadowScoringExecutor executor = enabledExecutor(
            ShadowCandidateBinding.of(countingScorer(0.7, calls), loaded.provenance()),
            mismatched,
            OptionalDouble.empty(),
            ShadowObservationSink.NOOP
        );

        assertThat(executor.observe(0.1, features(), null).status())
            .isEqualTo(ShadowScoringStatus.NOT_ELIGIBLE);
        assertThat(calls.get()).isZero();
    }

    @Test
    void fingerprintMismatchDoesNotScoreCandidate() {
        LoadedCandidateScorer loaded = loadReady();
        AcceptedCandidateIdentity mismatched = new AcceptedCandidateIdentity(
            loaded.provenance().scorerId(),
            loaded.provenance().scorerVersion(),
            loaded.provenance().artifactId(),
            loaded.provenance().verifiedDigestHex(),
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        );
        AtomicInteger calls = new AtomicInteger();
        ShadowScoringExecutor executor = enabledExecutor(
            ShadowCandidateBinding.of(countingScorer(0.7, calls), loaded.provenance()),
            mismatched,
            OptionalDouble.empty(),
            ShadowObservationSink.NOOP
        );

        assertThat(executor.observe(0.1, features(), null).ineligibilityReason())
            .contains(ShadowIneligibilityReason.IDENTITY_MISMATCH);
        assertThat(calls.get()).isZero();
    }

    @Test
    void missingCandidateIsUnavailable() {
        LoadedCandidateScorer loaded = loadReady();
        ShadowScoringExecutor executor = ShadowScoringExecutor.of(
            ShadowScoringConfiguration.enabled(
                AcceptedCandidateIdentity.fromAcceptedProvenance(loaded.provenance())),
            null,
            ShadowObservationSink.NOOP
        );

        assertThat(executor.observe(0.1, features(), null).status())
            .isEqualTo(ShadowScoringStatus.CANDIDATE_UNAVAILABLE);
    }

    @Test
    void invalidCandidateScoresAreContained() {
        LoadedCandidateScorer loaded = loadReady();
        AcceptedCandidateIdentity identity = AcceptedCandidateIdentity.fromAcceptedProvenance(loaded.provenance());

        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1}) {
            ShadowScoringObservation obs = enabledExecutor(
                ShadowCandidateBinding.of(fixedScorer(invalid), loaded.provenance()),
                identity,
                OptionalDouble.empty(),
                ShadowObservationSink.NOOP
            ).observe(0.4, features(), null);

            assertThat(obs.status()).isEqualTo(ShadowScoringStatus.CANDIDATE_INVALID_SCORE);
            assertThat(obs.candidateScore()).isEmpty();
            assertThat(obs.comparison()).isEmpty();
        }
    }

    @Test
    void candidateExecutionFailureIsContained() {
        LoadedCandidateScorer loaded = loadReady();
        AnomalyScorer failing = new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void update(RequestFeatures features) {
            }
        };
        ShadowScoringObservation obs = enabledExecutor(
            ShadowCandidateBinding.of(failing, loaded.provenance()),
            AcceptedCandidateIdentity.fromAcceptedProvenance(loaded.provenance()),
            OptionalDouble.empty(),
            ShadowObservationSink.NOOP
        ).observe(0.3, features(), "corr");

        assertThat(obs.status()).isEqualTo(ShadowScoringStatus.CANDIDATE_EXECUTION_FAILED);
        assertThat(obs.candidateScore()).isEmpty();
        assertThat(obs.failureDetail()).isPresent();
    }

    @Test
    void authoritativeInvalidStillRecordsCandidateWithoutComparison() {
        LoadedCandidateScorer loaded = loadReady();
        ShadowScoringObservation obs = enabledExecutor(
            ShadowCandidateBinding.of(fixedScorer(0.55), loaded.provenance()),
            AcceptedCandidateIdentity.fromAcceptedProvenance(loaded.provenance()),
            OptionalDouble.of(0.5),
            ShadowObservationSink.NOOP
        ).observe(Double.NaN, features(), null);

        assertThat(obs.status()).isEqualTo(ShadowScoringStatus.SCORED);
        assertThat(obs.candidateScore()).hasValue(0.55);
        assertThat(obs.comparison()).isEmpty();
    }

    @Test
    void sinkFailureDoesNotPropagate() {
        LoadedCandidateScorer loaded = loadReady();
        ShadowObservationSink exploding = observation -> {
            throw new RuntimeException("sink down");
        };
        ShadowScoringObservation obs = enabledExecutor(
            ShadowCandidateBinding.of(fixedScorer(0.4), loaded.provenance()),
            AcceptedCandidateIdentity.fromAcceptedProvenance(loaded.provenance()),
            OptionalDouble.empty(),
            exploding
        ).observe(0.3, features(), null);

        assertThat(obs.status()).isEqualTo(ShadowScoringStatus.SCORED);
    }

    @Test
    void collectingSinkReceivesImmutableObservations() {
        LoadedCandidateScorer loaded = loadReady();
        List<ShadowScoringObservation> collected = new ArrayList<>();
        ShadowScoringExecutor executor = enabledExecutor(
            ShadowCandidateBinding.of(fixedScorer(0.6), loaded.provenance()),
            AcceptedCandidateIdentity.fromAcceptedProvenance(loaded.provenance()),
            OptionalDouble.empty(),
            collected::add
        );

        ShadowScoringObservation first = executor.observe(0.1, features(), "a");
        ShadowScoringObservation second = executor.observe(0.2, features(), "b");

        assertThat(collected).containsExactly(first, second);
        assertThat(first.comparison()).isPresent();
        assertThat(second.comparison()).isPresent();
    }

    @Test
    void deterministicComparisonForSameInputs() {
        LoadedCandidateScorer loaded = loadReady();
        ShadowScoringExecutor executor = enabledExecutor(
            ShadowCandidateBinding.of(fixedScorer(0.42), loaded.provenance()),
            AcceptedCandidateIdentity.fromAcceptedProvenance(loaded.provenance()),
            OptionalDouble.of(0.5),
            ShadowObservationSink.NOOP
        );
        RequestFeatures f = features();

        ShadowScoringObservation a = executor.observe(0.31, f, "same");
        ShadowScoringObservation b = executor.observe(0.31, f, "same");

        assertThat(a.status()).isEqualTo(b.status());
        assertThat(a.candidateScore()).isEqualTo(b.candidateScore());
        assertThat(a.comparison()).isEqualTo(b.comparison());
    }

    @Test
    void candidateUpdateIsNeverInvokedByShadowExecutor() {
        LoadedCandidateScorer loaded = loadReady();
        AtomicInteger updates = new AtomicInteger();
        AnomalyScorer candidate = new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                return 0.5;
            }

            @Override
            public void update(RequestFeatures features) {
                updates.incrementAndGet();
            }
        };
        enabledExecutor(
            ShadowCandidateBinding.of(candidate, loaded.provenance()),
            AcceptedCandidateIdentity.fromAcceptedProvenance(loaded.provenance()),
            OptionalDouble.empty(),
            ShadowObservationSink.NOOP
        ).observe(0.1, features(), null);

        assertThat(updates.get()).isZero();
    }

    @Test
    void authoritativeDecisionUnchangedWhenShadowEnabledWithRadicallyDifferentCandidate() {
        FixedAnomalyScorer authoritative = new FixedAnomalyScorer(0.1);
        LoadedCandidateScorer loaded = loadReady();
        ShadowCandidateBinding highRiskCandidate =
            ShadowCandidateBinding.of(fixedScorer(0.99), loaded.provenance());
        ShadowScoringExecutor enabled = enabledExecutor(
            highRiskCandidate,
            AcceptedCandidateIdentity.fromAcceptedProvenance(loaded.provenance()),
            OptionalDouble.of(0.5),
            ShadowObservationSink.NOOP
        );

        RiskDecision disabledDecision = evaluate(authoritative, ShadowScoringExecutor.disabled());
        RiskDecision enabledDecision = evaluate(authoritative, enabled);

        assertAuthoritativeEquivalent(disabledDecision, enabledDecision);
        assertThat(enabledDecision.action()).isEqualTo(EnforcementAction.ALLOW);
        ShadowScoringObservation shadow = enabledDecision.context()
            .get(ShadowContextKeys.SHADOW_OBSERVATION, ShadowScoringObservation.class);
        assertThat(shadow.status()).isEqualTo(ShadowScoringStatus.SCORED);
        assertThat(shadow.candidateScore()).hasValue(0.99);
    }

    @Test
    void authoritativeDecisionUnchangedWhenCandidateMuchLower() {
        FixedAnomalyScorer authoritative = new FixedAnomalyScorer(0.9);
        LoadedCandidateScorer loaded = loadReady();
        ShadowScoringExecutor enabled = enabledExecutor(
            ShadowCandidateBinding.of(fixedScorer(0.01), loaded.provenance()),
            AcceptedCandidateIdentity.fromAcceptedProvenance(loaded.provenance()),
            OptionalDouble.of(0.5),
            ShadowObservationSink.NOOP
        );

        RiskDecision disabledDecision = evaluate(authoritative, ShadowScoringExecutor.disabled());
        RiskDecision enabledDecision = evaluate(authoritative, enabled);

        assertAuthoritativeEquivalent(disabledDecision, enabledDecision);
        assertThat(enabledDecision.action()).isEqualTo(EnforcementAction.QUARANTINE);
    }

    @Test
    void candidateFailureDoesNotChangeAuthoritativeDecision() {
        FixedAnomalyScorer authoritative = new FixedAnomalyScorer(0.2);
        LoadedCandidateScorer loaded = loadReady();
        AnomalyScorer failing = new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                throw new RuntimeException("candidate down");
            }

            @Override
            public void update(RequestFeatures features) {
            }
        };
        ShadowScoringExecutor enabled = enabledExecutor(
            ShadowCandidateBinding.of(failing, loaded.provenance()),
            AcceptedCandidateIdentity.fromAcceptedProvenance(loaded.provenance()),
            OptionalDouble.empty(),
            ShadowObservationSink.NOOP
        );

        assertAuthoritativeEquivalent(
            evaluate(authoritative, ShadowScoringExecutor.disabled()),
            evaluate(authoritative, enabled)
        );
    }

    @Test
    void candidateInvalidScoreDoesNotChangeAuthoritativeDecision() {
        FixedAnomalyScorer authoritative = new FixedAnomalyScorer(0.25);
        LoadedCandidateScorer loaded = loadReady();
        ShadowScoringExecutor enabled = enabledExecutor(
            ShadowCandidateBinding.of(fixedScorer(Double.NaN), loaded.provenance()),
            AcceptedCandidateIdentity.fromAcceptedProvenance(loaded.provenance()),
            OptionalDouble.empty(),
            observation -> {
                throw new IllegalStateException("sink must not affect authority");
            }
        );

        assertAuthoritativeEquivalent(
            evaluate(authoritative, ShadowScoringExecutor.disabled()),
            evaluate(authoritative, enabled)
        );
    }

    @Test
    void shadowDoesNotMutateAuthoritativeStatisticalBaseline() {
        StatisticalScorer authoritative = new StatisticalScorer();
        RequestFeatures f = features();
        // Warm the baseline once so subsequent scores are stable enough to compare.
        for (int i = 0; i < 20; i++) {
            authoritative.update(f);
        }
        double beforeShadowPath = authoritative.score(f);

        LoadedCandidateScorer loaded = loadReady();
        ShadowScoringExecutor enabled = enabledExecutor(
            ShadowCandidateBinding.of(fixedScorer(0.95), loaded.provenance()),
            AcceptedCandidateIdentity.fromAcceptedProvenance(loaded.provenance()),
            OptionalDouble.empty(),
            ShadowObservationSink.NOOP
        );
        // Shadow observe must not call authoritative.update
        enabled.observe(beforeShadowPath, f, null);
        assertThat(authoritative.score(f)).isEqualTo(beforeShadowPath);

        // Decision path with shadow still updates only via authoritative policy — same as disabled.
        StatisticalScorer a = new StatisticalScorer();
        StatisticalScorer b = new StatisticalScorer();
        for (int i = 0; i < 5; i++) {
            evaluate(a, ShadowScoringExecutor.disabled());
            evaluate(b, enabled);
        }
        assertThat(a.score(f)).isEqualTo(b.score(f));
    }

    @Test
    void legacyDecisionEngineConstructorDefaultsShadowDisabled() {
        AtomicInteger authoritativeCalls = new AtomicInteger();
        SentinelDecisionEngine engine = new SentinelDecisionEngine(
            countingScorer(0.1, authoritativeCalls),
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            NEVER_QUARANTINED,
            event -> {
            },
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE
        );

        RiskDecision decision = engine.evaluate(
            new MapHttpRequestView(),
            "identity-hash",
            features(),
            new RequestContext()
        );

        assertThat(decision).isNotNull();
        assertThat(decision.context().get(ShadowContextKeys.SHADOW_OBSERVATION, ShadowScoringObservation.class))
            .isNull();
        assertThat(authoritativeCalls.get()).isEqualTo(1);
    }

    @Test
    void legacyPipelineConstructorDefaultsShadowDisabled() {
        AtomicInteger authoritativeCalls = new AtomicInteger();
        SentinelPipeline pipeline = new SentinelPipeline(
            (request, identityHash, ctx) -> features(),
            countingScorer(0.1, authoritativeCalls),
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            new EnforcementHandler() {
                @Override
                public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                                     String identityHash, String endpoint) {
                    return true;
                }

                @Override
                public boolean isQuarantined(String identityHash, String endpoint) {
                    return false;
                }
            },
            event -> {
            },
            StartupGrace.NEVER,
            SentinelMetrics.NOOP
        );

        assertThat(pipeline.process(new MapHttpRequestView(), NOOP_RESPONSE, "identity-hash"))
            .isTrue();
        assertThat(authoritativeCalls.get()).isEqualTo(1);
    }

    private static void assertAgreement(
        LoadedCandidateScorer loaded,
        AcceptedCandidateIdentity identity,
        double authoritative,
        double candidate,
        ShadowClassificationAgreement expected
    ) {
        assertAgreement(loaded, identity, authoritative, candidate, expected, 0.5);
    }

    private static void assertAgreement(
        LoadedCandidateScorer loaded,
        AcceptedCandidateIdentity identity,
        double authoritative,
        double candidate,
        ShadowClassificationAgreement expected,
        double threshold
    ) {
        ShadowScoringObservation obs = enabledExecutor(
            ShadowCandidateBinding.of(fixedScorer(candidate), loaded.provenance()),
            identity,
            OptionalDouble.of(threshold),
            ShadowObservationSink.NOOP
        ).observe(authoritative, features(), null);
        assertThat(obs.comparison().orElseThrow().classificationAgreement()).contains(expected);
    }

    private static void assertAuthoritativeEquivalent(RiskDecision disabled, RiskDecision enabled) {
        assertThat(enabled.action()).isEqualTo(disabled.action());
        assertThat(enabled.anomalyScore()).isEqualTo(disabled.anomalyScore());
        assertThat(enabled.policyScore()).isEqualTo(disabled.policyScore());
        assertThat(enabled.evaluationStatuses()).isEqualTo(disabled.evaluationStatuses());
        assertThat(enabled.startupGraceActive()).isEqualTo(disabled.startupGraceActive());
    }

    private static RiskDecision evaluate(AnomalyScorer authoritative, ShadowScoringExecutor shadow) {
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
            features(),
            new RequestContext()
        );
        assertThat(decision).isNotNull();
        return decision;
    }

    private static ShadowScoringExecutor enabledExecutor(
        ShadowCandidateBinding binding,
        AcceptedCandidateIdentity identity,
        OptionalDouble threshold,
        ShadowObservationSink sink
    ) {
        return ShadowScoringExecutor.of(
            ShadowScoringConfiguration.enabled(identity, threshold),
            binding,
            sink
        );
    }

    private static LoadedCandidateScorer loadReady() {
        return loadReady("research-if-candidate", "1.0.0", "artifact-1");
    }

    private static LoadedCandidateScorer loadReady(String scorerId, String version, String artifactId) {
        try {
            byte[] payload = encodeTrainedIsolationForest();
            ScorerArtifactDescriptor descriptor = ScorerArtifactDescriptor.builder()
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
            return CandidateScorerLoader.load(descriptor, payload).loaded().orElseThrow();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] encodeTrainedIsolationForest() throws Exception {
        IsolationForestTrainer trainer = new IsolationForestTrainer(5, 3, 42L);
        IsolationForestModel model = trainer.train(List.of(
            new double[] {1, 2, 3, 4, 5},
            new double[] {2, 2, 2, 2, 2},
            new double[] {3, 3, 3, 3, 3}
        ));
        return IsolationForestModelCodec.encode(model);
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

    private static final class FixedAnomalyScorer implements AnomalyScorer {
        private final double value;

        private FixedAnomalyScorer(double value) {
            this.value = value;
        }

        @Override
        public double score(RequestFeatures features) {
            return value;
        }

        @Override
        public void update(RequestFeatures features) {
        }
    }

    private static final EnforcementHandler NEVER_QUARANTINED = new EnforcementHandler() {
        @Override
        public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                             String identityHash, String endpoint) {
            throw new AssertionError("decision engine must not apply enforcement");
        }

        @Override
        public boolean isQuarantined(String identityHash, String endpoint) {
            return false;
        }
    };

    private static final EnforcementResponse NOOP_RESPONSE = new EnforcementResponse() {
        @Override
        public void setStatus(int statusCode) {
        }

        @Override
        public void setContentType(String contentType) {
        }

        @Override
        public void writeBody(String body) {
        }
    };
}
