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
import dev.aisentinel.core.identity.IdentityRiskSignalKeys;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Additional hostile and positive coverage for the platform-neutral contract.
 */
class EvaluationContractHostilePositiveTest {

    @Test
    void anonymousIdentityAccepted() {
        EvaluationRequest request = EvaluationRequest.builder()
            .correlationId("anon-1")
            .identityKey("")
            .identityType("ANONYMOUS")
            .path("/public")
            .method("GET")
            .build();
        assertThat(request.identityKey()).isEmpty();
        assertThat(request.identityType()).isEqualTo("ANONYMOUS");
    }

    @Test
    void tenantScopedRequestAccepted() {
        EvaluationRequest request = EvaluationRequest.builder()
            .correlationId("t-1")
            .identityKey("hash-t")
            .tenantId("tenant-a")
            .path("/api/t")
            .build();
        assertThat(request.tenantId()).isEqualTo("tenant-a");
    }

    @Test
    void trustBearingRequestAccepted() {
        EvaluationRequest request = EvaluationRequest.builder()
            .correlationId("trust-1")
            .identityKey("hash-trust")
            .path("/api")
            .trustSignals(Map.of(IdentityRiskSignalKeys.NEW_SESSION, 0.4))
            .build();
        assertThat(request.trustSignals()).containsEntry(IdentityRiskSignalKeys.NEW_SESSION, 0.4);
    }

    @Test
    void wrongContractVersionRejected() {
        assertThatThrownBy(() -> EvaluationRequest.builder()
            .contractVersion(99)
            .correlationId("c")
            .identityKey("id")
            .path("/api")
            .build())
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("contractVersion");
    }

    @Test
    void factorOrderingIsDeterministicFromExplanationOrder() {
        RiskFactor a = factor(RiskFactorCode.BEHAVIOR_DEVIATION, "a");
        RiskFactor b = factor(RiskFactorCode.PIPELINE_DEGRADED, "b");
        RiskDecision decision = new RiskDecision(
            EnforcementAction.ALLOW, 0.2, 0.2, features(), new RequestContext(), false,
            Set.of(), new RiskExplanation(List.of(a, b), null));
        EvaluationResponse response = EvaluationContractMapper.toResponse(
            EvaluationRequest.builder().correlationId("c").identityKey("id").path("/api").build(),
            decision, true);
        assertThat(response.factors()).extracting(ContractRiskFactor::code)
            .containsExactly(RiskFactorCode.BEHAVIOR_DEVIATION.name(), RiskFactorCode.PIPELINE_DEGRADED.name());
    }

    @Test
    void emptyFactorsAndAdviceSemantics() {
        RiskDecision decision = new RiskDecision(
            EnforcementAction.ALLOW, 0.05, 0.05, features(), new RequestContext(), false,
            Set.of(), RiskExplanation.empty());
        EvaluationResponse response = EvaluationContractMapper.toResponse(
            EvaluationRequest.builder().correlationId("c").identityKey("id").path("/api").build(),
            decision, true);
        assertThat(response.factors()).isEmpty();
        assertThat(response.advice()).isNull();
    }

    @Test
    void advicePresentButDoesNotImplyActionChange() {
        RiskFactor factor = factor(RiskFactorCode.BEHAVIOR_DEVIATION, "feat");
        SecurityAdvice advice = new SecurityAdvice(
            AdvisoryCode.OTHER_OPERATOR_REVIEW, AdvisoryPriority.HIGH, "review",
            List.of(RiskFactorCode.BEHAVIOR_DEVIATION), true);
        RiskDecision decision = new RiskDecision(
            EnforcementAction.ALLOW, 0.1, 0.1, features(), new RequestContext(), false,
            Set.of(), new RiskExplanation(List.of(factor), advice));
        EvaluationResponse response = EvaluationContractMapper.toResponse(
            EvaluationRequest.builder().correlationId("c").identityKey("id").path("/api").build(),
            decision, true);
        assertThat(response.advice().code()).isEqualTo("OTHER_OPERATOR_REVIEW");
        assertThat(response.action()).isEqualTo(EnforcementAction.ALLOW);
    }

    @Test
    void statusesSortedByName() {
        RiskDecision decision = new RiskDecision(
            EnforcementAction.ALLOW, Double.NaN, Double.NaN, features(), new RequestContext(), false,
            Set.of(EvaluationStatus.DEGRADED, EvaluationStatus.INVALID_SCORE),
            RiskExplanation.empty());
        EvaluationResponse response = EvaluationContractMapper.toResponse(
            EvaluationRequest.builder().correlationId("c").identityKey("id").path("/api").build(),
            decision, true);
        assertThat(response.evaluationStatuses())
            .containsExactly("DEGRADED", "INVALID_SCORE");
        assertThat(response.anomalyScore()).isNull();
    }

    @Test
    void authorizationHeaderNotRequiredForValidRequest() {
        EvaluationRequest request = EvaluationRequest.builder()
            .correlationId("no-auth")
            .identityKey("id")
            .path("/api")
            .headers(Map.of("content-type", "application/json"))
            .build();
        assertThat(request.headers()).doesNotContainKey("authorization");
        assertThat(request.headers()).doesNotContainKey("cookie");
    }

    private static RiskFactor factor(RiskFactorCode code, String evidence) {
        return new RiskFactor(
            code, RiskFactorCategory.BEHAVIOR, RiskFactorSeverity.MEDIUM,
            0.5, 0.5, evidence, "text", "source");
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
