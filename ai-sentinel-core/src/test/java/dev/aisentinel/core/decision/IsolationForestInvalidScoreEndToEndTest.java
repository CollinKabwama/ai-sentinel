package dev.aisentinel.core.decision;

import dev.aisentinel.core.enforcement.CompositeEnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.http.MapHttpRequestView;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.NoopTrustPolicyAdjuster;
import dev.aisentinel.core.policy.PolicyEngine;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.scoring.BoundedTrainingBuffer;
import dev.aisentinel.core.scoring.CompositeScorer;
import dev.aisentinel.core.scoring.IsolationForestConfig;
import dev.aisentinel.core.scoring.IsolationForestModel;
import dev.aisentinel.core.scoring.IsolationForestScorer;
import dev.aisentinel.core.scoring.StatisticalScorer;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Decisive end-to-end regression for the independent-review P0 bypass: a loaded Isolation
 * Forest model returning {@code +Infinity} (or any invalid value) must reach the decision
 * engine as an invalid value and become {@code INVALID_SCORE → ALLOW} — never a
 * valid-looking {@code 1.0 → QUARANTINE}. Legitimate high IF scores keep normal policy behavior.
 */
class IsolationForestInvalidScoreEndToEndTest {

    private static final class CountingPolicy implements PolicyEngine {
        private final PolicyEngine delegate = new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8);
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public EnforcementAction evaluate(double riskScore, RequestFeatures features, String endpoint) {
            invocations.incrementAndGet();
            return delegate.evaluate(riskScore, features, endpoint);
        }
    }

    private static final class RecordingMetrics implements SentinelMetrics {
        private final AtomicInteger invalidRejected = new AtomicInteger();

        @Override
        public void recordInvalidScoreRejected() {
            invalidRejected.incrementAndGet();
        }
    }

    private static RequestFeatures features() {
        return RequestFeatures.builder()
            .identityHash("h")
            .endpoint("/api")
            .timestampMillis(1L)
            .requestsPerWindow(1)
            .endpointEntropy(0)
            .tokenAgeSeconds(60)
            .parameterCount(0)
            .payloadSizeBytes(0)
            .headerFingerprintHash(0)
            .ipBucket(0)
            .build();
    }

    private record ScorerWithBuffer(IsolationForestScorer scorer, BoundedTrainingBuffer buffer) {
    }

    private static ScorerWithBuffer ifScorerWithModelReturning(double modelScore) throws Exception {
        var buffer = new BoundedTrainingBuffer(100);
        var config = new IsolationForestConfig(0.42, 50, 10, 5, 42L, 1.0);
        var scorer = new IsolationForestScorer(buffer, config);
        IsolationForestModel model = mock(IsolationForestModel.class);
        when(model.score(any(double[].class))).thenReturn(modelScore);
        var modelField = IsolationForestScorer.class.getDeclaredField("model");
        modelField.setAccessible(true);
        modelField.set(scorer, model);
        return new ScorerWithBuffer(scorer, buffer);
    }

    private static SentinelDecisionEngine engine(AnomalyScorer scorer,
                                                 PolicyEngine policy,
                                                 EnforcementHandler quarantine,
                                                 SentinelMetrics metrics) {
        return new SentinelDecisionEngine(
            scorer,
            policy,
            quarantine,
            mock(TelemetryEmitter.class),
            StartupGrace.NEVER,
            metrics,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE);
    }

    private static EnforcementHandler notQuarantined() {
        return new EnforcementHandler() {
            @Override
            public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                                 String identityHash, String endpoint) {
                throw new AssertionError("engine must not apply enforcement");
            }

            @Override
            public boolean isQuarantined(String identityHash, String endpoint) {
                return false;
            }
        };
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN, -0.7})
    void invalidIsolationForestModelScoreBecomesInvalidScoreAllow(double modelScore) throws Exception {
        ScorerWithBuffer sb = ifScorerWithModelReturning(modelScore);
        CountingPolicy policy = new CountingPolicy();
        RecordingMetrics metrics = new RecordingMetrics();

        RiskDecision decision = engine(sb.scorer(), policy, notQuarantined(), metrics)
            .evaluate(new MapHttpRequestView(), "h", features(), new RequestContext());

        assertThat(decision).isNotNull();
        assertThat(decision.hasStatus(EvaluationStatus.INVALID_SCORE)).isTrue();
        assertThat(decision.hasStatus(EvaluationStatus.MODEL_FALLBACK_USED)).isTrue();
        assertThat(decision.hasStatus(EvaluationStatus.COMPLETE)).isFalse();
        assertThat(decision.action()).isEqualTo(EnforcementAction.ALLOW);
        assertThat(Double.isNaN(decision.anomalyScore())).isTrue();
        assertThat(Double.isNaN(decision.policyScore())).isTrue();
        // No threshold-policy max-risk enforcement, no baseline update, invalid metric recorded.
        assertThat(policy.invocations.get()).isZero();
        assertThat(sb.buffer().size()).isZero();
        assertThat(sb.scorer().getAcceptedTrainingSampleCount()).isZero();
        assertThat(metrics.invalidRejected.get()).isEqualTo(1);
    }

    @Test
    void invalidIsolationForestScoreCannotCreateQuarantineEntry() throws Exception {
        CompositeEnforcementHandler handler = new CompositeEnforcementHandler(
            403, 60_000L, 10.0, mock(TelemetryEmitter.class));
        ScorerWithBuffer sb = ifScorerWithModelReturning(Double.POSITIVE_INFINITY);

        RiskDecision decision = engine(sb.scorer(), new CountingPolicy(), handler, new RecordingMetrics())
            .evaluate(new MapHttpRequestView(), "h", features(), new RequestContext());

        assertThat(decision.action()).isEqualTo(EnforcementAction.ALLOW);
        handler.apply(decision.action(), new MapHttpRequestView(), mock(EnforcementResponse.class), "h", "/api");
        assertThat(handler.isQuarantined("h", "/api")).isFalse();
        assertThat(handler.getQuarantineCount()).isZero();
    }

    @Test
    void validHighIsolationForestScoreStillFollowsNormalPolicyAndCanQuarantine() throws Exception {
        ScorerWithBuffer sb = ifScorerWithModelReturning(0.95);
        CountingPolicy policy = new CountingPolicy();
        RecordingMetrics metrics = new RecordingMetrics();

        RiskDecision decision = engine(sb.scorer(), policy, notQuarantined(), metrics)
            .evaluate(new MapHttpRequestView(), "h", features(), new RequestContext());

        assertThat(decision.hasStatus(EvaluationStatus.INVALID_SCORE)).isFalse();
        assertThat(decision.anomalyScore()).isEqualTo(0.95);
        assertThat(decision.action()).isEqualTo(EnforcementAction.QUARANTINE);
        assertThat(policy.invocations.get()).isEqualTo(1);
        assertThat(metrics.invalidRejected.get()).isZero();
    }

    @Test
    void compositeWithInvalidIsolationForestProceedsOnStatisticalOnly() throws Exception {
        // Stock wiring: statistical + IF. Invalid IF output is excluded from the blend, so the
        // decision proceeds on the valid statistical score — it does not become INVALID_SCORE
        // and does not become max risk.
        var statistical = new StatisticalScorer(100, 60_000L, 999, 0.3);
        var composite = new CompositeScorer();
        composite.addScorer(statistical, 1.0);
        composite.addScorer(ifScorerWithModelReturning(Double.POSITIVE_INFINITY).scorer(), 0.5);
        CountingPolicy policy = new CountingPolicy();
        RecordingMetrics metrics = new RecordingMetrics();

        RiskDecision decision = engine(composite, policy, notQuarantined(), metrics)
            .evaluate(new MapHttpRequestView(), "h", features(), new RequestContext());

        assertThat(decision.hasStatus(EvaluationStatus.INVALID_SCORE)).isFalse();
        assertThat(decision.hasStatus(EvaluationStatus.MODEL_FALLBACK_USED)).isTrue();
        assertThat(decision.anomalyScore()).isEqualTo(0.3);
        assertThat(decision.action()).isEqualTo(EnforcementAction.MONITOR);
        assertThat(policy.invocations.get()).isEqualTo(1);
        assertThat(metrics.invalidRejected.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN, -0.7})
    void compositeWithOnlyInvalidIsolationForestBecomesInvalidScoreAllow(double modelScore) throws Exception {
        ScorerWithBuffer sb = ifScorerWithModelReturning(modelScore);
        var composite = new CompositeScorer();
        composite.addScorer(sb.scorer(), 1.0);
        CountingPolicy policy = new CountingPolicy();
        RecordingMetrics metrics = new RecordingMetrics();

        RiskDecision decision = engine(composite, policy, notQuarantined(), metrics)
            .evaluate(new MapHttpRequestView(), "h", features(), new RequestContext());

        assertThat(decision.hasStatus(EvaluationStatus.INVALID_SCORE)).isTrue();
        assertThat(decision.hasStatus(EvaluationStatus.MODEL_FALLBACK_USED)).isTrue();
        assertThat(decision.action()).isEqualTo(EnforcementAction.ALLOW);
        assertThat(Double.isNaN(decision.anomalyScore())).isTrue();
        assertThat(Double.isNaN(decision.policyScore())).isTrue();
        assertThat(policy.invocations.get()).isZero();
        assertThat(sb.buffer().size()).isZero();
        assertThat(sb.scorer().getAcceptedTrainingSampleCount()).isZero();
        assertThat(metrics.invalidRejected.get()).isEqualTo(1);
    }

    @Test
    void compositeWithOnlyInvalidIsolationForestCannotCreateQuarantineEntry() throws Exception {
        CompositeEnforcementHandler handler = new CompositeEnforcementHandler(
            403, 60_000L, 10.0, mock(TelemetryEmitter.class));
        ScorerWithBuffer sb = ifScorerWithModelReturning(Double.POSITIVE_INFINITY);
        var composite = new CompositeScorer();
        composite.addScorer(sb.scorer(), 1.0);

        RiskDecision decision = engine(composite, new CountingPolicy(), handler, new RecordingMetrics())
            .evaluate(new MapHttpRequestView(), "h", features(), new RequestContext());

        assertThat(decision.action()).isEqualTo(EnforcementAction.ALLOW);
        handler.apply(decision.action(), new MapHttpRequestView(), mock(EnforcementResponse.class), "h", "/api");
        assertThat(handler.isQuarantined("h", "/api")).isFalse();
        assertThat(handler.getQuarantineCount()).isZero();
    }
}
