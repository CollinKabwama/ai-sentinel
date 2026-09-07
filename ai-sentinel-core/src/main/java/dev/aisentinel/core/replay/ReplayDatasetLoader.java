package dev.aisentinel.core.replay;

import dev.aisentinel.core.contract.EvaluationEventSchemas;
import dev.aisentinel.core.dataset.EvaluationDatasetManifest;
import dev.aisentinel.core.dataset.EvaluationDatasetSchemas;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetAnnotations;
import dev.aisentinel.core.model.FeatureSchema;
import dev.aisentinel.core.model.FeatureSnapshot;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.distributed.training.TrainingFingerprintHashes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Typed loader for compatible evaluation datasets used by deterministic replay.
 */
public final class ReplayDatasetLoader {

    public ReplayDataset load(Path datasetDirectory) throws IOException {
        return load(datasetDirectory, null);
    }

    public ReplayDataset load(Path datasetDirectory, Path annotationsPath) throws IOException {
        Path manifestPath = datasetDirectory.resolve(EvaluationDatasetSchemas.MANIFEST_FILE_NAME);
        Path eventsPath = datasetDirectory.resolve(EvaluationDatasetSchemas.EVENTS_FILE_NAME);
        requireExists(manifestPath);
        requireExists(eventsPath);

        String manifestText = Files.readString(manifestPath, StandardCharsets.UTF_8);
        byte[] eventBytes = Files.readAllBytes(eventsPath);
        String eventsText = new String(eventBytes, StandardCharsets.UTF_8);
        String eventsSha256 = TrainingFingerprintHashes.sha256HexBytes(eventBytes);
        EvaluationDatasetManifest manifest = parseManifest(manifestText);
        if (!manifest.eventsSha256().equals(eventsSha256)) {
            throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Dataset checksum mismatch");
        }
        if (manifest.recordCount() <= 0) {
            throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Dataset recordCount must be > 0");
        }
        if (!EvaluationDatasetSchemas.ORDERING_APPEND_ORDER.equals(manifest.ordering())) {
            throw new ReplayException(ReplayFailureKind.UNSUPPORTED_SCHEMA, "Unsupported dataset ordering");
        }
        if (!EvaluationDatasetSchemas.EVENTS_FILE_NAME.equals(manifest.eventsFile())) {
            throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Unsupported events file name");
        }

        List<ReplayDataset.ReplaySourceEvent> events = parseEvents(eventsText);
        if (events.size() != manifest.recordCount()) {
            throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Manifest recordCount mismatch");
        }
        requireUniqueEventIds(events);

        ReplayDataset.AnnotationMetadata annotations = ReplayDataset.AnnotationMetadata.absent(manifest.datasetId());
        if (annotationsPath != null) {
            requireExists(annotationsPath);
            annotations = parseAnnotations(Files.readString(annotationsPath, StandardCharsets.UTF_8), manifest.datasetId());
        }
        return new ReplayDataset(manifest, eventsSha256, events, annotations);
    }

    private static void requireExists(Path path) {
        if (!Files.exists(path)) {
            throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Missing dataset artifact: " + path);
        }
    }

    private static EvaluationDatasetManifest parseManifest(String json) {
        try {
            return new EvaluationDatasetManifest(
                ReplayJsonSupport.requireString(json, "datasetSchemaVersion"),
                ReplayJsonSupport.requireString(json, "datasetId"),
                Instant.parse(ReplayJsonSupport.requireString(json, "createdAt")),
                ReplayJsonSupport.requireString(json, "aiSentinelVersion"),
                ReplayJsonSupport.requireString(json, "featureSchemaVersion"),
                ReplayJsonSupport.requireString(json, "evaluationEventSchemaVersion"),
                ReplayJsonSupport.requireLong(json, "recordCount"),
                ReplayJsonSupport.requireString(json, "ordering"),
                ReplayJsonSupport.requireString(json, "sourceClassification"),
                ReplayJsonSupport.requireString(json, "transformationVersion"),
                ReplayJsonSupport.requireString(json, "eventsFile"),
                ReplayJsonSupport.requireString(json, "eventsSha256"),
                ReplayJsonSupport.optionalString(json, "description"),
                ReplayJsonSupport.optionalString(json, "scenario")
            );
        } catch (RuntimeException e) {
            throw new ReplayException(ReplayFailureKind.INPUT_PARSE_FAILURE, "Failed to parse dataset manifest", e);
        }
    }

    private static List<ReplayDataset.ReplaySourceEvent> parseEvents(String eventsText) {
        List<ReplayDataset.ReplaySourceEvent> events = new ArrayList<>();
        String[] lines = eventsText.split("\n", -1);
        int sequence = 0;
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            sequence++;
            events.add(parseEvent(line, sequence));
        }
        return List.copyOf(events);
    }

    private static ReplayDataset.ReplaySourceEvent parseEvent(String line, int sequence) {
        try {
            String eventSchemaVersion = ReplayJsonSupport.requireString(line, "eventSchemaVersion");
            EvaluationEventSchemas.requireSupported(eventSchemaVersion);
            String featureSchemaVersion = ReplayJsonSupport.requireString(line, "featureSchemaVersion");
            FeatureSchema.requireSupportedVersion(featureSchemaVersion);
            FeatureSnapshot features = new FeatureSnapshot(
                ReplayJsonSupport.requireDouble(line, "requestsPerWindow"),
                ReplayJsonSupport.requireDouble(line, "endpointEntropy"),
                ReplayJsonSupport.requireDouble(line, "endpointConcentration"),
                ReplayJsonSupport.requireDouble(line, "tokenAgeSeconds"),
                (int) ReplayJsonSupport.requireLong(line, "parameterCount"),
                ReplayJsonSupport.requireLong(line, "payloadSizeBytes"),
                ReplayJsonSupport.requireLong(line, "headerFingerprintHash"),
                (int) ReplayJsonSupport.requireLong(line, "ipBucket")
            );
            ReplayInputRecord input = new ReplayInputRecord(
                sequence,
                ReplayJsonSupport.requireString(line, "eventId"),
                Instant.parse(ReplayJsonSupport.requireString(line, "observedAt")),
                ReplayJsonSupport.optionalString(line, "correlationId"),
                ReplayJsonSupport.requireString(line, "identityKey"),
                ReplayJsonSupport.optionalString(line, "identityType"),
                ReplayJsonSupport.requireString(line, "endpointKey"),
                featureSchemaVersion,
                features
            );
            HistoricalReferenceOutput historicalOutput = new HistoricalReferenceOutput(
                ReplayJsonSupport.requireString(line, "scorerId"),
                ReplayJsonSupport.optionalString(line, "scorerVersion"),
                ReplayJsonSupport.optionalDouble(line, "anomalyScore"),
                ReplayJsonSupport.optionalDouble(line, "policyScore"),
                EnforcementAction.valueOf(ReplayJsonSupport.requireString(line, "action")),
                ReplayJsonSupport.parseStatuses(ReplayJsonSupport.arrayBody(line, "evaluationStatuses")),
                ReplayJsonSupport.parseRiskFactors(line),
                ReplayJsonSupport.optionalString(line, "policyId"),
                ReplayJsonSupport.optionalString(line, "policyVersion"),
                ReplayJsonSupport.optionalString(line, "evaluationMode")
            );
            return new ReplayDataset.ReplaySourceEvent(input, historicalOutput);
        } catch (RuntimeException e) {
            throw new ReplayException(ReplayFailureKind.INPUT_PARSE_FAILURE,
                "Failed to parse dataset event at sequence " + sequence, e);
        }
    }

    private static void requireUniqueEventIds(List<ReplayDataset.ReplaySourceEvent> events) {
        Set<String> seen = new LinkedHashSet<>();
        for (ReplayDataset.ReplaySourceEvent event : events) {
            String eventId = event.replayInput().eventId();
            if (!seen.add(eventId)) {
                throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE,
                    "Duplicate dataset eventId: " + eventId);
            }
        }
    }

    private static ReplayDataset.AnnotationMetadata parseAnnotations(String annotationsText, String datasetId) {
        String schemaVersion = ReplayJsonSupport.requireString(annotationsText, "schemaVersion");
        if (!ReferenceDatasetAnnotations.SCHEMA_VERSION.equals(schemaVersion)) {
            throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Unsupported annotation schema");
        }
        String annotationDatasetId = ReplayJsonSupport.requireString(annotationsText, "datasetId");
        if (!datasetId.equals(annotationDatasetId)) {
            throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Annotation datasetId mismatch");
        }
        int scenarioCount = ReplayJsonSupport.countOccurrences(annotationsText, "\"id\":\"");
        return new ReplayDataset.AnnotationMetadata(schemaVersion, annotationDatasetId, scenarioCount);
    }
}
