package dev.aisentinel.core.regression;

import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.decision.OperatorEvaluationPhase;
import dev.aisentinel.core.decision.RiskDecision;
import dev.aisentinel.core.decision.SentinelDecisionEngine;
import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.fusion.RequestRiskFusion;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.http.MapHttpRequestView;
import dev.aisentinel.core.identity.IdentityContextKeys;
import dev.aisentinel.core.identity.model.AuthenticationContext;
import dev.aisentinel.core.identity.model.IdentityContext;
import dev.aisentinel.core.identity.model.IdentityRiskSignals;
import dev.aisentinel.core.identity.model.SessionContext;
import dev.aisentinel.core.identity.model.TrustScore;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
import dev.aisentinel.core.identity.spi.TrustEvaluator;
import dev.aisentinel.core.metrics.FailOpenReason;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.NoopTrustPolicyAdjuster;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.policy.TrustPolicyAdjuster;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.scoring.BoundedTrainingBuffer;
import dev.aisentinel.core.scoring.CompositeScorer;
import dev.aisentinel.core.scoring.IsolationForestConfig;
import dev.aisentinel.core.scoring.IsolationForestScorer;
import dev.aisentinel.core.scoring.StatisticalScorer;
import dev.aisentinel.core.telemetry.TelemetryEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Observability lifecycle: EvaluationStatus transitions, IF fallback labeling,
 * structured fail-open reasons, and DEGRADED vs FAIL_OPEN consistency.
 */
class ObservabilityLifecycleRegressionTest {

    private static final String IDENTITY = "id-obs";
    private static final String ENDPOINT = "/api/obs";

    @Test
    void warmupThenLive_operatorPhasesAreDeterministic() {
        StatisticalScorer scorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        List<TelemetryEvent> events = new ArrayList<>();
        SentinelDecisionEngine engine = engine(scorer, NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE, NoopRequestRiskFusion.INSTANCE, events, countingMetrics());

        RiskDecision w0 = engine.evaluate(shell(), IDENTITY, features(10), new RequestContext());
        RiskDecision w1 = engine.evaluate(shell(), IDENTITY, features(10), new RequestContext());
        RiskDecision live = engine.evaluate(shell(), IDENTITY, features(10), new RequestContext());

        assertThat(w0.hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isTrue();
        assertThat(OperatorEvaluationPhase.fromStatuses(w0.evaluationStatuses()))
            .contains(OperatorEvaluationPhase.WARMUP)
            .doesNotContain(OperatorEvaluationPhase.LIVE);
        assertThat(w1.hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isTrue();

        assertThat(live.hasStatus(EvaluationStatus.STATISTICAL_LIVE)).isTrue();
        assertThat(live.hasStatus(EvaluationStatus.COMPLETE)).isTrue();
        assertThat(OperatorEvaluationPhase.fromStatuses(live.evaluationStatuses()))
            .contains(OperatorEvaluationPhase.LIVE)
            .doesNotContain(OperatorEvaluationPhase.WARMUP);

        assertThat(events.stream().filter(e -> "ThreatScored".equals(e.type()))).hasSize(3);
        assertThat(events.get(0).payload()).containsKey("evaluationStatuses");
        assertThat(events.get(0).payload()).containsKey("operatorPhases");
    }

    @Test
    void isolationForestUnavailable_marksModelFallbackNotComplete() {
        IsolationForestConfig cfg = new IsolationForestConfig(0.5, 50, 10, 5, 42L, 1.0);
        IsolationForestScorer ifScorer = new IsolationForestScorer(new BoundedTrainingBuffer(100), cfg, countingMetrics());
        CompositeScorer composite = new CompositeScorer();
        composite.addScorer(new StatisticalScorer(1000, 60_000L, 2, 0.4), 0.5);
        composite.addScorer(ifScorer, 0.5);

        List<TelemetryEvent> events = new ArrayList<>();
        SentinelDecisionEngine engine = engine(composite, NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE, NoopRequestRiskFusion.INSTANCE, events, countingMetrics());

        RiskDecision d = engine.evaluate(shell(), IDENTITY, features(3), new RequestContext());
        assertThat(d.hasStatus(EvaluationStatus.MODEL_UNAVAILABLE)).isTrue();
        assertThat(d.hasStatus(EvaluationStatus.MODEL_FALLBACK_USED)).isTrue();
        assertThat(d.hasStatus(EvaluationStatus.COMPLETE)).isFalse();
        assertThat(OperatorEvaluationPhase.fromStatuses(d.evaluationStatuses()))
            .contains(OperatorEvaluationPhase.MODEL_FALLBACK);
        assertThat(ifScorer.lastScoreMode()).isEqualTo(IsolationForestScorer.LastScoreMode.FALLBACK_NO_MODEL);

        TelemetryEvent scored = events.stream().filter(e -> "ThreatScored".equals(e.type())).findFirst().orElseThrow();
        assertThat(scored.payload().get("isolationForestScoreMode")).isEqualTo("FALLBACK_NO_MODEL");
    }

    @Test
    void scorerFailure_failOpenReasonAndNoDecision() {
        AnomalyScorer failing = new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void update(RequestFeatures features) {
            }
        };
        CountingMetrics metrics = countingMetrics();
        List<TelemetryEvent> events = new ArrayList<>();
        SentinelDecisionEngine engine = engine(failing, NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE, NoopRequestRiskFusion.INSTANCE, events, metrics);

        assertThat(engine.evaluate(shell(), IDENTITY, features(1), new RequestContext())).isNull();
        assertThat(metrics.failOpen.get(FailOpenReason.SCORER_FAILURE).get()).isEqualTo(1);
        assertThat(events.stream().anyMatch(e -> "FailOpen".equals(e.type())
            && FailOpenReason.SCORER_FAILURE.name().equals(e.payload().get("reason")))).isTrue();
        assertThat(events.stream().noneMatch(e -> "ThreatScored".equals(e.type()))).isTrue();
    }

    @Test
    void trustFailure_marksDegradedAndContinuesDecision() {
        TrustEvaluator failingTrust = (identity, request, features, ctx) -> {
            throw new RuntimeException("trust down");
        };
        CountingMetrics metrics = countingMetrics();
        List<TelemetryEvent> events = new ArrayList<>();
        SentinelDecisionEngine engine = engine(new StatisticalScorer(), failingTrust,
            NoopTrustPolicyAdjuster.INSTANCE, NoopRequestRiskFusion.INSTANCE, events, metrics);

        RequestContext ctx = new RequestContext();
        ctx.put(IdentityContextKeys.IDENTITY_CONTEXT, new IdentityContext(
            AuthenticationContext.unauthenticated(),
            SessionContext.none(),
            TrustScore.fullyTrusted(),
            IdentityRiskSignals.empty()));

        RiskDecision d = engine.evaluate(shell(), IDENTITY, features(1), ctx);
        assertThat(d).isNotNull();
        assertThat(d.hasStatus(EvaluationStatus.DEGRADED)).isTrue();
        assertThat(d.hasStatus(EvaluationStatus.COMPLETE)).isFalse();
        assertThat(OperatorEvaluationPhase.fromStatuses(d.evaluationStatuses()))
            .contains(OperatorEvaluationPhase.DEGRADED);
        assertThat(metrics.failOpen.get(FailOpenReason.TRUST_EVALUATION_FAILURE).get()).isEqualTo(1);
    }

    @Test
    void trustPolicyFailure_marksDegraded() {
        TrustPolicyAdjuster failing = (action, score, features, endpoint, request, ctx) -> {
            throw new IllegalStateException("policy adjuster down");
        };
        CountingMetrics metrics = countingMetrics();
        SentinelDecisionEngine engine = engine(new StatisticalScorer(), NoopTrustEvaluator.INSTANCE,
            failing, NoopRequestRiskFusion.INSTANCE, new ArrayList<>(), metrics);

        RiskDecision d = engine.evaluate(shell(), IDENTITY, features(1), new RequestContext());
        assertThat(d.hasStatus(EvaluationStatus.DEGRADED)).isTrue();
        assertThat(metrics.failOpen.get(FailOpenReason.TRUST_POLICY_FAILURE).get()).isEqualTo(1);
    }

    @Test
    void repeatedTransitions_warmupLiveWarmupAreStable() {
        StatisticalScorer scorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        SentinelDecisionEngine engine = engine(scorer, NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE, NoopRequestRiskFusion.INSTANCE, new ArrayList<>(), countingMetrics());

        for (int i = 0; i < 2; i++) {
            assertThat(engine.evaluate(shell(), IDENTITY, features(5), new RequestContext())
                .hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isTrue();
        }
        assertThat(engine.evaluate(shell(), IDENTITY, features(5), new RequestContext())
            .hasStatus(EvaluationStatus.STATISTICAL_LIVE)).isTrue();

        StatisticalScorer fresh = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        SentinelDecisionEngine again = engine(fresh, NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE, NoopRequestRiskFusion.INSTANCE, new ArrayList<>(), countingMetrics());
        assertThat(again.evaluate(shell(), IDENTITY, features(5), new RequestContext())
            .hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isTrue();
    }

    private static SentinelDecisionEngine engine(AnomalyScorer scorer,
                                                 TrustEvaluator trust,
                                                 TrustPolicyAdjuster trustPolicy,
                                                 RequestRiskFusion fusion,
                                                 List<TelemetryEvent> events,
                                                 SentinelMetrics metrics) {
        return new SentinelDecisionEngine(
            scorer,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            NEVER_QUARANTINED,
            events::add,
            StartupGrace.NEVER,
            metrics,
            trust,
            trustPolicy,
            fusion,
            EnforcementAction.MONITOR
        );
    }

    private static CountingMetrics countingMetrics() {
        return new CountingMetrics();
    }

    private static MapHttpRequestView shell() {
        return new MapHttpRequestView().requestUri(ENDPOINT).method("GET").remoteAddr("203.0.113.80");
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

    private static final class CountingMetrics implements SentinelMetrics {
        final EnumMap<FailOpenReason, AtomicInteger> failOpen = new EnumMap<>(FailOpenReason.class);

        CountingMetrics() {
            for (FailOpenReason r : FailOpenReason.values()) {
                failOpen.put(r, new AtomicInteger());
            }
        }

        @Override
        public void recordFailOpen(FailOpenReason reason) {
            if (reason != null) {
                failOpen.get(reason).incrementAndGet();
            }
            recordFailOpen();
        }
    }
}
