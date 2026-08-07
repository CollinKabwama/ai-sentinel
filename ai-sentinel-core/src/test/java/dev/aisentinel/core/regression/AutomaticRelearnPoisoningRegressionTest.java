package dev.aisentinel.core.regression;

import dev.aisentinel.core.baseline.BaselineLifecycle;
import dev.aisentinel.core.baseline.BaselineRelearnMode;
import dev.aisentinel.core.baseline.ConfigurableBaselineUpdatePolicy;
import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.decision.RiskDecision;
import dev.aisentinel.core.decision.SentinelDecisionEngine;
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
import dev.aisentinel.core.scoring.StatisticalScorer;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Permanent security regression for automatic-relearn baseline poisoning.
 * <p>
 * Independent review reproduced this production-path attack against
 * {@code AFTER_CONSECUTIVE_SKIPS}:
 * <pre>
 *   established benign baseline
 *   → sustained attacker traffic (rpw=200) skipped by gated learning (QUARANTINE)
 *   → automatic relearn reset after N consecutive skips
 *   → continued identical attacker traffic trains warmup (warmup always updates)
 *   → by ~request 8 after trigger: score ≈ 0.0025, ALLOW
 * </pre>
 * That mode was removed. This suite asserts the attack cannot succeed on the
 * remaining modes ({@code DISABLED}, {@code EXPLICIT_ONLY}) without a deliberate
 * operator reset — and documents that an explicit reset is an operator-owned
 * trust boundary, not an attacker-triggerable path.
 * <p>
 * Exercises the real production path: {@link SentinelDecisionEngine} →
 * {@link ConfigurableBaselineUpdatePolicy} → {@link BaselineLifecycle} →
 * {@link StatisticalScorer} → {@link ThresholdPolicyEngine}.
 */
class AutomaticRelearnPoisoningRegressionTest {

    private static final String IDENTITY = "id-poison";
    private static final String ENDPOINT = "/api/poison";
    private static final double BENIGN_RPW = 10.0;
    private static final double ATTACK_RPW = 200.0;

    @Test
    void continuedAttackerTraffic_cannotSelfTrainIntoAllow_whenRelearnDisabled() {
        StatisticalScorer scorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        seedLive(scorer, BENIGN_RPW);
        BaselineLifecycle lifecycle = new BaselineLifecycle(
            scorer, BaselineRelearnMode.DISABLED, SentinelMetrics.NOOP);
        SentinelDecisionEngine engine = engine(scorer, lifecycle);

        RiskDecision first = engine.evaluate(shell(), IDENTITY, features(ATTACK_RPW), new RequestContext());
        assertThat(first.action()).isIn(
            EnforcementAction.THROTTLE, EnforcementAction.BLOCK, EnforcementAction.QUARANTINE);
        assertThat(first.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isTrue();
        assertThat(first.hasStatus(EvaluationStatus.BASELINE_RELEARNED)).isFalse();
        double frozen = first.anomalyScore();

        // Previously unsafe auto-relearn threshold was small (review PoC used 5).
        // Drive far more elevated observations; none may open warmup or reach ALLOW.
        for (int i = 0; i < 40; i++) {
            RiskDecision d = engine.evaluate(shell(), IDENTITY, features(ATTACK_RPW), new RequestContext());
            assertThat(d.hasStatus(EvaluationStatus.BASELINE_RELEARNED)).isFalse();
            assertThat(d.hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isFalse();
            assertThat(d.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isTrue();
            assertThat(d.anomalyScore()).isEqualTo(frozen);
            assertThat(d.action()).isNotEqualTo(EnforcementAction.ALLOW);
            assertThat(d.action()).isIn(
                EnforcementAction.THROTTLE, EnforcementAction.BLOCK, EnforcementAction.QUARANTINE);
        }
    }

    @Test
    void continuedAttackerTraffic_cannotSelfTrainIntoAllow_whenExplicitOnlyWithoutOperatorReset() {
        StatisticalScorer scorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        seedLive(scorer, BENIGN_RPW);
        BaselineLifecycle lifecycle = new BaselineLifecycle(
            scorer, BaselineRelearnMode.EXPLICIT_ONLY, SentinelMetrics.NOOP);
        SentinelDecisionEngine engine = engine(scorer, lifecycle);

        RiskDecision first = engine.evaluate(shell(), IDENTITY, features(ATTACK_RPW), new RequestContext());
        double frozen = first.anomalyScore();
        assertThat(first.action()).isNotEqualTo(EnforcementAction.ALLOW);

        for (int i = 0; i < 40; i++) {
            RiskDecision d = engine.evaluate(shell(), IDENTITY, features(ATTACK_RPW), new RequestContext());
            assertThat(d.hasStatus(EvaluationStatus.BASELINE_RELEARNED)).isFalse();
            assertThat(d.hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isFalse();
            assertThat(d.anomalyScore()).isEqualTo(frozen);
            assertThat(d.action()).isNotEqualTo(EnforcementAction.ALLOW);
        }
        assertThat(lifecycle.onUpdateSkipped(features(ATTACK_RPW))).isFalse();
    }

    @Test
    void sameTrafficCannotBothTriggerAndTrain_autoSkipHookNeverResets() {
        StatisticalScorer scorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        seedLive(scorer, BENIGN_RPW);
        BaselineLifecycle lifecycle = new BaselineLifecycle(
            scorer, BaselineRelearnMode.EXPLICIT_ONLY, SentinelMetrics.NOOP);

        for (int i = 0; i < 100; i++) {
            assertThat(lifecycle.onUpdateSkipped(features(ATTACK_RPW))).isFalse();
        }
        assertThat(scorer.isWarmup(features(BENIGN_RPW))).isFalse();
    }

    @Test
    void explicitReset_isDeliberateOperatorBoundary_subsequentTrafficTrainsWarmup() {
        // Documents operational responsibility: after an intentional reset, subsequent traffic
        // (including attacker traffic if the operator resets during an attack) will train warmup.
        // That is why automatic skip-triggered reset was removed.
        StatisticalScorer scorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        seedLive(scorer, BENIGN_RPW);
        BaselineLifecycle lifecycle = new BaselineLifecycle(
            scorer, BaselineRelearnMode.EXPLICIT_ONLY, SentinelMetrics.NOOP);
        SentinelDecisionEngine engine = engine(scorer, lifecycle);

        assertThat(lifecycle.reset(IDENTITY, ENDPOINT)).isTrue();

        RiskDecision w1 = engine.evaluate(shell(), IDENTITY, features(ATTACK_RPW), new RequestContext());
        assertThat(w1.hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isTrue();
        assertThat(w1.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isFalse();

        RiskDecision w2 = engine.evaluate(shell(), IDENTITY, features(ATTACK_RPW), new RequestContext());
        assertThat(w2.hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isTrue();

        RiskDecision live = engine.evaluate(shell(), IDENTITY, features(ATTACK_RPW), new RequestContext());
        assertThat(live.hasStatus(EvaluationStatus.STATISTICAL_LIVE)).isTrue();
        assertThat(live.action()).isEqualTo(EnforcementAction.ALLOW);
        assertThat(live.anomalyScore()).isLessThan(0.2);
    }

    @Test
    void automaticRelearnMode_isNoLongerOffered() {
        assertThat(BaselineRelearnMode.values())
            .containsExactly(BaselineRelearnMode.DISABLED, BaselineRelearnMode.EXPLICIT_ONLY);
    }

    private static void seedLive(StatisticalScorer scorer, double rpw) {
        RequestFeatures calm = features(rpw);
        for (int i = 0; i < 30; i++) {
            scorer.update(calm);
        }
    }

    private static SentinelDecisionEngine engine(StatisticalScorer scorer, BaselineLifecycle lifecycle) {
        return new SentinelDecisionEngine(
            scorer,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            NeverQuarantined.INSTANCE,
            NoopTel.INSTANCE,
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE,
            EnforcementAction.MONITOR,
            ConfigurableBaselineUpdatePolicy.allowOrMonitor(),
            lifecycle
        );
    }

    private static HttpRequestView shell() {
        return new MapHttpRequestView().requestUri(ENDPOINT).method("GET");
    }

    private static RequestFeatures features(double rpw) {
        return RequestFeatures.builder()
            .identityHash(IDENTITY)
            .endpoint(ENDPOINT)
            .timestampMillis(System.currentTimeMillis())
            .requestsPerWindow(rpw)
            .endpointEntropy(0)
            .tokenAgeSeconds(60)
            .parameterCount(2)
            .payloadSizeBytes(100)
            .headerFingerprintHash(10)
            .ipBucket(1)
            .build();
    }

    private enum NeverQuarantined implements EnforcementHandler {
        INSTANCE;

        @Override
        public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                             String identityHash, String endpoint) {
            return true;
        }

        @Override
        public boolean isQuarantined(String identityHash, String endpoint) {
            return false;
        }
    }

    private enum NoopTel implements TelemetryEmitter {
        INSTANCE;

        @Override
        public void emit(dev.aisentinel.core.telemetry.TelemetryEvent event) {
        }
    }
}
