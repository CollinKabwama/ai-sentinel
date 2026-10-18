package dev.aisentinel.core.replay;

import dev.aisentinel.core.contract.EvaluationContractException;
import dev.aisentinel.core.contract.EvaluationEvent;
import dev.aisentinel.core.contract.EvaluationEventJson;
import dev.aisentinel.core.contract.EvaluationEventSchemas;
import dev.aisentinel.core.dataset.EvaluationDatasetManifest;
import dev.aisentinel.core.dataset.EvaluationDatasetSchemas;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetAnnotations;
import dev.aisentinel.distributed.training.TrainingFingerprintHashes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
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
        EvaluationDatasetManifest manifest = parseManifest(manifestText);
        ReplayDataset.AnnotationMetadata annotations = ReplayDataset.AnnotationMetadata.absent(manifest.datasetId());
        if (annotationsPath != null) {
            requireExists(annotationsPath);
            annotations = parseAnnotations(Files.readString(annotationsPath, StandardCharsets.UTF_8), manifest.datasetId());
        }
        return fromManifestAndEvents(manifest, eventBytes, annotations);
    }

    /**
     * Builds a {@link ReplayDataset} from an in-memory manifest and events JSONL bytes.
     * Used by evaluator-provided (BYO) datasets that do not ship a separate replay {@code manifest.json}.
     */
    public ReplayDataset fromManifestAndEvents(
        EvaluationDatasetManifest manifest,
        byte[] eventBytes,
        ReplayDataset.AnnotationMetadata annotations
    ) {
        EvaluationDatasetManifest safeManifest = Objects.requireNonNull(manifest, "manifest");
        byte[] safeBytes = Objects.requireNonNull(eventBytes, "eventBytes");
        ReplayDataset.AnnotationMetadata safeAnnotations = annotations == null
            ? ReplayDataset.AnnotationMetadata.absent(safeManifest.datasetId())
            : annotations;

        String eventsSha256 = TrainingFingerprintHashes.sha256HexBytes(safeBytes);
        if (!safeManifest.eventsSha256().equals(eventsSha256)) {
            throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Dataset checksum mismatch");
        }
        if (safeManifest.recordCount() <= 0) {
            throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Dataset recordCount must be > 0");
        }
        if (!EvaluationDatasetSchemas.ORDERING_APPEND_ORDER.equals(safeManifest.ordering())) {
            throw new ReplayException(ReplayFailureKind.UNSUPPORTED_SCHEMA, "Unsupported dataset ordering");
        }
        if (!EvaluationDatasetSchemas.EVENTS_FILE_NAME.equals(safeManifest.eventsFile())) {
            throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Unsupported events file name");
        }

        List<ReplayDataset.ReplaySourceEvent> events =
            parseEvents(new String(safeBytes, StandardCharsets.UTF_8));
        if (events.size() != safeManifest.recordCount()) {
            throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Manifest recordCount mismatch");
        }
        requireUniqueEventIds(events);
        return new ReplayDataset(safeManifest, eventsSha256, events, safeAnnotations);
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
            EvaluationEvent event = EvaluationEventJson.parse(line);
            return EvaluationEventReplayBridge.toReplaySourceEvent(event, sequence);
        } catch (EvaluationContractException e) {
            throw new ReplayException(ReplayFailureKind.INPUT_PARSE_FAILURE,
                "Failed to parse dataset event at sequence " + sequence + ": " + e.getMessage(), e);
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
