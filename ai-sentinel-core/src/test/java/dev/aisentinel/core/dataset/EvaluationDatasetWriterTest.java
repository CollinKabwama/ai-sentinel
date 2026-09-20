package dev.aisentinel.core.dataset;

import dev.aisentinel.core.contract.ContractRiskFactor;
import dev.aisentinel.core.contract.EvaluationContractException;
import dev.aisentinel.core.contract.EvaluationEvent;
import dev.aisentinel.core.contract.EvaluationEventSchemas;
import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.model.FeatureSchema;
import dev.aisentinel.core.model.FeatureSnapshot;
import dev.aisentinel.core.policy.EnforcementAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationDatasetWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesDeterministicEventsJsonlAndManifestWithChecksum() throws Exception {
        Path datasetDir = tempDir.resolve("dataset-a");
        try (EvaluationDatasetWriter writer = new EvaluationDatasetWriter(
            datasetDir,
            "dataset-001",
            "0.3.0",
            FeatureSchema.VERSION_ID,
            EvaluationEventSchemas.CURRENT_VERSION,
            EvaluationDatasetSchemas.SOURCE_CLASSIFICATION_EVALUATION_EXPORT,
            "1",
            "Synthetic evaluation export fixture",
            "unit-test"
        )) {
            writer.append(event("evt-001", "id:pseudo-001", "route:/api/orders"));
            writer.append(event("evt-002", "id:pseudo-001", "route:/api/orders"));
        }

        String events = Files.readString(datasetDir.resolve("events.jsonl"));
        String manifest = Files.readString(datasetDir.resolve("manifest.json"));

        assertThat(events)
            .contains("\"eventId\":\"evt-001\"")
            .contains("\"eventId\":\"evt-002\"")
            .doesNotContain("Authorization")
            .doesNotContain("Bearer ")
            .doesNotContain("Cookie")
            .doesNotContain("password");
        assertThat(events.lines()).hasSize(2);
        assertThat(events).doesNotContain("\r\n");
        assertThat(events.indexOf("\"eventId\":\"evt-001\""))
            .isLessThan(events.indexOf("\"eventId\":\"evt-002\""));
        assertThat(manifest)
            .contains("\"datasetSchemaVersion\":\"1\"")
            .contains("\"recordCount\":2")
            .contains("\"ordering\":\"append-order\"")
            .contains("\"eventsFile\":\"events.jsonl\"");

        String expectedSha = EvaluationDatasetJson.sha256Hex(Files.readAllBytes(datasetDir.resolve("events.jsonl")));
        assertThat(manifest).contains("\"eventsSha256\":\"" + expectedSha + "\"");
    }

    @Test
    void emptyDatasetIsValidAndProducesCompletedArtifacts() throws Exception {
        Path datasetDir = tempDir.resolve("dataset-empty");
        try (EvaluationDatasetWriter ignored = new EvaluationDatasetWriter(
            datasetDir,
            "dataset-empty",
            "0.3.0",
            FeatureSchema.VERSION_ID,
            EvaluationEventSchemas.CURRENT_VERSION,
            EvaluationDatasetSchemas.SOURCE_CLASSIFICATION_EVALUATION_EXPORT,
            "1",
            "Empty dataset",
            ""
        )) {
            // no events
        }

        assertThat(Files.readString(datasetDir.resolve("events.jsonl"))).isEmpty();
        assertThat(Files.readString(datasetDir.resolve("manifest.json"))).contains("\"recordCount\":0");
    }

    @Test
    void mismatchedSchemaVersionIsRejected() throws Exception {
        Path datasetDir = tempDir.resolve("dataset-b");
        try (EvaluationDatasetWriter writer = new EvaluationDatasetWriter(
            datasetDir,
            "dataset-002",
            "0.3.0",
            FeatureSchema.VERSION_ID,
            EvaluationEventSchemas.CURRENT_VERSION,
            EvaluationDatasetSchemas.SOURCE_CLASSIFICATION_EVALUATION_EXPORT,
            "1",
            "",
            ""
        )) {
            EvaluationEvent mismatched = new EvaluationEvent(
                EvaluationEventSchemas.CURRENT_VERSION,
                "evt-003",
                Instant.parse("2026-09-06T18:00:00Z"),
                "corr-003",
                "id:pseudo-003",
                "UNKNOWN",
                "route:/api/orders",
                FeatureSchema.VERSION_ID,
                new FeatureSnapshot(1.0, 0.2, 0.1, -1.0, 1, 32L, 11L, 7),
                "composite",
                "",
                null,
                null,
                EnforcementAction.ALLOW,
                List.of(EvaluationStatus.INVALID_SCORE),
                List.of(),
                "",
                "",
                ""
            );
            assertThatThrownBy(() -> writer.append(new EvaluationEvent(
                "2",
                mismatched.eventId(),
                mismatched.observedAt(),
                mismatched.correlationId(),
                mismatched.identityKey(),
                mismatched.identityType(),
                mismatched.endpointKey(),
                mismatched.featureSchemaVersion(),
                mismatched.features(),
                mismatched.scorerId(),
                mismatched.scorerVersion(),
                mismatched.anomalyScore(),
                mismatched.policyScore(),
                mismatched.action(),
                mismatched.evaluationStatuses(),
                mismatched.riskFactors(),
                mismatched.policyId(),
                mismatched.policyVersion(),
                mismatched.evaluationMode()
            )))
                .isInstanceOf(EvaluationContractException.class);
        }
    }

    @Test
    void partialExportDoesNotMasqueradeAsCompletedDataset() throws Exception {
        Path datasetDir = tempDir.resolve("dataset-fail");
        EvaluationDatasetWriter writer = new EvaluationDatasetWriter(
            datasetDir,
            "dataset-003",
            "0.3.0",
            FeatureSchema.VERSION_ID,
            EvaluationEventSchemas.CURRENT_VERSION,
            EvaluationDatasetSchemas.SOURCE_CLASSIFICATION_EVALUATION_EXPORT,
            "1",
            "",
            ""
        );
        writer.append(event("evt-001", "id:pseudo-001", "route:/api/orders"));

        assertThat(datasetDir.resolve("manifest.json")).doesNotExist();
        assertThat(datasetDir.resolve("events.jsonl")).doesNotExist();
        writer.close();
    }

    @Test
    void existingFinalizedArtifactsAreNotOverwritten() throws Exception {
        Path datasetDir = tempDir.resolve("dataset-existing");
        Files.createDirectories(datasetDir);
        Files.writeString(datasetDir.resolve("manifest.json"), "{}");

        assertThatThrownBy(() -> new EvaluationDatasetWriter(
            datasetDir,
            "dataset-existing",
            "0.3.0",
            FeatureSchema.VERSION_ID,
            EvaluationEventSchemas.CURRENT_VERSION,
            EvaluationDatasetSchemas.SOURCE_CLASSIFICATION_EVALUATION_EXPORT,
            "1",
            "",
            ""
        ))
            .isInstanceOf(FileAlreadyExistsException.class);
    }

    @Test
    void exportRecordRejectsRawUrlLikeEndpointKey() {
        assertThatThrownBy(() -> event("evt-004", "id:pseudo-004", "route:/api/orders?apiKey=secret"))
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("endpointKey");
    }

    @Test
    void riskFactorsAndStatusesSerializeDeterministically() throws Exception {
        Path datasetDir = tempDir.resolve("dataset-c");
        EvaluationEvent event = new EvaluationEvent(
            EvaluationEventSchemas.CURRENT_VERSION,
            "evt-005",
            Instant.parse("2026-09-06T18:00:00Z"),
            "corr-005",
            "id:pseudo-005",
            "UNKNOWN",
            "route:/api/orders",
            FeatureSchema.VERSION_ID,
            new FeatureSnapshot(3.0, 1.2, 0.5, 9.0, 2, 64L, 44L, 12),
            "composite",
            "",
            0.4,
            0.4,
            EnforcementAction.MONITOR,
            List.of(EvaluationStatus.INVALID_SCORE, EvaluationStatus.DEGRADED, EvaluationStatus.INVALID_SCORE),
            List.of(new ContractRiskFactor("A", "SYSTEM", "LOW", 0.1, 0.9, "", "a", "status")),
            "",
            "",
            ""
        );

        try (EvaluationDatasetWriter writer = new EvaluationDatasetWriter(
            datasetDir,
            "dataset-004",
            "0.3.0",
            FeatureSchema.VERSION_ID,
            EvaluationEventSchemas.CURRENT_VERSION,
            EvaluationDatasetSchemas.SOURCE_CLASSIFICATION_EVALUATION_EXPORT,
            "1",
            "",
            ""
        )) {
            writer.append(event);
        }

        String events = Files.readString(datasetDir.resolve("events.jsonl"));
        assertThat(events).contains("\"evaluationStatuses\":[\"DEGRADED\",\"INVALID_SCORE\"]");
        assertThat(events).contains("\"riskFactors\":[{\"code\":\"A\"");
    }

    private static EvaluationEvent event(String eventId, String identityKey, String endpointKey) {
        return new EvaluationEvent(
            EvaluationEventSchemas.CURRENT_VERSION,
            eventId,
            Instant.parse("2026-09-06T18:00:00Z"),
            "corr-" + eventId,
            identityKey,
            "UNKNOWN",
            endpointKey,
            FeatureSchema.VERSION_ID,
            new FeatureSnapshot(1.0, 0.2, 0.1, -1.0, 1, 32L, 11L, 7),
            "composite",
            "",
            null,
            null,
            EnforcementAction.ALLOW,
            List.of(EvaluationStatus.COMPLETE),
            List.of(),
            "",
            "",
            ""
        );
    }
}
