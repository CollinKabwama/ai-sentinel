package dev.aisentinel.core.regression;

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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lightweight latency smoke for the framework-free decision path. Not a certification benchmark.
 */
class DecisionEnginePerformanceSmokeTest {

    @Test
    void decisionEngine_averageLatencyUnderOneMillisecondOnWarmJvm() {
        AnomalyScorer scorer = new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                return 0.35;
            }

            @Override
            public void update(RequestFeatures features) {
            }
        };
        EnforcementHandler neverQ = new EnforcementHandler() {
            @Override
            public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                                 String identityHash, String endpoint) {
                return true;
            }

            @Override
            public boolean isQuarantined(String identityHash, String endpoint) {
                return false;
            }
        };
        SentinelDecisionEngine engine = new SentinelDecisionEngine(
            scorer,
            new ThresholdPolicyEngine(),
            neverQ,
            e -> {},
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE
        );
        RequestFeatures features = RequestFeatures.builder()
            .identityHash("perf")
            .endpoint("/api/perf")
            .timestampMillis(System.currentTimeMillis())
            .requestsPerWindow(1)
            .endpointEntropy(0.1)
            .tokenAgeSeconds(10)
            .parameterCount(1)
            .payloadSizeBytes(16)
            .headerFingerprintHash(1)
            .ipBucket(1)
            .build();
        HttpRequestView request = new MapHttpRequestView().requestUri("/api/perf").method("GET");
        RequestContext ctx = new RequestContext();

        for (int i = 0; i < 2_000; i++) {
            engine.evaluate(request, "perf", features, ctx);
        }

        int n = 10_000;
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            RiskDecision d = engine.evaluate(request, "perf", features, ctx);
            assertThat(d).isNotNull();
            assertThat(d.action()).isEqualTo(EnforcementAction.MONITOR);
        }
        long elapsedNs = System.nanoTime() - start;
        double avgMicros = (elapsedNs / (double) n) / 1_000.0;
        // Generous smoke bound: catch multi-ms accidental I/O on the hot path, not an SLA claim.
        assertThat(avgMicros)
            .as("avg decision latency µs=%s", avgMicros)
            .isLessThan(1_000.0);
    }
}
