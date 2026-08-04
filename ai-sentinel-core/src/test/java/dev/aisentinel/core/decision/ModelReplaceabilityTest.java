package dev.aisentinel.core.decision;

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
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.function.ToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The anomaly model is a replaceable component: any {@link AnomalyScorer} can be swapped in and the policy layer
 * still maps its score to the same {@link EnforcementAction}. No servlet, Spring, or ML runtime is involved.
 */
class ModelReplaceabilityTest {

    /** Any model shape reduces to "features in, score out" for the decision core. */
    private record StubScorer(String name, ToDoubleFunction<RequestFeatures> fn) implements AnomalyScorer {
        @Override
        public double score(RequestFeatures features) {
            return fn.applyAsDouble(features);
        }

        @Override
        public void update(RequestFeatures features) {
        }
    }

    private static final EnforcementHandler NEVER_QUARANTINED = new EnforcementHandler() {
        @Override
        public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                             String identityHash, String endpoint) {
            throw new AssertionError("decision engine must not apply enforcement");
        }
    };

    private static final TelemetryEmitter DISCARD = event -> { };

    private static RequestFeatures features(double requestsPerWindow) {
        return RequestFeatures.builder()
            .identityHash("h")
            .endpoint("/api")
            .timestampMillis(1L)
            .requestsPerWindow(requestsPerWindow)
            .endpointEntropy(0)
            .tokenAgeSeconds(60)
            .parameterCount(0)
            .payloadSizeBytes(0)
            .headerFingerprintHash(0)
            .ipBucket(0)
            .build();
    }

    private static EnforcementAction decide(AnomalyScorer scorer, RequestFeatures features) {
        SentinelDecisionEngine engine = new SentinelDecisionEngine(
            scorer,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            NEVER_QUARANTINED,
            DISCARD,
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE);
        return engine.evaluate(new MapHttpRequestView(), "h", features, new RequestContext()).action();
    }

    @ParameterizedTest
    @CsvSource({
        "0.00, ALLOW",
        "0.19, ALLOW",
        "0.20, MONITOR",
        "0.39, MONITOR",
        "0.40, THROTTLE",
        "0.59, THROTTLE",
        "0.60, BLOCK",
        "0.79, BLOCK",
        "0.80, QUARANTINE",
        "1.00, QUARANTINE"
    })
    void anyScorerReturningTheSameScoreProducesTheSameAction(double score, EnforcementAction expected) {
        AnomalyScorer constant = new StubScorer("constant", f -> score);
        AnomalyScorer viaFeature = new StubScorer("feature-derived", f -> f.requestsPerWindow() / 100.0);

        assertThat(decide(constant, features(1))).isEqualTo(expected);
        assertThat(decide(viaFeature, features(score * 100.0))).isEqualTo(expected);
    }

    @Test
    void swappingTheModelChangesTheActionWithoutTouchingPolicy() {
        RequestFeatures f = features(1);
        assertThat(decide(new StubScorer("quiet", x -> 0.05), f)).isEqualTo(EnforcementAction.ALLOW);
        assertThat(decide(new StubScorer("loud", x -> 0.85), f)).isEqualTo(EnforcementAction.QUARANTINE);
    }
}
