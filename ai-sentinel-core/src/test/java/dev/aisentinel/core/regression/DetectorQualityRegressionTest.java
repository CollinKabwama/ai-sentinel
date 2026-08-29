package dev.aisentinel.core.regression;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused regressions for detector-quality invariants:
 * near-zero variance (role-aware floors / z-caps), identity-like feature exclusion,
 * and endpoint entropy vs concentration.
 */
class DetectorQualityRegressionTest {

    private static final double THRESHOLD_CRITICAL = 0.8;

    @Test
    void tinyOrdinalFlip_scoresFarBelowGenuineRateAnomaly() {
        StatisticalScorer scorer = new StatisticalScorer(100_000, 300_000L, 2, 0.4);
        SentinelDecisionEngine engine = newEngine(scorer);
        RequestFeatures baseline = features("id-dq", "/api/a", 10, 0.1, 0.0, -1, 0, 0L, 42L, 7);
        for (int i = 0; i < 40; i++) {
            scorer.update(baseline);
        }
        RiskDecision tiny = engine.evaluate(shell("/api/a"), "id-dq",
            features("id-dq", "/api/a", 10, 0.1, 0.0, -1, 1, 0L, 42L, 7), new RequestContext());
        RiskDecision burst = engine.evaluate(shell("/api/a"), "id-dq",
            features("id-dq", "/api/a", 200, 0.1, 0.0, -1, 0, 0L, 42L, 7), new RequestContext());

        assertThat(tiny.anomalyScore()).isLessThan(0.5);
        assertThat(burst.anomalyScore()).isGreaterThan(tiny.anomalyScore() + 0.3);
        assertThat(burst.anomalyScore()).isGreaterThanOrEqualTo(THRESHOLD_CRITICAL);
    }

    @Test
    void hashAndIpFlip_doNotMoveStatisticalScore() {
        StatisticalScorer scorer = new StatisticalScorer(100_000, 300_000L, 2, 0.4);
        RequestFeatures baseline = features("id-hash", "/api/b", 10, 0.2, 0.5, -1, 2, 100L, 42L, 7);
        for (int i = 0; i < 30; i++) {
            scorer.update(baseline);
        }
        double calm = scorer.score(baseline);
        double headerFlip = scorer.score(
            features("id-hash", "/api/b", 10, 0.2, 0.5, -1, 2, 100L, 99_999L, 7));
        double ipFlip = scorer.score(
            features("id-hash", "/api/b", 10, 0.2, 0.5, -1, 2, 100L, 42L, 999));

        assertThat(headerFlip).isEqualTo(calm);
        assertThat(ipFlip).isEqualTo(calm);
    }

    @Test
    void singleEndpointFlood_hasLowEntropyAndHighConcentration() {
        BaselineStore store = new BaselineStore(Duration.ofMinutes(5), 10_000);
        DefaultFeatureExtractor extractor = new DefaultFeatureExtractor(store);
        MapHttpRequestView req = new MapHttpRequestView()
            .requestUri("/api/flood")
            .remoteAddr("198.51.100.10");

        RequestFeatures last = null;
        for (int i = 0; i < 40; i++) {
            last = extractor.extract(req, "id-flood", new RequestContext());
        }
        assertThat(last).isNotNull();
        assertThat(last.endpointEntropy()).isEqualTo(0.0);
        assertThat(last.endpointConcentration()).isEqualTo(1.0);
        assertThat(last.requestsPerWindow()).isEqualTo(40);
    }

    @Test
    void multiEndpointTraffic_raisesEntropyAndLowersConcentration() {
        BaselineStore store = new BaselineStore(Duration.ofMinutes(5), 10_000);
        DefaultFeatureExtractor extractor = new DefaultFeatureExtractor(store);
        String[] endpoints = {"/api/a", "/api/b", "/api/c", "/api/d"};
        RequestFeatures last = null;
        for (int i = 0; i < 40; i++) {
            MapHttpRequestView req = new MapHttpRequestView()
                .requestUri(endpoints[i % endpoints.length])
                .remoteAddr("198.51.100.20");
            last = extractor.extract(req, "id-diverse", new RequestContext());
        }
        assertThat(last).isNotNull();
        assertThat(last.endpointEntropy()).isGreaterThan(1.0);
        assertThat(last.endpointConcentration()).isLessThan(0.5);
    }

    @Test
    void diversityCollapse_raisesStatisticalScore() {
        StatisticalScorer scorer = new StatisticalScorer(100_000, 300_000L, 2, 0.4);
        RequestFeatures diverse = features("id-collapse", "/api/x", 10, 1.3, 0.3, -1, 1, 50L, 1L, 1);
        for (int i = 0; i < 40; i++) {
            scorer.update(diverse);
        }
        double calm = scorer.score(diverse);
        double collapsed = scorer.score(
            features("id-collapse", "/api/x", 10, 0.0, 1.0, -1, 1, 50L, 1L, 1));

        assertThat(collapsed).isGreaterThan(calm);
        assertThat(collapsed).isGreaterThan(0.4);
    }

    private static RequestFeatures features(String identity, String endpoint, double rpw,
                                            double entropy, double concentration, double tokenAge,
                                            int params, long payload, long headerFp, int ipBucket) {
        return RequestFeatures.builder()
            .identityHash(identity)
            .endpoint(endpoint)
            .timestampMillis(1_700_000_000_000L)
            .requestsPerWindow(rpw)
            .endpointEntropy(entropy)
            .endpointConcentration(concentration)
            .tokenAgeSeconds(tokenAge)
            .parameterCount(params)
            .payloadSizeBytes(payload)
            .headerFingerprintHash(headerFp)
            .ipBucket(ipBucket)
            .build();
    }

    private static MapHttpRequestView shell(String endpoint) {
        return new MapHttpRequestView().requestUri(endpoint).method("GET").remoteAddr("203.0.113.50");
    }

    private static SentinelDecisionEngine newEngine(StatisticalScorer scorer) {
        return new SentinelDecisionEngine(
            scorer,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            NeverQuarantined.INSTANCE,
            NoopTel.INSTANCE,
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE
        );
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
