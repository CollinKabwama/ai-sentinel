package dev.aisentinel.core.regression;

import dev.aisentinel.core.baseline.BaselineLifecycle;
import dev.aisentinel.core.baseline.BaselineRelearnMode;
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
 * Permanent R-127 coverage: mono-endpoint flooding is a <em>rate</em> problem, not an entropy problem.
 * <p>
 * Shannon entropy and {@code endpointConcentration} are both ~invariant under established mono-endpoint
 * traffic (0 and 1 respectively). Abrupt floods are detected by {@code requestsPerWindow} under default
 * gated baseline updates (scenario B). A pure unit staircase asymptotes to MONITOR (~0.31) under
 * continuous learning and must not freeze-escalate (scenario C / R-036). Concentration remains useful
 * for diverse→mono distribution shift without a rate change — that is not a flood.
 */
class SingleEndpointFloodRegressionTest {

    private static final String IDENTITY = "id-r127";
    private static final String ENDPOINT = "/api/checkout";
    private static final double CALM_RPW = 10.0;
    private static final double FLOOD_RPW = 100.0;
    private static final int BASELINE_N = 40;
    private static final Set<EnforcementAction> THROTTLE_PLUS = EnumSet.of(
        EnforcementAction.THROTTLE, EnforcementAction.BLOCK, EnforcementAction.QUARANTINE);

    @Test
    void scenarioA_establishedMonoEndpointBenign_staysLowRisk() {
        StatisticalScorer scorer = newScorer();
        SentinelDecisionEngine engine = gatedEngine(scorer);
        RequestFeatures calm = mono(CALM_RPW);
        for (int i = 0; i < BASELINE_N; i++) {
            scorer.update(calm);
        }
        RiskDecision d = engine.evaluate(shell(), IDENTITY, calm, new RequestContext());

        System.out.printf(Locale.ROOT,
            "R127-A calm rpw=%.1f entropy=%.3f conc=%.3f score=%.6f action=%s statuses=%s%n",
            calm.requestsPerWindow(), calm.endpointEntropy(), calm.endpointConcentration(),
            d.anomalyScore(), d.action(), d.evaluationStatuses());

        assertThat(calm.endpointEntropy()).isEqualTo(0.0);
        assertThat(calm.endpointConcentration()).isEqualTo(1.0);
        assertThat(d.anomalyScore()).isLessThan(0.2);
        assertThat(d.action()).isIn(EnforcementAction.ALLOW, EnforcementAction.MONITOR);
        assertThat(d.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isFalse();
    }

    @Test
    void scenarioB_abruptMonoEndpointFlood_crossesThrottleAndGatingHolds() {
        StatisticalScorer scorer = newScorer();
        SentinelDecisionEngine engine = gatedEngine(scorer);
        RequestFeatures calm = mono(CALM_RPW);
        for (int i = 0; i < BASELINE_N; i++) {
            scorer.update(calm);
        }
        RiskDecision calmProbe = engine.evaluate(shell(), IDENTITY, calm, new RequestContext());

        RequestFeatures flood = mono(FLOOD_RPW);
        List<RiskDecision> elevated = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            elevated.add(engine.evaluate(shell(), IDENTITY, flood, new RequestContext()));
        }
        RiskDecision first = elevated.get(0);
        RiskDecision last = elevated.get(elevated.size() - 1);

        System.out.printf(Locale.ROOT,
            "R127-B calmScore=%.6f firstFlood=%.6f/%s lastFlood=%.6f/%s entropy=%.3f conc=%.3f%n",
            calmProbe.anomalyScore(), first.anomalyScore(), first.action(),
            last.anomalyScore(), last.action(),
            flood.endpointEntropy(), flood.endpointConcentration());

        // Concentration unchanged vs calm mono baseline — not the flood discriminator.
        assertThat(flood.endpointConcentration()).isEqualTo(calm.endpointConcentration());
        assertThat(flood.endpointEntropy()).isEqualTo(calm.endpointEntropy());

        assertThat(first.anomalyScore()).isGreaterThan(calmProbe.anomalyScore() + 0.5);
        assertThat(THROTTLE_PLUS.contains(first.action())).isTrue();
        assertThat(last.anomalyScore()).isEqualTo(first.anomalyScore());
        assertThat(first.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isTrue();
        assertThat(last.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isTrue();
    }

    @Test
    void scenarioC_gradualMonoEndpointRamp_staysMonitorBandWithoutFreezeEscalate() {
        StatisticalScorer scorer = newScorer();
        SentinelDecisionEngine gated = gatedEngine(scorer);
        StatisticalScorer alwaysScorer = newScorer();
        SentinelDecisionEngine always = alwaysEngine(alwaysScorer);

        List<RiskDecision> gatedDecisions = new ArrayList<>();
        List<RiskDecision> alwaysDecisions = new ArrayList<>();
        for (int i = 1; i <= 90; i++) {
            RequestFeatures f = mono(i);
            gatedDecisions.add(gated.evaluate(shell(), IDENTITY, f, new RequestContext()));
            alwaysDecisions.add(always.evaluate(shell(), "id-always", f, new RequestContext()));
        }

        RiskDecision gatedLate = gatedDecisions.get(gatedDecisions.size() - 1);
        RiskDecision alwaysLate = alwaysDecisions.get(alwaysDecisions.size() - 1);
        long gatedThrottlePlus = gatedDecisions.stream().filter(d -> THROTTLE_PLUS.contains(d.action())).count();

        System.out.printf(Locale.ROOT,
            "R127-C gatedThrottlePlus=%d gatedLate=%.6f/%s alwaysLate=%.6f/%s%n",
            gatedThrottlePlus, gatedLate.anomalyScore(), gatedLate.action(),
            alwaysLate.anomalyScore(), alwaysLate.action());

        // Unit staircase asymptotes to MONITOR (~0.31) under continuous learning (F-001).
        // Default gating must keep learning through that band — not freeze early and escalate
        // to THROTTLE+/QUARANTINE (that freeze was the R-036 benign defect). Abrupt floods remain
        // covered by scenario B.
        assertThat(gatedThrottlePlus).isZero();
        assertThat(gatedLate.action()).isEqualTo(EnforcementAction.MONITOR);
        assertThat(gatedLate.anomalyScore()).isBetween(0.2, 0.4);
        assertThat(alwaysLate.anomalyScore()).isLessThan(0.4);
        assertThat(gatedLate.anomalyScore()).isGreaterThanOrEqualTo(alwaysLate.anomalyScore());
    }

    @Test
    void scenarioD_explicitReset_allowsNewLegitimateHighVolumeBaseline() {
        StatisticalScorer scorer = newScorer();
        BaselineLifecycle lifecycle = new BaselineLifecycle(
            scorer, BaselineRelearnMode.EXPLICIT_ONLY, SentinelMetrics.NOOP);
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
            ConfigurableBaselineUpdatePolicy.allowOrMonitor(),
            lifecycle
        );

        RequestFeatures calm = mono(CALM_RPW);
        for (int i = 0; i < BASELINE_N; i++) {
            scorer.update(calm);
        }
        RiskDecision flood = engine.evaluate(shell(), IDENTITY, mono(FLOOD_RPW), new RequestContext());
        assertThat(THROTTLE_PLUS.contains(flood.action())).isTrue();

        assertThat(lifecycle.reset(IDENTITY, ENDPOINT)).isTrue();

        // Post-reset warmup + learn the new legitimate high volume.
        RequestFeatures high = mono(FLOOD_RPW);
        RiskDecision w0 = engine.evaluate(shell(), IDENTITY, high, new RequestContext());
        RiskDecision w1 = engine.evaluate(shell(), IDENTITY, high, new RequestContext());
        for (int i = 0; i < 20; i++) {
            engine.evaluate(shell(), IDENTITY, high, new RequestContext());
        }
        RiskDecision settled = engine.evaluate(shell(), IDENTITY, high, new RequestContext());

        System.out.printf(Locale.ROOT,
            "R127-D flood=%.6f/%s warmup0=%.6f warmup1=%.6f settled=%.6f/%s%n",
            flood.anomalyScore(), flood.action(), w0.anomalyScore(), w1.anomalyScore(),
            settled.anomalyScore(), settled.action());

        assertThat(w0.hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isTrue();
        assertThat(settled.anomalyScore()).isLessThan(0.3);
        assertThat(settled.action()).isIn(EnforcementAction.ALLOW, EnforcementAction.MONITOR);
    }

    @Test
    void scenarioE_diverseToMonoWithoutRateIncrease_isDistributionShiftNotFloodClaim() {
        StatisticalScorer scorer = newScorer();
        RequestFeatures diverse = features(CALM_RPW, 1.3, 0.30);
        for (int i = 0; i < BASELINE_N; i++) {
            scorer.update(diverse);
        }
        double calmScore = scorer.score(diverse);
        double collapsed = scorer.score(features(CALM_RPW, 0.0, 1.0));

        System.out.printf(Locale.ROOT,
            "R127-E diverseScore=%.6f collapsedScore=%.6f (same rpw=%.1f)%n",
            calmScore, collapsed, CALM_RPW);

        assertThat(collapsed).isGreaterThan(calmScore);
        assertThat(collapsed).isGreaterThan(0.4);
        // Same rolling count — this is not a volume flood; concentration/entropy carry the shift.
    }

    @Test
    void extractorMonoFlood_entropyAndConcentrationUnchangedWhileRollingCountRises() {
        AtomicLong now = new AtomicLong(1_700_000_000_000L);
        BaselineStore store = new BaselineStore(Duration.ofMinutes(5), 10_000, now::get);
        DefaultFeatureExtractor extractor = new DefaultFeatureExtractor(store);
        MapHttpRequestView req = new MapHttpRequestView()
            .requestUri(ENDPOINT)
            .remoteAddr("198.51.100.40");

        RequestFeatures lastCalm = null;
        for (int i = 0; i < 15; i++) {
            lastCalm = extractor.extract(req, IDENTITY, new RequestContext());
            now.addAndGet(50);
        }
        assertThat(lastCalm).isNotNull();
        double calmEntropy = lastCalm.endpointEntropy();
        double calmConc = lastCalm.endpointConcentration();
        double calmRpw = lastCalm.requestsPerWindow();

        RequestFeatures lastFlood = null;
        for (int i = 0; i < 80; i++) {
            lastFlood = extractor.extract(req, IDENTITY, new RequestContext());
            now.addAndGet(50);
        }
        assertThat(lastFlood).isNotNull();

        System.out.printf(Locale.ROOT,
            "R127-extractor calmRpw=%.0f floodRpw=%.0f entropy %.3f→%.3f conc %.3f→%.3f%n",
            calmRpw, lastFlood.requestsPerWindow(), calmEntropy, lastFlood.endpointEntropy(),
            calmConc, lastFlood.endpointConcentration());

        assertThat(calmEntropy).isEqualTo(0.0);
        assertThat(lastFlood.endpointEntropy()).isEqualTo(0.0);
        assertThat(calmConc).isEqualTo(1.0);
        assertThat(lastFlood.endpointConcentration()).isEqualTo(1.0);
        assertThat(lastFlood.requestsPerWindow()).isGreaterThan(calmRpw + 50);
    }

    @Test
    void windowBoundary_doesNotDropRollingCountForActiveFlood() {
        AtomicLong now = new AtomicLong(1_700_000_000_050L);
        BaselineStore store = new BaselineStore(Duration.ofMinutes(5), 10_000, now::get);
        String key = IDENTITY + "|" + ENDPOINT;

        for (int i = 0; i < 25; i++) {
            store.incrementAndGet(key);
        }
        int before = store.get(key);
        long nextBucket = ((now.get() / BaselineStore.bucketMs()) + 1) * BaselineStore.bucketMs();
        now.set(nextBucket);
        int across = store.incrementAndGet(key);
        now.set(nextBucket + BaselineStore.bucketMs() / 2);
        int after = store.get(key);

        System.out.printf(Locale.ROOT,
            "R127-window before=%d across=%d after=%d%n", before, across, after);

        assertThat(across).isEqualTo(before + 1);
        assertThat(after).isEqualTo(across);
    }

    private static StatisticalScorer newScorer() {
        return new StatisticalScorer(100_000, 300_000L, 2, 0.4);
    }

    private static SentinelDecisionEngine gatedEngine(StatisticalScorer scorer) {
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
            BaselineLifecycle.disabled()
        );
    }

    private static SentinelDecisionEngine alwaysEngine(StatisticalScorer scorer) {
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
            ConfigurableBaselineUpdatePolicy.always(),
            BaselineLifecycle.disabled()
        );
    }

    private static RequestFeatures mono(double rpw) {
        return features(rpw, 0.0, 1.0);
    }

    private static RequestFeatures features(double rpw, double entropy, double concentration) {
        return RequestFeatures.builder()
            .identityHash(IDENTITY)
            .endpoint(ENDPOINT)
            .timestampMillis(1_700_000_000_000L)
            .requestsPerWindow(rpw)
            .endpointEntropy(entropy)
            .endpointConcentration(concentration)
            .tokenAgeSeconds(-1)
            .parameterCount(0)
            .payloadSizeBytes(0)
            .headerFingerprintHash(42L)
            .ipBucket(7)
            .build();
    }

    private static HttpRequestView shell() {
        return new MapHttpRequestView().requestUri(ENDPOINT).method("GET").remoteAddr("203.0.113.77");
    }

    private enum NoopTel implements TelemetryEmitter {
        INSTANCE;

        @Override
        public void emit(dev.aisentinel.core.telemetry.TelemetryEvent event) {
        }
    }

    private enum NeverQuarantined implements EnforcementHandler {
        INSTANCE;

        @Override
        public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                             String identityHash, String endpoint) {
            throw new AssertionError("decision engine must not apply enforcement");
        }

        @Override
        public boolean isQuarantined(String identityHash, String endpoint) {
            return false;
        }
    }
}
