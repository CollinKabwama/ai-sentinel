package dev.aisentinel.core.decision;

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
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.scoring.CompositeScorer;
import dev.aisentinel.core.scoring.IsolationForestConfig;
import dev.aisentinel.core.scoring.IsolationForestScorer;
import dev.aisentinel.core.scoring.BoundedTrainingBuffer;
import dev.aisentinel.core.scoring.StatisticalScorer;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Statistical warmup must not enter enforcement bands by score collision,
 * and evaluation lifecycle statuses must be observable on {@link RiskDecision}.
 */
class WarmupEvaluationStatusTest {

    private static final String IDENTITY = "id-warmup";
    private static final String ENDPOINT = "/api/warmup";

    @Test
    void newBaseline_isWarmupMonitor_notThrottle() {
        StatisticalScorer scorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        SentinelDecisionEngine engine = engine(scorer, EnforcementAction.MONITOR);

        RiskDecision d = engine.evaluate(shell(), IDENTITY, features(1), new RequestContext());
        assertThat(d).isNotNull();
        assertThat(d.anomalyScore()).isEqualTo(0.4);
        assertThat(d.action()).isEqualTo(EnforcementAction.MONITOR);
        assertThat(d.hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isTrue();
        assertThat(d.hasStatus(EvaluationStatus.COMPLETE)).isFalse();
        assertThat(d.hasStatus(EvaluationStatus.STATISTICAL_LIVE)).isFalse();
        assertThat(d.evaluationStatuses()).isUnmodifiable();
    }

    @Test
    void warmup_neverBlockOrQuarantine_underDefaults() {
        StatisticalScorer scorer = new StatisticalScorer();
        SentinelDecisionEngine engine = engine(scorer, EnforcementAction.MONITOR);
        for (int i = 0; i < 2; i++) {
            RiskDecision d = engine.evaluate(shell(), IDENTITY, features(1), new RequestContext());
            assertThat(d.action()).isNotIn(EnforcementAction.THROTTLE, EnforcementAction.BLOCK, EnforcementAction.QUARANTINE);
            assertThat(d.hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isTrue();
        }
    }

    @Test
    void transitionsToLive_afterWarmupSamples() {
        StatisticalScorer scorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        SentinelDecisionEngine engine = engine(scorer, EnforcementAction.MONITOR);

        RiskDecision w0 = engine.evaluate(shell(), IDENTITY, features(10), new RequestContext());
        RiskDecision w1 = engine.evaluate(shell(), IDENTITY, features(10), new RequestContext());
        assertThat(w0.hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isTrue();
        assertThat(w1.hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isTrue();

        RiskDecision live = engine.evaluate(shell(), IDENTITY, features(10), new RequestContext());
        assertThat(live.hasStatus(EvaluationStatus.STATISTICAL_LIVE)).isTrue();
        assertThat(live.hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isFalse();
        assertThat(live.hasStatus(EvaluationStatus.COMPLETE)).isTrue();
    }

    @Test
    void liveScoring_usesPolicyThresholds() {
        StatisticalScorer scorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        SentinelDecisionEngine engine = engine(scorer, EnforcementAction.MONITOR);
        for (int i = 0; i < 20; i++) {
            engine.evaluate(shell(), IDENTITY, features(10), new RequestContext());
        }
        RiskDecision calm = engine.evaluate(shell(), IDENTITY, features(10), new RequestContext());
        assertThat(calm.hasStatus(EvaluationStatus.STATISTICAL_LIVE)).isTrue();
        assertThat(calm.action()).isIn(EnforcementAction.ALLOW, EnforcementAction.MONITOR);

        RiskDecision burst = engine.evaluate(shell(), IDENTITY, features(10_000), new RequestContext());
        assertThat(burst.hasStatus(EvaluationStatus.STATISTICAL_LIVE)).isTrue();
        assertThat(burst.anomalyScore()).isGreaterThan(calm.anomalyScore());
    }

    @Test
    void customWarmupAction_allow() {
        StatisticalScorer scorer = new StatisticalScorer();
        SentinelDecisionEngine engine = engine(scorer, EnforcementAction.ALLOW);
        RiskDecision d = engine.evaluate(shell(), IDENTITY, features(1), new RequestContext());
        assertThat(d.action()).isEqualTo(EnforcementAction.ALLOW);
        assertThat(d.hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isTrue();
    }

    @Test
    void nonAllowOrMonitorWarmupAction_normalizedToMonitor() {
        assertThat(SentinelDecisionEngine.normalizeWarmupAction(EnforcementAction.THROTTLE))
            .isEqualTo(EnforcementAction.MONITOR);
        assertThat(SentinelDecisionEngine.normalizeWarmupAction(EnforcementAction.BLOCK))
            .isEqualTo(EnforcementAction.MONITOR);
        assertThat(SentinelDecisionEngine.normalizeWarmupAction(EnforcementAction.QUARANTINE))
            .isEqualTo(EnforcementAction.MONITOR);
    }

    @Test
    void freshScorer_reentersWarmup() {
        StatisticalScorer first = new StatisticalScorer();
        SentinelDecisionEngine e1 = engine(first, EnforcementAction.MONITOR);
        for (int i = 0; i < 10; i++) {
            e1.evaluate(shell(), IDENTITY, features(5), new RequestContext());
        }
        StatisticalScorer fresh = new StatisticalScorer();
        SentinelDecisionEngine e2 = engine(fresh, EnforcementAction.MONITOR);
        RiskDecision d = e2.evaluate(shell(), IDENTITY, features(5), new RequestContext());
        assertThat(d.hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isTrue();
        assertThat(d.action()).isEqualTo(EnforcementAction.MONITOR);
    }

    @Test
    void riskDecisionOf_emptyStatuses() {
        RiskDecision d = RiskDecision.of(EnforcementAction.ALLOW, 0.1, 0.1, features(1), new RequestContext(), false);
        assertThat(d.evaluationStatuses()).isEmpty();
    }

    @Test
    void fixedScorer_marksComplete() {
        AnomalyScorer fixed = new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                return 0.1;
            }

            @Override
            public void update(RequestFeatures features) {
            }
        };
        SentinelDecisionEngine engine = engine(fixed, EnforcementAction.MONITOR);
        RiskDecision d = engine.evaluate(shell(), IDENTITY, features(1), new RequestContext());
        assertThat(d.action()).isEqualTo(EnforcementAction.ALLOW);
        assertThat(d.hasStatus(EvaluationStatus.COMPLETE)).isTrue();
        assertThat(d.hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isFalse();
    }

    @Test
    void isolationForestWithoutModel_marksUnavailableAndFallback() {
        IsolationForestConfig cfg = new IsolationForestConfig(0.5, 50, 10, 5, 42L, 1.0);
        CompositeScorer live = new CompositeScorer();
        live.addScorer(new StatisticalScorer(1000, 60_000L, 2, 0.4), 0.5);
        live.addScorer(new IsolationForestScorer(new BoundedTrainingBuffer(100), cfg), 0.5);
        SentinelDecisionEngine engine = engine(live, EnforcementAction.MONITOR);
        RiskDecision d = engine.evaluate(shell(), "id-if", features(3), new RequestContext());
        assertThat(d.hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isTrue();
        assertThat(d.hasStatus(EvaluationStatus.MODEL_UNAVAILABLE)).isTrue();
        assertThat(d.hasStatus(EvaluationStatus.MODEL_FALLBACK_USED)).isTrue();
        assertThat(d.hasStatus(EvaluationStatus.COMPLETE)).isFalse();
        assertThat(d.action()).isEqualTo(EnforcementAction.MONITOR);
    }

    @Test
    void statisticalUpdateStillAdvancesBaseline() {
        StatisticalScorer scorer = new StatisticalScorer();
        SentinelDecisionEngine engine = engine(scorer, EnforcementAction.MONITOR);
        assertThat(scorer.isWarmup(features(1))).isTrue();
        engine.evaluate(shell(), IDENTITY, features(1), new RequestContext());
        engine.evaluate(shell(), IDENTITY, features(1), new RequestContext());
        assertThat(scorer.isWarmup(features(1))).isFalse();
    }

    private static SentinelDecisionEngine engine(AnomalyScorer scorer, EnforcementAction warmupAction) {
        return new SentinelDecisionEngine(
            scorer,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            NEVER_QUARANTINED,
            event -> {
            },
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE,
            warmupAction
        );
    }

    private static MapHttpRequestView shell() {
        return new MapHttpRequestView().requestUri(ENDPOINT).method("GET").remoteAddr("203.0.113.70");
    }

    private static RequestFeatures features(double rpw) {
        return RequestFeatures.builder()
            .identityHash(IDENTITY)
            .endpoint(ENDPOINT)
            .timestampMillis(1_700_000_000_000L)
            .requestsPerWindow(rpw)
            .endpointEntropy(0.1)
            .tokenAgeSeconds(-1)
            .parameterCount(0)
            .payloadSizeBytes(0)
            .headerFingerprintHash(11L)
            .ipBucket(3)
            .build();
    }

    private static final EnforcementHandler NEVER_QUARANTINED = new EnforcementHandler() {
        @Override
        public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                             String identityHash, String endpoint) {
            throw new AssertionError("must not apply");
        }

        @Override
        public boolean isQuarantined(String identityHash, String endpoint) {
            return false;
        }
    };
}
