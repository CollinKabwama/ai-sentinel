package dev.aisentinel.core.decision;

import dev.aisentinel.core.baseline.ConfigurableBaselineUpdatePolicy;
import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.http.MapHttpRequestView;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.NoopTrustPolicyAdjuster;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.AnomalyScorer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fail-open scoring must not fabricate decision explanation evidence.
 */
class ScoringFailureDoesNotPublishExplanationTest {

    @Test
    void scorerException_returnsNullDecision_withoutExplanationEvidence() {
        AnomalyScorer boom = new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void update(RequestFeatures features) {
            }
        };
        EnforcementHandler handler = (action, request, response, identityHash, endpoint) -> true;
        SentinelDecisionEngine engine = new SentinelDecisionEngine(
            boom,
            new ThresholdPolicyEngine(0.3, 0.4, 0.7, 0.9),
            handler,
            event -> { },
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE,
            EnforcementAction.MONITOR,
            ConfigurableBaselineUpdatePolicy.allowOrMonitor()
        );
        RequestContext ctx = new RequestContext();
        RequestFeatures features = RequestFeatures.builder()
            .identityHash("id").endpoint("/x").timestampMillis(0)
            .requestsPerWindow(1).endpointEntropy(0).tokenAgeSeconds(60)
            .parameterCount(0).payloadSizeBytes(0).headerFingerprintHash(0).ipBucket(0)
            .build();

        RiskDecision decision = engine.evaluate(new MapHttpRequestView().requestUri("/x"), "id", features, ctx);
        assertThat(decision).isNull();
        assertThat(ctx.get(ExplanationContextKeys.DECISION_EXPLANATION, DecisionExplanationEvidence.class)).isNull();
    }
}
