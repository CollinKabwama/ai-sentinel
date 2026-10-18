package dev.aisentinel.core.contract;

import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.model.FeatureSchema;
import dev.aisentinel.core.model.FeatureSnapshot;
import dev.aisentinel.core.policy.EnforcementAction;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationEventJsonTest {

    private static final Set<String> FORBIDDEN_COMPONENT_NAMES = Set.of(
        "expectedClass",
        "groundTruth",
        "labels",
        "anomalyExpected",
        "scenarioId",
        "corpusId",
        "resultId"
    );

    @Test
    void roundTripPreservesDetectorFacingFields() {
        EvaluationEvent original = sampleEvent();
        String json = EvaluationEventJson.write(original);
        EvaluationEvent parsed = EvaluationEventJson.parse(json);

        assertThat(parsed.eventId()).isEqualTo(original.eventId());
        assertThat(parsed.correlationId()).isEqualTo(original.correlationId());
        assertThat(parsed.featureSchemaVersion()).isEqualTo(FeatureSchema.VERSION_ID);
        assertThat(parsed.features()).isEqualTo(original.features());
        assertThat(parsed.anomalyScore()).isEqualTo(0.42);
        assertThat(parsed.action()).isEqualTo(EnforcementAction.MONITOR);
        assertThat(parsed.evaluationStatuses()).containsExactly(EvaluationStatus.COMPLETE);
        assertThat(json).doesNotContain("expectedClass");
        assertThat(json).doesNotContain("scenarioId");
        assertThat(json).doesNotContain("corpusId");
    }

    @Test
    void roundTripPreservesEscapedCharactersInStringFields() {
        // Quotes, backslashes, control characters, and non-ASCII must survive write -> parse
        // unchanged. A regex-based codec that captures string content with a bare "[^\"]*" group
        // truncates at the first *escaped* quote instead of the real closing quote; this proves
        // that defect is fixed rather than merely not-yet-triggered by simple fixture values.
        String tricky = "quote\" backslash\\ newline\n tab\t emoji😀 done";
        EvaluationEvent original = new EvaluationEvent(
            EvaluationEventSchemas.CURRENT_VERSION,
            "evt-escape-001",
            Instant.parse("2026-09-06T10:00:00Z"),
            tricky,
            tricky,
            "UNKNOWN",
            "route:/api/orders",
            FeatureSchema.VERSION_ID,
            new FeatureSnapshot(1.0, 0.1, 0.1, -1.0, 0, 0L, 1L, 1),
            "composite",
            "1",
            0.5,
            0.5,
            EnforcementAction.MONITOR,
            List.of(EvaluationStatus.COMPLETE),
            List.of(new dev.aisentinel.core.contract.ContractRiskFactor(
                "code", "category", "severity", 0.1, 0.2, "ref", tricky, "source")),
            "threshold",
            "1",
            "MONITOR"
        );

        String json = EvaluationEventJson.write(original);
        EvaluationEvent parsed = EvaluationEventJson.parse(json);

        assertThat(parsed.correlationId()).isEqualTo(tricky);
        assertThat(parsed.identityKey()).isEqualTo(tricky);
        assertThat(parsed.riskFactors()).hasSize(1);
        assertThat(parsed.riskFactors().getFirst().explanation()).isEqualTo(tricky);
    }

    @Test
    void parseRejectsMalformedJsonCleanly() {
        assertThatThrownBy(() -> EvaluationEventJson.parse("not json at all"))
            .isInstanceOf(EvaluationContractException.class);
        assertThatThrownBy(() -> EvaluationEventJson.parse("{\"eventSchemaVersion\":\"1\""))
            .isInstanceOf(EvaluationContractException.class);
    }

    @Test
    void parseUsesFirstOccurrenceForDuplicateKeys() {
        // Documented, deliberate behavior for this internal regex-based codec (not general-purpose
        // JSON): the first match wins. The canonical writer never emits duplicate keys, so this is
        // a known limitation of accepting foreign/hand-edited input, not a defect in normal use.
        String json = EvaluationEventJson.write(sampleEvent());
        String duplicated = json.replaceFirst(
            "\"eventId\":\"evt-json-001\"", "\"eventId\":\"first\",\"eventId\":\"second\"");
        EvaluationEvent parsed = EvaluationEventJson.parse(duplicated);
        assertThat(parsed.eventId()).isEqualTo("first");
    }

    @Test
    void parseRejectsGroundTruthOnDetectorFacingEnvelope() {
        String json = EvaluationEventJson.write(sampleEvent());
        String polluted = json.substring(0, json.length() - 1) + ",\"expectedClass\":\"anomalous\"}";
        assertThatThrownBy(() -> EvaluationEventJson.parse(polluted))
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("expectedClass");
    }

    @Test
    void parseRejectsKitRunIdentityOnDetectorFacingEnvelope() {
        String json = EvaluationEventJson.write(sampleEvent());
        String polluted = json.substring(0, json.length() - 1) + ",\"corpusId\":\"corpus.example\"}";
        assertThatThrownBy(() -> EvaluationEventJson.parse(polluted))
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("corpusId");
    }

    @Test
    void parseRejectsUnknownFeatureSchemaVersion() {
        String json = EvaluationEventJson.write(sampleEvent())
            .replace("\"featureSchemaVersion\":\"1\"", "\"featureSchemaVersion\":\"99\"");
        assertThatThrownBy(() -> EvaluationEventJson.parse(json))
            .isInstanceOf(EvaluationContractException.class);
    }

    @Test
    void parseRejectsUnknownEventSchemaVersion() {
        String json = EvaluationEventJson.write(sampleEvent())
            .replace("\"eventSchemaVersion\":\"1\"", "\"eventSchemaVersion\":\"99\"");
        assertThatThrownBy(() -> EvaluationEventJson.parse(json))
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("Unsupported evaluation event schema version");
    }

    @Test
    void recordComponentsDoNotIncludeGroundTruthOrKitRunIdentity() {
        List<String> names = Arrays.stream(EvaluationEvent.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
        assertThat(names).doesNotContainAnyElementsOf(FORBIDDEN_COMPONENT_NAMES);
    }

    private static EvaluationEvent sampleEvent() {
        return new EvaluationEvent(
            EvaluationEventSchemas.CURRENT_VERSION,
            "evt-json-001",
            Instant.parse("2026-09-06T10:00:00Z"),
            "corr-001",
            "id:example",
            "UNKNOWN",
            "route:/api/orders",
            FeatureSchema.VERSION_ID,
            new FeatureSnapshot(12.0, 1.32, 0.75, 42.0, 3, 512L, 123456789L, 17),
            "composite",
            "1",
            0.42,
            0.42,
            EnforcementAction.MONITOR,
            List.of(EvaluationStatus.COMPLETE),
            List.of(),
            "threshold",
            "1",
            "MONITOR"
        );
    }
}
