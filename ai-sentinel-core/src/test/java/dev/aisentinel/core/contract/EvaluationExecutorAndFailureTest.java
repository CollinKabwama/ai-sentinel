package dev.aisentinel.core.contract;

import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.decision.SentinelDecisionEngine;
import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import dev.aisentinel.core.feature.FeatureExtractor;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.http.HttpRequestView;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationExecutorAndFailureTest {

    @Test
    void remoteFailureResponseIsFailOpenNotHighRisk() {
        EvaluationResponse response = EvaluationFailureResponses.remoteFailure("c-1");
        assertThat(response.action()).isEqualTo(EnforcementAction.ALLOW);
        assertThat(response.proceed()).isTrue();
        assertThat(response.anomalyScore()).isNull();
        assertThat(response.policyScore()).isNull();
        assertThat(response.evaluationStatuses()).containsExactly("REMOTE_EVALUATION_FAILURE");
        assertThat(response.factors()).isEmpty();
    }

    @Test
    void localExecutorMapsNullBridgeToFailOpen() {
        LocalEvaluationBridge bridge = new LocalEvaluationBridge(
            (request, identityHash, ctx) -> {
                throw new RuntimeException("extract fail");
            },
            engine(0.1));
        EvaluationResponse response = new LocalEvaluationExecutor(bridge).evaluate(
            EvaluationRequest.builder().correlationId("x").identityKey("id").path("/api").build());
        assertThat(response.action()).isEqualTo(EnforcementAction.ALLOW);
        assertThat(response.proceed()).isTrue();
        assertThat(response.evaluationStatuses()).doesNotContain("REMOTE_EVALUATION_FAILURE");
    }

    @Test
    void localExecutorDelegatesSuccessfulDecision() {
        LocalEvaluationBridge bridge = new LocalEvaluationBridge(fixedExtractor("id"), engine(0.05));
        EvaluationResponse response = new LocalEvaluationExecutor(bridge).evaluate(
            EvaluationRequest.builder().correlationId("ok").identityKey("id").path("/api").build());
        assertThat(response.action()).isEqualTo(EnforcementAction.ALLOW);
        assertThat(response.anomalyScore()).isEqualTo(0.05);
    }

    @Test
    void responseValidatorRejectsVersionMismatchAndCorrelationMismatch() {
        EvaluationResponse badVersion = new EvaluationResponse(
            99, "c", EnforcementAction.ALLOW, java.util.List.of(), 0.1, 0.1, false, true, "/a",
            java.util.List.of(), null);
        assertThatThrownBy(() -> EvaluationResponseValidator.validate(badVersion, "c"))
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("contractVersion");

        EvaluationResponse mismatch = EvaluationFailureResponses.remoteFailure("other");
        assertThatThrownBy(() -> EvaluationResponseValidator.validate(mismatch, "expected"))
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("correlationId");
    }

    @Test
    void remoteEvaluationFailureStatusExists() {
        assertThat(EvaluationStatus.REMOTE_EVALUATION_FAILURE.name())
            .isEqualTo("REMOTE_EVALUATION_FAILURE");
    }

    private static FeatureExtractor fixedExtractor(String identity) {
        return (request, identityHash, ctx) -> RequestFeatures.builder()
            .identityHash(identity)
            .endpoint(request.getRequestURI())
            .timestampMillis(1L)
            .requestsPerWindow(1)
            .endpointEntropy(0.1)
            .endpointConcentration(0.1)
            .tokenAgeSeconds(-1)
            .parameterCount(0)
            .payloadSizeBytes(0)
            .headerFingerprintHash(1L)
            .ipBucket(1)
            .build();
    }

    private static SentinelDecisionEngine engine(double score) {
        return new SentinelDecisionEngine(
            new FixedScorer(score),
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            NEVER_QUARANTINED,
            (TelemetryEmitter) event -> {
            },
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE);
    }

    private static final EnforcementHandler NEVER_QUARANTINED = new EnforcementHandler() {
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

    private static final class FixedScorer implements AnomalyScorer {
        private final double score;

        private FixedScorer(double score) {
            this.score = score;
        }

        @Override
        public double score(RequestFeatures features) {
            return score;
        }

        @Override
        public void update(RequestFeatures features) {
        }
    }
}
