package dev.aisentinel.core.contract;

import dev.aisentinel.core.decision.AdvisoryCode;
import dev.aisentinel.core.decision.AdvisoryPriority;
import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.decision.RiskDecision;
import dev.aisentinel.core.decision.RiskExplanation;
import dev.aisentinel.core.decision.RiskFactor;
import dev.aisentinel.core.decision.RiskFactorCategory;
import dev.aisentinel.core.decision.RiskFactorCode;
import dev.aisentinel.core.decision.RiskFactorSeverity;
import dev.aisentinel.core.decision.SecurityAdvice;
import dev.aisentinel.core.http.MapHttpRequestView;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationResponseContractTest {

    @Test
    void invalidScoreMapsToNullScoresAndStatus() {
        RiskDecision decision = new RiskDecision(
            EnforcementAction.ALLOW,
            Double.NaN,
            Double.NaN,
            features(),
            new RequestContext(),
            false,
            Set.of(EvaluationStatus.INVALID_SCORE),
            RiskExplanation.empty());
        EvaluationRequest request = EvaluationRequest.builder()
            .correlationId("c1")
            .identityKey("id")
            .path("/api")
            .build();
        EvaluationResponse response = EvaluationContractMapper.toResponse(request, decision, true);
        assertThat(response.anomalyScore()).isNull();
        assertThat(response.policyScore()).isNull();
        assertThat(response.evaluationStatuses()).contains("INVALID_SCORE");
        assertThat(response.action()).isEqualTo(EnforcementAction.ALLOW);
    }

    @Test
    void responseRejectsNonFiniteScores() {
        assertThatThrownBy(() -> new EvaluationResponse(
            1, "c", EnforcementAction.ALLOW, List.of(), Double.NaN, null, false, true, "/x",
            List.of(), null))
            .isInstanceOf(EvaluationContractException.class);
    }

    @Test
    void remoteFailureStatusMustRemainFailOpenAllowShape() {
        EvaluationResponse blocking = new EvaluationResponse(
            1, "c", EnforcementAction.BLOCK, List.of(EvaluationStatus.REMOTE_EVALUATION_FAILURE.name()),
            null, null, false, false, "", List.of(), null);
        assertThatThrownBy(() -> EvaluationResponseValidator.validate(blocking, "c"))
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("REMOTE_EVALUATION_FAILURE");
    }

    @Test
    void remoteFailureStatusRejectsRiskPayload() {
        EvaluationResponse withScore = new EvaluationResponse(
            1, "c", EnforcementAction.ALLOW, List.of(EvaluationStatus.REMOTE_EVALUATION_FAILURE.name()),
            0.1, null, false, true, "", List.of(), null);
        assertThatThrownBy(() -> EvaluationResponseValidator.validate(withScore, "c"))
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("REMOTE_EVALUATION_FAILURE");
    }

    @Test
    void factorsAndAdviceAreImmutable() {
        RiskFactor factor = new RiskFactor(
            RiskFactorCode.PIPELINE_DEGRADED, RiskFactorCategory.SYSTEM, RiskFactorSeverity.MEDIUM,
            1.0, 0.9, EvaluationStatus.DEGRADED.name(), "degraded", "status");
        SecurityAdvice advice = new SecurityAdvice(
            AdvisoryCode.OTHER_OPERATOR_REVIEW, AdvisoryPriority.MEDIUM, "review",
            List.of(RiskFactorCode.PIPELINE_DEGRADED), true);
        RiskDecision decision = new RiskDecision(
            EnforcementAction.ALLOW, 0.1, 0.1, features(), new RequestContext(), false,
            Set.of(EvaluationStatus.DEGRADED), new RiskExplanation(List.of(factor), advice));
        EvaluationResponse response = EvaluationContractMapper.toResponse(
            EvaluationRequest.builder().correlationId("c").identityKey("id").path("/api").build(),
            decision, true);
        assertThat(response.evaluationStatuses()).contains("DEGRADED");
        assertThat(response.advice().code()).isEqualTo("OTHER_OPERATOR_REVIEW");
        assertThatThrownBy(() -> response.factors().add(
            new ContractRiskFactor("X", "Y", "Z", 1, 1, "", "", "")))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> response.advice().linkedFactorCodes().add("X"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void evidenceRefWithTokenKeywordIsRedacted() {
        assertThat(EvaluationContractMapper.sanitizeEvidenceRef("Authorization")).isEqualTo("redacted");
        assertThat(EvaluationContractMapper.sanitizeEvidenceRef("requestsPerWindow"))
            .isEqualTo("requestsPerWindow");
    }

    @Test
    void jsonSafeProjectionHasNoNanOrClassNames() {
        RiskDecision decision = new RiskDecision(
            EnforcementAction.MONITOR, Double.POSITIVE_INFINITY, Double.NaN, features(),
            new RequestContext(), false, Set.of(EvaluationStatus.INVALID_SCORE), RiskExplanation.empty());
        EvaluationResponse response = EvaluationContractMapper.toResponse(
            EvaluationRequest.builder().correlationId("c").identityKey("id").path("/api").build(),
            decision, true);
        Map<String, Object> json = toJsonLike(response);
        assertThat(json.get("anomalyScore")).isNull();
        assertThat(json.get("policyScore")).isNull();
        assertThat(json.toString()).doesNotContain("NaN").doesNotContain("Infinity");
        assertThat(json.toString()).doesNotContain("dev.aisentinel");
    }

    @Test
    void fromHttpRequestViewRoundTripPreservesCoreFields() {
        MapHttpRequestView view = new MapHttpRequestView()
            .requestUri("/api/orders")
            .method("POST")
            .remoteAddr("10.0.0.2")
            .header("content-length", "12")
            .parameter("q", "1")
            .session("sess-1", 100L, 100L, true);
        EvaluationRequest request = EvaluationContractMapper.fromHttpRequestView(view, "hash-1", "corr-9");
        assertThat(request.path()).isEqualTo("/api/orders");
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.identityKey()).isEqualTo("hash-1");
        assertThat(request.headers()).containsEntry("content-length", "12");
        assertThat(request.headers()).doesNotContainKey("Authorization");
        ContractHttpRequestView bridged = new ContractHttpRequestView(request);
        assertThat(bridged.getRequestURI()).isEqualTo("/api/orders");
        assertThat(bridged.getHeader("Content-Length")).isEqualTo("12");
        assertThat(bridged.isNewSession()).isTrue();
    }

    private static Map<String, Object> toJsonLike(EvaluationResponse response) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("contractVersion", response.contractVersion());
        m.put("correlationId", response.correlationId());
        m.put("action", response.action().name());
        m.put("evaluationStatuses", new ArrayList<>(response.evaluationStatuses()));
        m.put("anomalyScore", response.anomalyScore());
        m.put("policyScore", response.policyScore());
        m.put("startupGraceActive", response.startupGraceActive());
        m.put("proceed", response.proceed());
        m.put("endpoint", response.endpoint());
        m.put("factors", response.factors());
        m.put("advice", response.advice());
        return m;
    }

    private static RequestFeatures features() {
        return RequestFeatures.builder()
            .identityHash("id")
            .endpoint("/api")
            .timestampMillis(1L)
            .requestsPerWindow(1)
            .endpointEntropy(0)
            .endpointConcentration(0)
            .tokenAgeSeconds(-1)
            .parameterCount(0)
            .payloadSizeBytes(0)
            .headerFingerprintHash(0)
            .ipBucket(0)
            .build();
    }
}
