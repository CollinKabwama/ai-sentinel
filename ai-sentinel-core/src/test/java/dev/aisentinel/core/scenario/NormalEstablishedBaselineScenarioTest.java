package dev.aisentinel.core.scenario;

import dev.aisentinel.core.baseline.ConfigurableBaselineUpdatePolicy;
import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.decision.RiskDecision;
import dev.aisentinel.core.decision.SentinelDecisionEngine;
import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import dev.aisentinel.core.feature.DefaultFeatureExtractor;
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scenario {@code normal-established-baseline}: benign traffic under default
 * {@code ALLOW_OR_MONITOR} gating.
 * <p>
 * Correct steady-state intent: once {@code requestsPerWindow} is stable, live scores converge
 * to ALLOW. A compressed-time extractor climb (rpw {@code 1..N} with no plateau) asymptotes to
 * MONITOR (~0.31) under continuous learning — not QUARANTINE. Early staircase freeze into
 * THROTTLE+ was the prior freeze-then-escalate defect.
 */
class NormalEstablishedBaselineScenarioTest {

    private static final String SCENARIO_ID = "normal-established-baseline";
    private static final String IDENTITY = "id-benign";
    private static final String ENDPOINT = "/api/hello";
    private static final int COMPRESSED_TOTAL = 60;
    private static final Set<EnforcementAction> THROTTLE_PLUS = EnumSet.of(
        EnforcementAction.THROTTLE, EnforcementAction.BLOCK, EnforcementAction.QUARANTINE);

    @Test
    void benignTraffic_beyondWarmup_isDeterministic() {
        List<DetectionScenarioRunner.ScenarioObservation> first = runCompressedExtractor(COMPRESSED_TOTAL);
        List<DetectionScenarioRunner.ScenarioObservation> second = runCompressedExtractor(COMPRESSED_TOTAL);

        assertThat(first).hasSize(COMPRESSED_TOTAL);
        assertThat(second).hasSize(COMPRESSED_TOTAL);

        assertThat(first.get(0).anomalyScore()).isEqualTo(ScenarioTestSupport.WARMUP_SCORE);
        assertThat(first.get(0).action()).isEqualTo(EnforcementAction.MONITOR);
        assertThat(first.get(1).anomalyScore()).isEqualTo(ScenarioTestSupport.WARMUP_SCORE);
        assertThat(first.get(1).action()).isEqualTo(EnforcementAction.MONITOR);
        assertThat(first.get(2).anomalyScore()).isNotEqualTo(ScenarioTestSupport.WARMUP_SCORE);

        DetectionScenarioRunner.ScenarioObservation last = first.get(COMPRESSED_TOTAL - 1);
        for (int i = 0; i < COMPRESSED_TOTAL; i++) {
            assertThat(second.get(i).anomalyScore()).isEqualTo(first.get(i).anomalyScore());
            assertThat(second.get(i).action()).isEqualTo(first.get(i).action());
            assertThat(second.get(i).features().headerFingerprintHash())
                .isEqualTo(first.get(i).features().headerFingerprintHash());
            assertThat(second.get(i).features().ipBucket())
                .isEqualTo(first.get(i).features().ipBucket());
            assertThat(second.get(i).features().parameterCount()).isEqualTo(0);
            assertThat(second.get(i).features().payloadSizeBytes()).isEqualTo(0L);
            assertThat(second.get(i).features().tokenAgeSeconds()).isEqualTo(-1.0);
        }

        long throttlePlus = first.stream().filter(o -> THROTTLE_PLUS.contains(o.action())).count();
        long allowCount = first.stream().filter(o -> o.action() == EnforcementAction.ALLOW).count();
        double minPostWarmup = first.stream().skip(2).mapToDouble(DetectionScenarioRunner.ScenarioObservation::anomalyScore)
            .min().orElseThrow();
        double maxPostWarmup = first.stream().skip(2).mapToDouble(DetectionScenarioRunner.ScenarioObservation::anomalyScore)
            .max().orElseThrow();

        System.out.printf(Locale.ROOT,
            "%s compressed lastRpw=%.0f lastScore=%.6f lastAction=%s allowCount=%d throttlePlus=%d "
                + "minPostWarmup=%.6f maxPostWarmup=%.6f%n",
            SCENARIO_ID, last.features().requestsPerWindow(), last.anomalyScore(), last.action(),
            allowCount, throttlePlus, minPostWarmup, maxPostWarmup);

        assertThat(last.startupGraceActive()).isFalse();
        assertThat(last.policyScore()).isEqualTo(last.anomalyScore());
        assertThat(last.features().requestsPerWindow()).isEqualTo(COMPRESSED_TOTAL);
        // No plateau in compressed time → MONITOR asymptote; must not freeze-escalate.
        assertThat(throttlePlus).isZero();
        assertThat(last.action()).isEqualTo(EnforcementAction.MONITOR);
        assertThat(last.anomalyScore()).isBetween(0.2, 0.4);
    }

    @Test
    void steadyRate_afterWindowFill_convergesToAllow() {
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        BaselineStore store = new BaselineStore(Duration.ofMinutes(5), 100_000, clock::get);
        DefaultFeatureExtractor extractor = new DefaultFeatureExtractor(store, 100_000, 300_000L);
        StatisticalScorer scorer = new StatisticalScorer(100_000, 300_000L, 2, 0.4);
        SentinelDecisionEngine engine = defaultEngine(scorer);

        List<RiskDecision> decisions = new ArrayList<>();
        double lastRpw = 0;
        // 1 req/s for 400s: fills the 5m window then holds near the plateau (~300–310).
        for (int i = 0; i < 400; i++) {
            RequestContext ctx = new RequestContext();
            RequestFeatures features = extractor.extract(stableRequest(), IDENTITY, ctx);
            RiskDecision d = engine.evaluate(stableRequest(), IDENTITY, features, ctx);
            assertThat(d).isNotNull();
            decisions.add(d);
            lastRpw = features.requestsPerWindow();
            clock.addAndGet(1_000L);
        }

        RiskDecision last = decisions.get(decisions.size() - 1);
        long throttlePlus = decisions.stream().filter(d -> THROTTLE_PLUS.contains(d.action())).count();
        long allowTail = decisions.stream().skip(350).filter(d -> d.action() == EnforcementAction.ALLOW).count();

        System.out.printf(Locale.ROOT,
            "%s steadyRate lastRpw=%.0f lastScore=%.6f lastAction=%s statuses=%s "
                + "throttlePlus=%d allowInLast50=%d%n",
            SCENARIO_ID, lastRpw, last.anomalyScore(), last.action(), last.evaluationStatuses(),
            throttlePlus, allowTail);

        assertThat(throttlePlus).as("benign steady rate must not freeze into THROTTLE+").isZero();
        assertThat(lastRpw).isGreaterThan(250);
        assertThat(last.hasStatus(EvaluationStatus.STATISTICAL_LIVE)).isTrue();
        assertThat(last.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isFalse();
        assertThat(last.action()).isEqualTo(EnforcementAction.ALLOW);
        assertThat(last.anomalyScore()).isLessThan(ScenarioTestSupport.THRESHOLD_MODERATE);
        assertThat(allowTail).isGreaterThan(40);
    }

    @Test
    void constantVolume_featureFixture_convergesToAllow() {
        StatisticalScorer scorer = new StatisticalScorer(100_000, 300_000L, 2, 0.4);
        SentinelDecisionEngine engine = defaultEngine(scorer);
        List<RiskDecision> decisions = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            RequestFeatures f = ScenarioTestSupport.baseFeatures(IDENTITY, ENDPOINT, 30);
            decisions.add(engine.evaluate(stableRequest(), IDENTITY, f, new RequestContext()));
        }
        RiskDecision last = decisions.get(decisions.size() - 1);
        System.out.printf(Locale.ROOT,
            "%s constantRpw lastScore=%.6f lastAction=%s%n",
            SCENARIO_ID, last.anomalyScore(), last.action());
        assertThat(last.action()).isEqualTo(EnforcementAction.ALLOW);
        assertThat(last.anomalyScore()).isLessThan(0.05);
    }

    private static List<DetectionScenarioRunner.ScenarioObservation> runCompressedExtractor(int total) {
        DetectionScenarioRunner runner = DetectionScenarioRunner.statisticalOnly();
        List<DetectionScenarioRunner.DetectionScenarioStep> steps = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            steps.add(new DetectionScenarioRunner.DetectionScenarioStep(IDENTITY, stableRequest()));
        }
        return runner.run(new DetectionScenarioRunner.DetectionScenario(SCENARIO_ID, List.copyOf(steps)));
    }

    private static SentinelDecisionEngine defaultEngine(StatisticalScorer scorer) {
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
            ConfigurableBaselineUpdatePolicy.allowOrMonitor()
        );
    }

    private static MapHttpRequestView stableRequest() {
        return new MapHttpRequestView()
            .requestUri(ENDPOINT)
            .method("GET")
            .remoteAddr("203.0.113.60")
            .header("Accept", "application/json");
    }

    private enum NeverQuarantined implements EnforcementHandler {
        INSTANCE;

        @Override
        public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                             String identityHash, String endpoint) {
            throw new AssertionError("scenario must not apply enforcement via the decision engine");
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
