package dev.aisentinel.core.contract;

import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.model.FeatureSchema;
import dev.aisentinel.core.model.FeatureSnapshot;
import dev.aisentinel.core.policy.EnforcementAction;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationEventContractTest {

    @Test
    void validEventConstructionPreservesIndependentVersionsAndDeterministicCollections() {
        EvaluationEvent event = eventBuilder()
            .evaluationStatuses(List.of(EvaluationStatus.DEGRADED, EvaluationStatus.INVALID_SCORE, EvaluationStatus.DEGRADED))
            .riskFactors(List.of(new ContractRiskFactor(
                "INVALID_SCORE", "SYSTEM", "MEDIUM", 1.0, 1.0, "INVALID_SCORE", "invalid score", "status"
            )))
            .build();

        assertThat(event.eventSchemaVersion()).isEqualTo("1");
        assertThat(event.featureSchemaVersion()).isEqualTo(FeatureSchema.VERSION_ID);
        assertThat(event.evaluationStatuses())
            .containsExactly(EvaluationStatus.DEGRADED, EvaluationStatus.INVALID_SCORE);
        assertThat(event.riskFactors()).hasSize(1);
    }

    @Test
    void unknownEventSchemaVersionIsRejected() {
        assertThatThrownBy(() -> eventBuilder().eventSchemaVersion("2").build())
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("Unsupported evaluation event schema version");
    }

    @Test
    void unknownFeatureSchemaVersionIsRejectedEvenWhenEventVersionIsSupported() {
        assertThatThrownBy(() -> eventBuilder().featureSchemaVersion("2").build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported feature schema version");
    }

    @Test
    void scoresMustBeFiniteOrAbsent() {
        assertThatThrownBy(() -> eventBuilder().anomalyScore(Double.NaN).build())
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("anomalyScore");
        assertThatThrownBy(() -> eventBuilder().policyScore(Double.POSITIVE_INFINITY).build())
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("policyScore");
        assertThatThrownBy(() -> eventBuilder().anomalyScore(Double.NEGATIVE_INFINITY).build())
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("anomalyScore");
        assertThatThrownBy(() -> eventBuilder().anomalyScore(-0.01).build())
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("anomalyScore");
        assertThatThrownBy(() -> eventBuilder().policyScore(1.01).build())
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("policyScore");

        EvaluationEvent absentScores = eventBuilder().anomalyScore(null).policyScore(null).build();
        assertThat(absentScores.anomalyScore()).isNull();
        assertThat(absentScores.policyScore()).isNull();
    }

    @Test
    void requiredIdentifiersMustBePresent() {
        assertThatThrownBy(() -> eventBuilder().eventSchemaVersion(" ").build())
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("eventSchemaVersion");
        assertThatThrownBy(() -> eventBuilder().eventId("").build())
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("eventId");
        assertThatThrownBy(() -> eventBuilder().identityKey(null).build())
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("identityKey");
        assertThatThrownBy(() -> eventBuilder().endpointKey(" ").build())
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("endpointKey");
        assertThatThrownBy(() -> eventBuilder().featureSchemaVersion("").build())
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("featureSchemaVersion");
        assertThatThrownBy(() -> eventBuilder().scorerId(" ").build())
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("scorerId");
    }

    @Test
    void immutableCollectionsAreDefensivelyCopied() {
        List<EvaluationStatus> statuses = new ArrayList<>();
        statuses.add(EvaluationStatus.COMPLETE);
        List<ContractRiskFactor> factors = new ArrayList<>();
        factors.add(new ContractRiskFactor("COMPLETE", "SYSTEM", "LOW", 0.1, 1.0, "", "", "status"));

        EvaluationEvent event = eventBuilder().evaluationStatuses(statuses).riskFactors(factors).build();
        statuses.add(EvaluationStatus.DEGRADED);
        factors.clear();

        assertThat(event.evaluationStatuses()).containsExactly(EvaluationStatus.COMPLETE);
        assertThat(event.riskFactors()).hasSize(1);
        assertThatThrownBy(() -> event.evaluationStatuses().add(EvaluationStatus.DEGRADED))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> event.riskFactors().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void privacySafeShapeAvoidsSensitiveEscapeHatches() {
        assertThat(EvaluationEvent.class.isRecord()).isTrue();
        assertThat(List.of(EvaluationEvent.class.getRecordComponents()).stream()
            .map(RecordComponent::getName))
            .doesNotContain(
                "headers",
                "authorization",
                "cookie",
                "cookies",
                "context",
                "attributes",
                "extensions",
                "password",
                "requestBody",
                "rawRequest",
                "request",
                "rawHeaders",
                "metadata"
            );
    }

    private static Builder eventBuilder() {
        return new Builder();
    }

    private static final class Builder {
        private String eventSchemaVersion = EvaluationEventSchemas.CURRENT_VERSION;
        private String eventId = "evt-001";
        private Instant observedAt = Instant.parse("2026-09-06T10:00:00Z");
        private String correlationId = "corr-001";
        private String identityKey = "id:example-001";
        private String identityType = "UNKNOWN";
        private String endpointKey = "route:/api/orders";
        private String featureSchemaVersion = FeatureSchema.VERSION_ID;
        private FeatureSnapshot features = new FeatureSnapshot(12.0, 1.3, 0.75, 42.0, 3, 512L, 123456789L, 17);
        private String scorerId = "composite";
        private String scorerVersion = "";
        private Double anomalyScore = 0.42;
        private Double policyScore = 0.42;
        private EnforcementAction action = EnforcementAction.MONITOR;
        private List<EvaluationStatus> evaluationStatuses = List.of(EvaluationStatus.COMPLETE);
        private List<ContractRiskFactor> riskFactors = List.of();
        private String policyId = "threshold";
        private String policyVersion = "";
        private String evaluationMode = "ENFORCE";

        private Builder eventSchemaVersion(String value) {
            this.eventSchemaVersion = value;
            return this;
        }

        private Builder eventId(String value) {
            this.eventId = value;
            return this;
        }

        private Builder identityKey(String value) {
            this.identityKey = value;
            return this;
        }

        private Builder endpointKey(String value) {
            this.endpointKey = value;
            return this;
        }

        private Builder featureSchemaVersion(String value) {
            this.featureSchemaVersion = value;
            return this;
        }

        private Builder scorerId(String value) {
            this.scorerId = value;
            return this;
        }

        private Builder anomalyScore(Double value) {
            this.anomalyScore = value;
            return this;
        }

        private Builder policyScore(Double value) {
            this.policyScore = value;
            return this;
        }

        private Builder evaluationStatuses(List<EvaluationStatus> value) {
            this.evaluationStatuses = value;
            return this;
        }

        private Builder riskFactors(List<ContractRiskFactor> value) {
            this.riskFactors = value;
            return this;
        }

        private EvaluationEvent build() {
            return new EvaluationEvent(
                eventSchemaVersion,
                eventId,
                observedAt,
                correlationId,
                identityKey,
                identityType,
                endpointKey,
                featureSchemaVersion,
                features,
                scorerId,
                scorerVersion,
                anomalyScore,
                policyScore,
                action,
                evaluationStatuses,
                riskFactors,
                policyId,
                policyVersion,
                evaluationMode
            );
        }
    }
}
