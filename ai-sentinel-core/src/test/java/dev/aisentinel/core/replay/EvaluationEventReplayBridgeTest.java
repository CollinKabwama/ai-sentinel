package dev.aisentinel.core.replay;

import dev.aisentinel.core.contract.EvaluationEvent;
import dev.aisentinel.core.contract.EvaluationEventJson;
import dev.aisentinel.core.contract.EvaluationEventSchemas;
import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.model.FeatureSchema;
import dev.aisentinel.core.model.FeatureSnapshot;
import dev.aisentinel.core.policy.EnforcementAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationEventReplayBridgeTest {

    @TempDir
    Path tempDir;

    @Test
    void bridgeRoundTripPreservesEventIdentityAndFeatures() {
        EvaluationEvent original = sampleEvent("evt-bridge-001");
        ReplayDataset.ReplaySourceEvent projected =
            EvaluationEventReplayBridge.toReplaySourceEvent(original, 1);
        EvaluationEvent restored = EvaluationEventReplayBridge.fromReplaySourceEvent(projected);

        assertThat(projected.replayInput().eventId()).isEqualTo("evt-bridge-001");
        assertThat(projected.replayInput().sequenceNumber()).isEqualTo(1);
        assertThat(projected.historicalOutput().action()).isEqualTo(EnforcementAction.ALLOW);
        assertThat(restored.eventId()).isEqualTo(original.eventId());
        assertThat(restored.features()).isEqualTo(original.features());
        assertThat(restored.anomalyScore()).isEqualTo(original.anomalyScore());
        assertThat(restored.evaluationStatuses()).isEqualTo(original.evaluationStatuses());
    }

    @Test
    void datasetLoaderParsesThroughEvaluationEventSemantics() throws Exception {
        EvaluationEvent event = sampleEvent("evt-load-001");
        Path datasetDir = writeMinimalDataset(List.of(event));

        ReplayDataset loaded = new ReplayDatasetLoader().load(datasetDir);
        assertThat(loaded.events()).hasSize(1);
        EvaluationEvent restored =
            EvaluationEventReplayBridge.fromReplaySourceEvent(loaded.events().getFirst());
        assertThat(restored.eventId()).isEqualTo("evt-load-001");
        assertThat(restored.featureSchemaVersion()).isEqualTo(FeatureSchema.VERSION_ID);
        assertThat(restored.action()).isEqualTo(EnforcementAction.ALLOW);
    }

    @Test
    void datasetLoaderRejectsGroundTruthLeakageInEventsJsonl() throws Exception {
        EvaluationEvent event = sampleEvent("evt-gt-001");
        String line = EvaluationEventJson.write(event);
        String polluted = line.substring(0, line.length() - 1) + ",\"expectedClass\":\"anomalous\"}";
        Path datasetDir = writeMinimalDatasetFromLines(List.of(polluted));

        assertThatThrownBy(() -> new ReplayDatasetLoader().load(datasetDir))
            .isInstanceOf(ReplayException.class)
            .hasMessageContaining("expectedClass");
    }

    private Path writeMinimalDataset(List<EvaluationEvent> events) throws Exception {
        return writeMinimalDatasetFromLines(events.stream().map(EvaluationEventJson::write).toList());
    }

    private Path writeMinimalDatasetFromLines(List<String> eventLines) throws Exception {
        Path dir = tempDir.resolve("dataset-" + System.nanoTime());
        Files.createDirectories(dir);
        StringBuilder eventsBody = new StringBuilder();
        for (String line : eventLines) {
            eventsBody.append(line).append('\n');
        }
        byte[] eventBytes = eventsBody.toString().getBytes(StandardCharsets.UTF_8);
        Files.write(dir.resolve("events.jsonl"), eventBytes);
        String sha = dev.aisentinel.distributed.training.TrainingFingerprintHashes.sha256HexBytes(eventBytes);
        String manifest = "{"
            + "\"datasetSchemaVersion\":\"1\","
            + "\"datasetId\":\"ds-bridge-test\","
            + "\"createdAt\":\"2026-09-06T10:00:00Z\","
            + "\"aiSentinelVersion\":\"0.4.0\","
            + "\"featureSchemaVersion\":\"1\","
            + "\"evaluationEventSchemaVersion\":\"" + EvaluationEventSchemas.CURRENT_VERSION + "\","
            + "\"recordCount\":" + eventLines.size() + ","
            + "\"ordering\":\"append-order\","
            + "\"sourceClassification\":\"SYNTHETIC\","
            + "\"transformationVersion\":\"1\","
            + "\"eventsFile\":\"events.jsonl\","
            + "\"eventsSha256\":\"" + sha + "\""
            + "}";
        Files.writeString(dir.resolve("manifest.json"), manifest, StandardCharsets.UTF_8);
        return dir;
    }

    private static EvaluationEvent sampleEvent(String eventId) {
        return new EvaluationEvent(
            EvaluationEventSchemas.CURRENT_VERSION,
            eventId,
            Instant.parse("2026-09-06T10:00:00Z"),
            "corr-bridge",
            "id:bridge",
            "UNKNOWN",
            "route:/api/bridge",
            FeatureSchema.VERSION_ID,
            new FeatureSnapshot(1.0, 0.5, 0.5, -1.0, 0, 0L, 1L, 1),
            "statistical",
            "1",
            0.1,
            0.1,
            EnforcementAction.ALLOW,
            List.of(EvaluationStatus.COMPLETE),
            List.of(),
            "threshold",
            "1",
            "MONITOR"
        );
    }
}
