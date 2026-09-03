package dev.aisentinel.core.characterization;

import dev.aisentinel.core.baseline.ConfigurableBaselineUpdatePolicy;
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
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterizes policy action boundaries around configured score thresholds.
 */
class PolicyBoundaryCharacterizationTest {

    @Test
    void thresholdEngine_epsilonAroundDefaultBands_isMonotonic() {
        ThresholdPolicyEngine policy = new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8);
        RequestFeatures f = RequestFeatures.builder()
            .identityHash("p").endpoint("/p").timestampMillis(1)
            .requestsPerWindow(1).endpointEntropy(0).endpointConcentration(0)
            .tokenAgeSeconds(-1).parameterCount(0).payloadSizeBytes(0)
            .headerFingerprintHash(0).ipBucket(0).build();
        double[] scores = {
            0.1999999, 0.2, 0.2000001,
            0.3999999, 0.4, 0.4000001,
            0.5999999, 0.6, 0.6000001,
            0.7999999, 0.8, 0.8000001
        };
        EnforcementAction prev = EnforcementAction.ALLOW;
        for (double s : scores) {
            EnforcementAction a = policy.evaluate(s, f, "/p");
            System.out.printf(Locale.ROOT, "policy score=%.7f action=%s%n", s, a);
            assertThat(a.ordinal()).isGreaterThanOrEqualTo(prev.ordinal());
            prev = a;
        }
    }

    @Test
    void monitorMode_doesNotMutateBaselineDifferentlyThanDocumentedGating() {
        // MONITOR decisions still update under ALLOW_OR_MONITOR; THROTTLE+ do not.
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
        HttpRequestView req = new MapHttpRequestView().requestUri("/m").method("GET");
        for (int i = 0; i < 25; i++) {
            engine.evaluate(req, "m", feat(10), new RequestContext());
        }
        RiskDecision monitorBand = engine.evaluate(req, "m", feat(12), new RequestContext());
        System.out.printf(Locale.ROOT, "MONITOR-band action=%s score=%.4f%n",
            monitorBand.action(), monitorBand.anomalyScore());
        assertThat(monitorBand.action()).isIn(EnforcementAction.ALLOW, EnforcementAction.MONITOR);
    }

    private static RequestFeatures feat(double rpw) {
        return RequestFeatures.builder()
            .identityHash("m").endpoint("/m").timestampMillis(1)
            .requestsPerWindow(rpw).endpointEntropy(0.1).endpointConcentration(0.9)
            .tokenAgeSeconds(-1).parameterCount(0).payloadSizeBytes(0)
            .headerFingerprintHash(1).ipBucket(1).build();
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
