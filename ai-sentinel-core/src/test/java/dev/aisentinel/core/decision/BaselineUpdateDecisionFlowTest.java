package dev.aisentinel.core.decision;

import dev.aisentinel.core.baseline.BaselineUpdateMode;
import dev.aisentinel.core.baseline.ConfigurableBaselineUpdatePolicy;
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
import dev.aisentinel.core.scoring.StatisticalScorer;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decision-flow coverage for gated baseline learning.
 */
class BaselineUpdateDecisionFlowTest {

    private static final String IDENTITY = "id-gate";
    private static final String ENDPOINT = "/api/gate";

    @Test
    void defaultPolicy_allowAndMonitorUpdate_throttleDoesNot() {
        StatisticalScorer scorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        seedLive(scorer, 10.0);

        SentinelDecisionEngine engine = engine(scorer, ConfigurableBaselineUpdatePolicy.allowOrMonitor(),
            StartupGrace.NEVER, NeverQuarantined.INSTANCE);

        RiskDecision allowish = engine.evaluate(shell(), IDENTITY, features(10.0), new RequestContext());
        assertThat(allowish.hasStatus(EvaluationStatus.STATISTICAL_LIVE)).isTrue();
        assertThat(allowish.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isFalse();
        assertThat(allowish.action()).isIn(EnforcementAction.ALLOW, EnforcementAction.MONITOR);

        RiskDecision elevated = engine.evaluate(shell(), IDENTITY, features(200.0), new RequestContext());
        assertThat(elevated.action()).isIn(EnforcementAction.THROTTLE, EnforcementAction.BLOCK, EnforcementAction.QUARANTINE);
        assertThat(elevated.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isTrue();

        double afterSkip = engine.evaluate(shell(), IDENTITY, features(200.0), new RequestContext()).anomalyScore();
        double again = engine.evaluate(shell(), IDENTITY, features(200.0), new RequestContext()).anomalyScore();
        assertThat(again).isEqualTo(afterSkip);
    }

    @Test
    void always_updatesOnThrottle() {
        StatisticalScorer scorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        seedLive(scorer, 10.0);
        SentinelDecisionEngine engine = engine(scorer, ConfigurableBaselineUpdatePolicy.always(),
            StartupGrace.NEVER, NeverQuarantined.INSTANCE);

        RiskDecision first = engine.evaluate(shell(), IDENTITY, features(200.0), new RequestContext());
        assertThat(first.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isFalse();
        double firstScore = first.anomalyScore();
        RiskDecision later = null;
        for (int i = 0; i < 20; i++) {
            later = engine.evaluate(shell(), IDENTITY, features(200.0), new RequestContext());
        }
        assertThat(later.anomalyScore()).isLessThan(firstScore);
    }

    @Test
    void startupGrace_doesNotAllowElevatedRiskToTrain() {
        StatisticalScorer scorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        seedLive(scorer, 10.0);
        StartupGrace grace = () -> true;
        SentinelDecisionEngine engine = engine(scorer, ConfigurableBaselineUpdatePolicy.allowOrMonitor(),
            grace, NeverQuarantined.INSTANCE);

        RiskDecision d = engine.evaluate(shell(), IDENTITY, features(200.0), new RequestContext());
        assertThat(d.startupGraceActive()).isTrue();
        assertThat(d.action()).isEqualTo(EnforcementAction.MONITOR);
        assertThat(d.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isTrue();
    }

    @Test
    void quarantineOverride_doesNotDistortRiskBasedLearning() {
        StatisticalScorer scorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        seedLive(scorer, 10.0);
        SentinelDecisionEngine engine = engine(scorer, ConfigurableBaselineUpdatePolicy.allowOrMonitor(),
            StartupGrace.NEVER, AlwaysQuarantined.INSTANCE);

        RiskDecision calm = engine.evaluate(shell(), IDENTITY, features(10.0), new RequestContext());
        assertThat(calm.action()).isEqualTo(EnforcementAction.QUARANTINE);
        assertThat(calm.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isFalse();
    }

    @Test
    void warmup_continuesLearningAndReachesLive() {
        StatisticalScorer scorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        SentinelDecisionEngine engine = engine(scorer, ConfigurableBaselineUpdatePolicy.allowOrMonitor(),
            StartupGrace.NEVER, NeverQuarantined.INSTANCE);

        RiskDecision w1 = engine.evaluate(shell(), IDENTITY, features(5.0), new RequestContext());
        RiskDecision w2 = engine.evaluate(shell(), IDENTITY, features(5.0), new RequestContext());
        assertThat(w1.hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isTrue();
        assertThat(w2.hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isTrue();
        assertThat(w1.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isFalse();

        RiskDecision live = engine.evaluate(shell(), IDENTITY, features(5.0), new RequestContext());
        assertThat(live.hasStatus(EvaluationStatus.STATISTICAL_LIVE)).isTrue();
    }

    @Test
    void allowOnly_warmupBypassLearnsEvenWhenRiskWouldNotAllow() {
        StatisticalScorer scorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        CountingMetrics metrics = new CountingMetrics();
        SentinelDecisionEngine engine = engine(scorer,
            new ConfigurableBaselineUpdatePolicy(BaselineUpdateMode.ALLOW_ONLY, 0.4),
            StartupGrace.NEVER, NeverQuarantined.INSTANCE, metrics);

        assertThat(scorer.isWarmup(features(5.0))).isTrue();
        assertThat(scorer.metricsStateEntryCount()).isZero();

        RiskDecision w1 = engine.evaluate(shell(), IDENTITY, features(5.0), new RequestContext());
        // Warmup numeric score 0.4 maps to THROTTLE risk before warmup enforcement override.
        assertThat(w1.hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isTrue();
        assertThat(w1.action()).isEqualTo(EnforcementAction.MONITOR);
        assertThat(w1.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isFalse();
        assertThat(scorer.metricsStateEntryCount()).isEqualTo(1);
        assertThat(metrics.acceptedWarmup.get()).isEqualTo(1);
        assertThat(metrics.skipped.get()).isZero();

        RiskDecision w2 = engine.evaluate(shell(), IDENTITY, features(5.0), new RequestContext());
        assertThat(w2.hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isTrue();
        assertThat(w2.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isFalse();
        assertThat(metrics.acceptedWarmup.get()).isEqualTo(2);

        RiskDecision live = engine.evaluate(shell(), IDENTITY, features(5.0), new RequestContext());
        assertThat(live.hasStatus(EvaluationStatus.STATISTICAL_LIVE)).isTrue();
        assertThat(scorer.isWarmup(features(5.0))).isFalse();

        // Live ALLOW_ONLY: MONITOR risk must not train.
        RiskDecision monitorish = engine.evaluate(shell(), IDENTITY, features(5.0), new RequestContext());
        if (monitorish.action() == EnforcementAction.MONITOR) {
            assertThat(monitorish.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isTrue();
        } else if (monitorish.action() == EnforcementAction.ALLOW) {
            assertThat(monitorish.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isFalse();
        }

        RiskDecision elevated = engine.evaluate(shell(), IDENTITY, features(200.0), new RequestContext());
        assertThat(elevated.action()).isNotEqualTo(EnforcementAction.ALLOW);
        assertThat(elevated.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isTrue();
    }

    @Test
    void scoreBelowThreshold_warmupBypassWinsEvenWhenWarmupScoreAtOrAboveGate() {
        // Warmup score 0.4 is NOT strictly below threshold 0.3 — without warmup bypass, learning would deadlock.
        StatisticalScorer scorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        CountingMetrics metrics = new CountingMetrics();
        ConfigurableBaselineUpdatePolicy policy =
            new ConfigurableBaselineUpdatePolicy(BaselineUpdateMode.SCORE_BELOW_THRESHOLD, 0.3);
        SentinelDecisionEngine engine = engine(scorer, policy, StartupGrace.NEVER, NeverQuarantined.INSTANCE, metrics);

        RiskDecision w1 = engine.evaluate(shell(), IDENTITY, features(5.0), new RequestContext());
        assertThat(w1.hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isTrue();
        assertThat(w1.anomalyScore()).isEqualTo(0.4);
        assertThat(w1.anomalyScore()).isGreaterThanOrEqualTo(policy.scoreThreshold());
        assertThat(w1.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isFalse();
        assertThat(metrics.acceptedWarmup.get()).isEqualTo(1);

        RiskDecision w2 = engine.evaluate(shell(), IDENTITY, features(5.0), new RequestContext());
        assertThat(w2.hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isTrue();
        assertThat(w2.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isFalse();

        RiskDecision live = engine.evaluate(shell(), IDENTITY, features(5.0), new RequestContext());
        assertThat(live.hasStatus(EvaluationStatus.STATISTICAL_LIVE)).isTrue();
        assertThat(scorer.isWarmup(features(5.0))).isFalse();

        RiskDecision elevated = engine.evaluate(shell(), IDENTITY, features(200.0), new RequestContext());
        assertThat(elevated.policyScore()).isGreaterThanOrEqualTo(0.3);
        assertThat(elevated.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isTrue();

        // Boundary: policyScore == threshold must skip (strict <).
        FixedAnomalyScorer atGate = new FixedAnomalyScorer(0.3);
        SentinelDecisionEngine atGateEngine = engine(atGate, policy, StartupGrace.NEVER, NeverQuarantined.INSTANCE);
        RiskDecision boundary = atGateEngine.evaluate(shell(), IDENTITY + "-b", features(1.0), new RequestContext());
        assertThat(boundary.policyScore()).isEqualTo(0.3);
        assertThat(boundary.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isTrue();
        assertThat(atGate.updates).isZero();

        FixedAnomalyScorer below = new FixedAnomalyScorer(0.29);
        SentinelDecisionEngine belowEngine = engine(below, policy, StartupGrace.NEVER, NeverQuarantined.INSTANCE);
        RiskDecision accepted = belowEngine.evaluate(shell(), IDENTITY + "-c", features(1.0), new RequestContext());
        assertThat(accepted.policyScore()).isLessThan(0.3);
        assertThat(accepted.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isFalse();
        assertThat(below.updates).isEqualTo(1);
    }

    @Test
    void customNonUpdatingScorer_remainsCompatible() {
        AtomicInteger scores = new AtomicInteger();
        AtomicInteger updates = new AtomicInteger();
        AnomalyScorer custom = new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                scores.incrementAndGet();
                return 0.1;
            }

            @Override
            public void update(RequestFeatures features) {
                updates.incrementAndGet();
            }
        };
        SentinelDecisionEngine engine = engine(custom, ConfigurableBaselineUpdatePolicy.allowOrMonitor(),
            StartupGrace.NEVER, NeverQuarantined.INSTANCE);
        RiskDecision d = engine.evaluate(shell(), IDENTITY, features(1.0), new RequestContext());
        assertThat(d.action()).isIn(EnforcementAction.ALLOW, EnforcementAction.MONITOR);
        assertThat(scores.get()).isEqualTo(1);
        assertThat(updates.get()).isEqualTo(1);
    }

    @Test
    void scoreBelowThreshold_modeHonorsThreshold() {
        StatisticalScorer scorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        seedLive(scorer, 10.0);
        SentinelDecisionEngine engine = engine(scorer,
            new ConfigurableBaselineUpdatePolicy(BaselineUpdateMode.SCORE_BELOW_THRESHOLD, 0.4),
            StartupGrace.NEVER, NeverQuarantined.INSTANCE);

        RiskDecision high = engine.evaluate(shell(), IDENTITY, features(200.0), new RequestContext());
        assertThat(high.policyScore()).isGreaterThanOrEqualTo(0.4);
        assertThat(high.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isTrue();
    }

    @Test
    void sustainedThrottle_freezesBaseline_whenRelearnDisabled() {
        StatisticalScorer scorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        seedLive(scorer, 10.0);
        SentinelDecisionEngine engine = engine(scorer, ConfigurableBaselineUpdatePolicy.allowOrMonitor(),
            StartupGrace.NEVER, NeverQuarantined.INSTANCE);

        RiskDecision first = engine.evaluate(shell(), IDENTITY, features(150.0), new RequestContext());
        assertThat(first.action()).isIn(EnforcementAction.THROTTLE, EnforcementAction.BLOCK, EnforcementAction.QUARANTINE);
        double firstScore = first.anomalyScore();
        for (int i = 0; i < 40; i++) {
            RiskDecision d = engine.evaluate(shell(), IDENTITY, features(150.0), new RequestContext());
            assertThat(d.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isTrue();
            assertThat(d.anomalyScore()).isEqualTo(firstScore);
        }
    }

    @Test
    void throwingUpdatePolicy_failsOpen_skipsUpdateAndStillReturnsDecision() {
        StatisticalScorer scorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        seedLive(scorer, 10.0);
        SentinelDecisionEngine engine = new SentinelDecisionEngine(
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
            ctx -> { throw new IllegalStateException("boom"); });

        RiskDecision d = engine.evaluate(shell(), IDENTITY, features(10.0), new RequestContext());

        assertThat(d).isNotNull();
        assertThat(d.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isTrue();
    }

    private static void seedLive(StatisticalScorer scorer, double rpw) {
        RequestFeatures calm = features(rpw);
        for (int i = 0; i < 30; i++) {
            scorer.update(calm);
        }
    }

    private static SentinelDecisionEngine engine(AnomalyScorer scorer,
                                                ConfigurableBaselineUpdatePolicy policy,
                                                StartupGrace grace,
                                                EnforcementHandler quarantine) {
        return engine(scorer, policy, grace, quarantine, SentinelMetrics.NOOP);
    }

    private static SentinelDecisionEngine engine(AnomalyScorer scorer,
                                                ConfigurableBaselineUpdatePolicy policy,
                                                StartupGrace grace,
                                                EnforcementHandler quarantine,
                                                SentinelMetrics metrics) {
        return new SentinelDecisionEngine(
            scorer,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            quarantine,
            NoopTel.INSTANCE,
            grace,
            metrics,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE,
            EnforcementAction.MONITOR,
            policy
        );
    }

    private static HttpRequestView shell() {
        return new MapHttpRequestView().requestUri(ENDPOINT).method("GET");
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
            .headerFingerprintHash(42L)
            .ipBucket(7)
            .build();
    }

    private static final class FixedAnomalyScorer implements AnomalyScorer {
        private final double score;
        private int updates;

        private FixedAnomalyScorer(double score) {
            this.score = score;
        }

        @Override
        public double score(RequestFeatures features) {
            return score;
        }

        @Override
        public void update(RequestFeatures features) {
            updates++;
        }
    }

    private static final class CountingMetrics implements SentinelMetrics {
        private final AtomicInteger acceptedWarmup = new AtomicInteger();
        private final AtomicInteger skipped = new AtomicInteger();

        @Override
        public void recordBaselineUpdateAccepted(String policyMode, boolean warmup) {
            if (warmup) {
                acceptedWarmup.incrementAndGet();
            }
        }

        @Override
        public void recordBaselineUpdateSkipped(String policyMode) {
            skipped.incrementAndGet();
        }
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

    private enum AlwaysQuarantined implements EnforcementHandler {
        INSTANCE;

        @Override
        public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                             String identityHash, String endpoint) {
            return true;
        }

        @Override
        public boolean isQuarantined(String identityHash, String endpoint) {
            return true;
        }
    }

    private enum NoopTel implements TelemetryEmitter {
        INSTANCE;

        @Override
        public void emit(dev.aisentinel.core.telemetry.TelemetryEvent event) {
        }
    }
}
