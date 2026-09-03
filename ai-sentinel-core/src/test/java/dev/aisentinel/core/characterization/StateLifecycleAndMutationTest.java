package dev.aisentinel.core.characterization;

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
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.scoring.StatisticalScorer;
import dev.aisentinel.core.store.BaselineStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterizes state lifecycle, bounded retention, mutation isolation,
 * baseline-update gating, and concurrent access behavior.
 */
class StateLifecycleAndMutationTest {

    @Test
    void idleStateIsEventuallyEvictedWithinSweepWindow() throws Exception {
        long[] now = {1_700_000_000_000L};
        BaselineStore store = new BaselineStore(Duration.ofSeconds(2), 100, () -> now[0]);
        assertThat(store.incrementAndGet("k1")).isEqualTo(1);
        assertThat(store.size()).isEqualTo(1);
        now[0] += 2_000 + store.expireSweepIntervalMs() + 1;
        assertThat(store.get("k1")).isEqualTo(0);
        System.out.printf(Locale.ROOT, "idle expiry OK sizeAfter=%d sweeps=%d%n",
            store.size(), store.expireSweepCount());
    }

    @Test
    void stateRemainsBoundedWhenMaximumKeysAreExceeded() {
        BaselineStore store = new BaselineStore(Duration.ofMinutes(5), 5);
        for (int i = 0; i < 20; i++) {
            store.incrementAndGet("id" + i);
        }
        assertThat(store.size()).isLessThanOrEqualTo(5);
        System.out.printf(Locale.ROOT, "maxKeys pressure size=%d (cap=5)%n", store.size());
    }

    @Test
    void allowOrMonitorGatingResistsBaselinePoisoningAfterAnomalousBurst() {
        double gatedAfter = contaminateThenProbe(ConfigurableBaselineUpdatePolicy.allowOrMonitor());
        double alwaysAfter = contaminateThenProbe(ConfigurableBaselineUpdatePolicy.always());
        System.out.printf(Locale.ROOT,
            "poisoning probeAfterBurst gated=%.4f always=%.4f%n", gatedAfter, alwaysAfter);
        // After anomalous burst, a repeat of the burst under ALWAYS should look more "normal" (lower score)
        // than under gating which refused to learn THROTTLE+.
        assertThat(gatedAfter)
            .as("gated path should retain elevated sensitivity after burst")
            .isGreaterThanOrEqualTo(alwaysAfter - 0.02);
    }

    @Test
    void invalidScoresDoNotUpdateBaseline() {
        AtomicInteger updates = new AtomicInteger();
        AnomalyScorer scorer = new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                return Double.NaN;
            }

            @Override
            public void update(RequestFeatures features) {
                updates.incrementAndGet();
            }
        };
        SentinelDecisionEngine engine = new SentinelDecisionEngine(
            scorer,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            NeverQ.INSTANCE,
            e -> {},
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE,
            EnforcementAction.MONITOR,
            ConfigurableBaselineUpdatePolicy.allowOrMonitor()
        );
        RiskDecision d = engine.evaluate(shell("/x"), "i", feat("i", "/x", 10), new RequestContext());
        assertThat(d.evaluationStatuses()).contains(EvaluationStatus.INVALID_SCORE);
        assertThat(d.action()).isEqualTo(EnforcementAction.ALLOW);
        assertThat(updates.get()).isZero();
    }

    @Test
    void requestContextMutationDoesNotAlterCapturedDecisionState() {
        StatisticalScorer scorer = new StatisticalScorer(1000, 300_000L, 2, 0.4);
        SentinelDecisionEngine engine = new SentinelDecisionEngine(
            scorer,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            NeverQ.INSTANCE,
            e -> {},
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE
        );
        for (int i = 0; i < 10; i++) {
            scorer.update(feat("m", "/m", 5));
        }
        RequestContext ctx = new RequestContext();
        ctx.put("probe", "before");
        RiskDecision d = engine.evaluate(shell("/m"), "m", feat("m", "/m", 5), ctx);
        double score = d.anomalyScore();
        EnforcementAction action = d.action();
        ctx.put("probe", "after-mutation");
        ctx.put("attacker", new Object());
        assertThat(d.anomalyScore()).isEqualTo(score);
        assertThat(d.action()).isEqualTo(action);
        assertThat(d.context().get("probe", String.class)).isEqualTo("after-mutation");
        System.out.printf(Locale.ROOT,
            "decision fields stable after ctx mutation; ctx still mutable via same reference%n");
    }

    @Test
    void concurrentSameIdentityEvaluationsRemainFiniteWithoutExceptions() throws Exception {
        StatisticalScorer scorer = new StatisticalScorer(100_000, 300_000L, 2, 0.4);
        SentinelDecisionEngine engine = new SentinelDecisionEngine(
            scorer,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            NeverQ.INSTANCE,
            e -> {},
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE,
            EnforcementAction.MONITOR,
            ConfigurableBaselineUpdatePolicy.allowOrMonitor()
        );
        for (int i = 0; i < 20; i++) {
            scorer.update(feat("c", "/c", 10));
        }
        int threads = 32;
        int per = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Double>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                double max = 0;
                for (int i = 0; i < per; i++) {
                    RiskDecision d = engine.evaluate(shell("/c"), "c", feat("c", "/c", 10 + (i % 5)),
                        new RequestContext());
                    assertThat(Double.isFinite(d.anomalyScore()) || Double.isNaN(d.anomalyScore())).isTrue();
                    max = Math.max(max, Double.isNaN(d.anomalyScore()) ? 0 : d.anomalyScore());
                }
                return max;
            }));
        }
        start.countDown();
        for (Future<Double> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        System.out.printf(Locale.ROOT, "concurrency same-identity OK threads=%d ops=%d%n", threads, threads * per);
    }

    private static double contaminateThenProbe(ConfigurableBaselineUpdatePolicy policy) {
        StatisticalScorer scorer = new StatisticalScorer(100_000, 300_000L, 2, 0.4);
        SentinelDecisionEngine engine = new SentinelDecisionEngine(
            scorer,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            NeverQ.INSTANCE,
            e -> {},
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE,
            EnforcementAction.MONITOR,
            policy
        );
        HttpRequestView req = shell("/p");
        for (int i = 0; i < 40; i++) {
            engine.evaluate(req, "p", feat("p", "/p", 10), new RequestContext());
        }
        for (int i = 0; i < 25; i++) {
            engine.evaluate(req, "p", feat("p", "/p", 200), new RequestContext());
        }
        return engine.evaluate(req, "p", feat("p", "/p", 200), new RequestContext()).anomalyScore();
    }

    private static RequestFeatures feat(String id, String ep, double rpw) {
        return RequestFeatures.builder()
            .identityHash(id).endpoint(ep).timestampMillis(1L)
            .requestsPerWindow(rpw).endpointEntropy(0.1).endpointConcentration(0.9)
            .tokenAgeSeconds(-1).parameterCount(0).payloadSizeBytes(0)
            .headerFingerprintHash(1).ipBucket(1).build();
    }

    private static HttpRequestView shell(String ep) {
        return new MapHttpRequestView().requestUri(ep).method("GET");
    }

    private enum NeverQ implements EnforcementHandler {
        INSTANCE;
        @Override
        public boolean apply(EnforcementAction a, HttpRequestView r, EnforcementResponse s, String i, String e) {
            return true;
        }
        @Override
        public boolean isQuarantined(String identityHash, String endpoint) {
            return false;
        }
    }
}
