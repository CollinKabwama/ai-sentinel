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
import dev.aisentinel.core.store.BaselineStore;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Permanent regression coverage for the statistical baseline lifecycle:
 * gated learning, explicit reset, and TTL alignment between {@link BaselineStore}
 * and {@link StatisticalScorer}.
 * <p>
 * Automatic skip-triggered relearn poisoning coverage lives in
 * {@link AutomaticRelearnPoisoningRegressionTest}.
 */
class BaselineLifecycleRegressionTest {

    private static final String IDENTITY = "id-lifecycle";
    private static final String ENDPOINT = "/api/lifecycle";

    @Test
    void relearnDisabled_sustainedThrottleDoesNotReset() {
        StatisticalScorer scorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        seedLive(scorer, 10.0);
        BaselineLifecycle lifecycle = new BaselineLifecycle(
            scorer, BaselineRelearnMode.DISABLED, SentinelMetrics.NOOP);
        SentinelDecisionEngine engine = engine(scorer, lifecycle);

        RiskDecision first = engine.evaluate(shell(), IDENTITY, features(150.0), new RequestContext());
        double firstScore = first.anomalyScore();
        for (int i = 0; i < 20; i++) {
            RiskDecision d = engine.evaluate(shell(), IDENTITY, features(150.0), new RequestContext());
            assertThat(d.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isTrue();
            assertThat(d.hasStatus(EvaluationStatus.BASELINE_RELEARNED)).isFalse();
            assertThat(d.anomalyScore()).isEqualTo(firstScore);
        }
        assertThat(lifecycle.reset(IDENTITY, ENDPOINT)).isFalse();
    }

    @Test
    void explicitReset_clearsStateWhenModeAllows() {
        StatisticalScorer scorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        seedLive(scorer, 10.0);
        assertThat(scorer.isWarmup(features(10.0))).isFalse();

        BaselineLifecycle disabled = new BaselineLifecycle(
            scorer, BaselineRelearnMode.DISABLED, SentinelMetrics.NOOP);
        assertThat(disabled.reset(IDENTITY, ENDPOINT)).isFalse();
        assertThat(scorer.isWarmup(features(10.0))).isFalse();

        BaselineLifecycle explicit = new BaselineLifecycle(
            scorer, BaselineRelearnMode.EXPLICIT_ONLY, SentinelMetrics.NOOP);
        assertThat(explicit.reset(IDENTITY, ENDPOINT)).isTrue();
        assertThat(scorer.isWarmup(features(10.0))).isTrue();
    }

    @Test
    void explicitReset_isObservableViaMetrics() {
        StatisticalScorer scorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        seedLive(scorer, 10.0);
        CountingMetrics metrics = new CountingMetrics();
        BaselineLifecycle lifecycle = new BaselineLifecycle(
            scorer, BaselineRelearnMode.EXPLICIT_ONLY, metrics);

        assertThat(lifecycle.reset(IDENTITY, ENDPOINT)).isTrue();
        assertThat(metrics.relearns.get()).isEqualTo(1);
        assertThat(lifecycle.reset(IDENTITY, ENDPOINT)).isFalse();
        assertThat(metrics.relearns.get()).isEqualTo(1);
    }

    @Test
    void idleExpiry_scorerAndStoreDropStaleKeys() throws Exception {
        Duration ttl = Duration.ofMillis(1000);
        BaselineStore store = new BaselineStore(ttl, 100);
        StatisticalScorer scorer = new StatisticalScorer(100, ttl.toMillis(), 2, 0.4);

        assertThat(store.ttlMs()).isEqualTo(scorer.ttlMs());

        String key = IDENTITY + "|" + ENDPOINT;
        store.incrementAndGet(key);
        seedLive(scorer, 10.0);
        assertThat(store.size()).isEqualTo(1);
        assertThat(scorer.metricsStateEntryCount()).isEqualTo(1);
        assertThat(scorer.isWarmup(features(10.0))).isFalse();

        // TTL + one sweep interval so the throttled expireIdle path actually runs.
        Thread.sleep(ttl.toMillis() + scorer.expireSweepIntervalMs() + 100);

        assertThat(store.get(key)).isZero();
        assertThat(store.size()).isZero();
        assertThat(scorer.isWarmup(features(10.0))).isTrue();
        assertThat(scorer.metricsStateEntryCount()).isZero();
    }

    @Test
    void expireSweep_throttledWithinInterval_storeAndScorer() {
        BaselineStore store = new BaselineStore(Duration.ofMinutes(5), 1000);
        StatisticalScorer scorer = new StatisticalScorer(1000, 300_000L, 2, 0.4);
        assertThat(store.expireSweepIntervalMs()).isEqualTo(scorer.expireSweepIntervalMs());

        RequestFeatures f = features(10.0);
        scorer.update(f);
        store.incrementAndGet(IDENTITY + "|" + ENDPOINT);

        long scorerSweepsBefore = scorer.expireSweepCount();
        long storeSweepsBefore = store.expireSweepCount();

        for (int i = 0; i < 200; i++) {
            scorer.score(f);
            store.get(IDENTITY + "|" + ENDPOINT);
        }

        // Within one sweep interval, repeated hot-path calls must not rescan on every call.
        assertThat(scorer.expireSweepCount() - scorerSweepsBefore).isLessThanOrEqualTo(1);
        assertThat(store.expireSweepCount() - storeSweepsBefore).isLessThanOrEqualTo(1);
    }

    @Test
    void synchronizedTtl_wiringUsesSameBoundForStoreAndScorer() {
        Duration ttl = Duration.ofMinutes(5);
        int maxKeys = 50_000;
        BaselineStore store = new BaselineStore(ttl, maxKeys);
        StatisticalScorer scorer = new StatisticalScorer(maxKeys, ttl.toMillis(), 2, 0.4);
        assertThat(store.ttlMs()).isEqualTo(scorer.ttlMs());
        assertThat(store.maxKeys()).isEqualTo(scorer.maxKeys());
    }

    @Test
    void restartBehaviour_newScorerInstanceIsColdStartWarmup() {
        StatisticalScorer live = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        seedLive(live, 10.0);
        assertThat(live.isWarmup(features(10.0))).isFalse();

        StatisticalScorer restarted = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        assertThat(restarted.isWarmup(features(10.0))).isTrue();
        assertThat(restarted.metricsStateEntryCount()).isZero();
    }

    @Test
    void warmup_alwaysLearnsUnderGatedPolicy() {
        StatisticalScorer scorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        SentinelDecisionEngine engine = engine(scorer, BaselineLifecycle.disabled());

        RiskDecision w1 = engine.evaluate(shell(), IDENTITY, features(5.0), new RequestContext());
        RiskDecision w2 = engine.evaluate(shell(), IDENTITY, features(5.0), new RequestContext());
        assertThat(w1.hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isTrue();
        assertThat(w2.hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isTrue();
        assertThat(w1.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isFalse();

        RiskDecision live = engine.evaluate(shell(), IDENTITY, features(5.0), new RequestContext());
        assertThat(live.hasStatus(EvaluationStatus.STATISTICAL_LIVE)).isTrue();
    }

    @Test
    void gatedLearning_skipsElevatedRisk() {
        StatisticalScorer scorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        seedLive(scorer, 10.0);
        SentinelDecisionEngine engine = engine(scorer, BaselineLifecycle.disabled());

        RiskDecision calm = engine.evaluate(shell(), IDENTITY, features(10.0), new RequestContext());
        assertThat(calm.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isFalse();

        RiskDecision elevated = engine.evaluate(shell(), IDENTITY, features(200.0), new RequestContext());
        assertThat(elevated.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isTrue();
        double frozen = elevated.anomalyScore();
        assertThat(engine.evaluate(shell(), IDENTITY, features(200.0), new RequestContext()).anomalyScore())
            .isEqualTo(frozen);
    }

    @Test
    void contaminationProtection_preservedWhenRelearnDisabled() {
        StatisticalScorer scorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        seedLive(scorer, 10.0);
        SentinelDecisionEngine engine = engine(scorer, BaselineLifecycle.disabled());

        double first = engine.evaluate(shell(), IDENTITY, features(200.0), new RequestContext()).anomalyScore();
        for (int i = 0; i < 30; i++) {
            assertThat(engine.evaluate(shell(), IDENTITY, features(200.0), new RequestContext()).anomalyScore())
                .isEqualTo(first);
        }
    }

    @Test
    void lifecycleFailOpen_nullScorerDoesNotBreakDecision() {
        StatisticalScorer scorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        seedLive(scorer, 10.0);
        BaselineLifecycle lifecycle = new BaselineLifecycle(
            null, BaselineRelearnMode.EXPLICIT_ONLY, SentinelMetrics.NOOP);
        SentinelDecisionEngine engine = engine(scorer, lifecycle);

        assertThatCode(() -> engine.evaluate(shell(), IDENTITY, features(150.0), new RequestContext()))
            .doesNotThrowAnyException();
        RiskDecision d = engine.evaluate(shell(), IDENTITY, features(150.0), new RequestContext());
        assertThat(d).isNotNull();
        assertThat(d.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isTrue();
        assertThat(d.hasStatus(EvaluationStatus.BASELINE_RELEARNED)).isFalse();
        assertThat(lifecycle.reset(IDENTITY, ENDPOINT)).isFalse();
    }

    private static void seedLive(StatisticalScorer scorer, double rpw) {
        RequestFeatures calm = features(rpw);
        for (int i = 0; i < 30; i++) {
            scorer.update(calm);
        }
    }

    private static SentinelDecisionEngine engine(StatisticalScorer scorer, BaselineLifecycle lifecycle) {
        return engine(scorer, lifecycle, SentinelMetrics.NOOP);
    }

    private static SentinelDecisionEngine engine(StatisticalScorer scorer,
                                                BaselineLifecycle lifecycle,
                                                SentinelMetrics metrics) {
        return new SentinelDecisionEngine(
            scorer,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            NeverQuarantined.INSTANCE,
            NoopTel.INSTANCE,
            StartupGrace.NEVER,
            metrics,
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

    private static final class CountingMetrics implements SentinelMetrics {
        final AtomicInteger relearns = new AtomicInteger();

        @Override
        public void recordBaselineRelearn(String reason) {
            relearns.incrementAndGet();
        }
    }
}
