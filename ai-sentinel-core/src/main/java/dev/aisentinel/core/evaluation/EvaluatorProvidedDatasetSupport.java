package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.contract.EvaluationEvent;
import dev.aisentinel.core.contract.EvaluationEventJson;
import dev.aisentinel.core.dataset.EvaluationDatasetManifest;
import dev.aisentinel.core.dataset.EvaluationDatasetSchemas;
import dev.aisentinel.core.replay.ReplayDataset;
import dev.aisentinel.core.replay.ReplayDatasetLoader;
import dev.aisentinel.core.replay.ReplayException;
import dev.aisentinel.distributed.training.TrainingFingerprintHashes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads and integrity-checks evaluator-provided (BYO) Evaluation Kit dataset directories.
 * <p>
 * Builds an in-memory {@link EvaluationDatasetManifest} + {@link ReplayDataset}; does not require
 * a separate on-disk replay {@code manifest.json} or generator provenance.
 */
final class EvaluatorProvidedDatasetSupport {

    static final String DATASET_MANIFEST_FILE = "dataset-manifest.json";
    static final String ANNOTATIONS_FILE = "annotations.json";
    static final String EVENTS_FILE = "events.jsonl";

    private EvaluatorProvidedDatasetSupport() {
    }

    static LoadedDataset load(Path datasetDirectory) throws IOException {
        Path dir = requireDirectory(datasetDirectory);
        Path datasetManifestPath = requireFile(dir.resolve(DATASET_MANIFEST_FILE));
        Path eventsPath = requireFile(dir.resolve(EVENTS_FILE));
        Path annotationsPath = dir.resolve(ANNOTATIONS_FILE);

        String datasetManifestJson = Files.readString(datasetManifestPath, StandardCharsets.UTF_8);
        EvaluatorProvidedDatasetProvenance provenance = parseProvenance(datasetManifestJson);

        byte[] eventBytes = Files.readAllBytes(eventsPath);
        verifyChecksum(eventBytes, provenance.eventsSha256(), "events");

        long eventLines = countNonBlankLines(eventBytes);
        if (eventLines != provenance.eventCount()) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.INTEGRITY_FAILURE,
                "Event count mismatch: manifest=" + provenance.eventCount() + " file=" + eventLines);
        }

        boolean annotationsDeclared = hasObjectField(datasetManifestJson, "annotations");
        boolean annotationsPresent = Files.isRegularFile(annotationsPath);
        if (annotationsDeclared != annotationsPresent) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.MISSING_ARTIFACT,
                annotationsDeclared
                    ? "dataset-manifest declares annotations but annotations.json is missing"
                    : "annotations.json is present but dataset-manifest does not declare annotations");
        }

        GeneratedCorpusGroundTruth groundTruth = null;
        if (annotationsDeclared) {
            requireFile(annotationsPath);
            verifyChecksum(
                Files.readAllBytes(annotationsPath),
                provenance.annotationsSha256(),
                "annotations"
            );
            groundTruth = parseGroundTruth(
                Files.readString(annotationsPath, StandardCharsets.UTF_8),
                provenance.datasetId()
            );
        }

        EvaluationDatasetManifest replayManifest = new EvaluationDatasetManifest(
            EvaluationDatasetSchemas.DATASET_SCHEMA_VERSION,
            provenance.datasetId(),
            Instant.parse("1970-01-01T00:00:00Z"),
            "0.4.0",
            provenance.featureSchemaVersion(),
            provenance.evaluationEventSchemaVersion(),
            provenance.eventCount(),
            EvaluationDatasetSchemas.ORDERING_APPEND_ORDER,
            "evaluator-provided",
            "evaluator-provided-1",
            EvaluationDatasetSchemas.EVENTS_FILE_NAME,
            provenance.eventsSha256(),
            "Evaluator-provided Evaluation Kit dataset",
            ""
        );

        ReplayDataset replayDataset;
        try {
            replayDataset = new ReplayDatasetLoader().fromManifestAndEvents(
                replayManifest,
                eventBytes,
                new ReplayDataset.AnnotationMetadata("", provenance.datasetId(), 0)
            );
        } catch (ReplayException ex) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.INTEGRITY_FAILURE,
                ex.getMessage() == null ? "Failed to load evaluator-provided events" : ex.getMessage()
            );
        }

        // Reject detector-facing labels / pre-asserted runtime statuses.
        for (ReplayDataset.ReplaySourceEvent event : replayDataset.events()) {
            if (!event.historicalOutput().evaluationStatuses().isEmpty()) {
                throw new GeneratedCorpusEvaluationException(
                    GeneratedCorpusEvaluationFailureKind.INTEGRITY_FAILURE,
                    "Evaluator-provided events must not pre-assert runtime evaluationStatuses: "
                        + event.replayInput().eventId());
            }
        }
        rejectForbiddenEventFields(eventBytes);

        return new LoadedDataset(dir, provenance, groundTruth, replayDataset, eventsPath, annotationsPath);
    }

    static void validateGroundTruthAgainstEvents(
        GeneratedCorpusGroundTruth groundTruth,
        Set<String> eventIds
    ) {
        GeneratedCorpusSupport.validateGroundTruthAgainstEvents(groundTruth, eventIds);
    }

    static GeneratedCorpusPhaseCounts phaseCounts(GeneratedCorpusGroundTruth groundTruth) {
        return GeneratedCorpusSupport.phaseCounts(groundTruth);
    }

    static GeneratedCorpusPhaseCounts unlabeledPhaseCounts(int eventCount) {
        return new GeneratedCorpusPhaseCounts(eventCount, 0, eventCount, 0, 0, 0);
    }

    private static EvaluatorProvidedDatasetProvenance parseProvenance(String json) {
        String schema = requireString(json, "datasetSchemaVersion");
        if (!"1".equals(schema)) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.UNSUPPORTED_SCHEMA,
                "Unsupported datasetSchemaVersion: " + schema);
        }
        String ordering = requireString(json, "ordering");
        if (!"append-order".equals(ordering)) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.UNSUPPORTED_SCHEMA,
                "Unsupported ordering: " + ordering);
        }
        if (hasStringField(json, "seed")
            || hasStringField(json, "generatorBuildId")
            || hasStringField(json, "generatorContractVersion")
            || hasStringField(json, "corpusId")) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.UNSUPPORTED_SCHEMA,
                "Evaluator-provided dataset-manifest must not include generator/corpus provenance fields");
        }

        String eventsObject = requireObject(json, "events");
        String eventsPath = requireString(eventsObject, "path");
        if (!EVENTS_FILE.equals(eventsPath)) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.UNSUPPORTED_SCHEMA,
                "Unsupported events path: " + eventsPath);
        }
        String eventsSha = requireString(eventsObject, "sha256");

        boolean labeled = hasObjectField(json, "annotations");
        String annotationsSha = "";
        if (labeled) {
            String annotationsObject = requireObject(json, "annotations");
            String annotationsPath = requireString(annotationsObject, "path");
            if (!ANNOTATIONS_FILE.equals(annotationsPath)) {
                throw new GeneratedCorpusEvaluationException(
                    GeneratedCorpusEvaluationFailureKind.UNSUPPORTED_SCHEMA,
                    "Unsupported annotations path: " + annotationsPath);
            }
            annotationsSha = requireString(annotationsObject, "sha256");
        }

        return new EvaluatorProvidedDatasetProvenance(
            requireString(json, "datasetId"),
            schema,
            requireString(json, "featureSchemaVersion"),
            requireString(json, "evaluationEventSchemaVersion"),
            requireString(json, "representationMode"),
            eventsSha,
            annotationsSha,
            requireInt(json, "eventCount"),
            labeled
        );
    }

    private static GeneratedCorpusGroundTruth parseGroundTruth(String json, String expectedDatasetId) {
        if (hasStringField(json, "corpusId")) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.GROUND_TRUTH_FAILURE,
                "Evaluator-provided annotations must use datasetId, not corpusId");
        }
        String datasetId = requireString(json, "datasetId");
        if (!datasetId.equals(expectedDatasetId)) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.GROUND_TRUTH_FAILURE,
                "annotations datasetId does not match dataset-manifest datasetId");
        }
        String scenarioId = optionalString(json, "scenarioId");
        if (scenarioId.isEmpty()) {
            scenarioId = datasetId;
        }
        if (!"1".equals(requireString(json, "annotationSchemaVersion"))) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.UNSUPPORTED_SCHEMA,
                "Unsupported annotationSchemaVersion: " + requireString(json, "annotationSchemaVersion"));
        }

        String annotationsBody = requireArrayBody(json, "annotations");
        List<String> objects = splitTopLevelObjects(annotationsBody);
        if (objects.isEmpty()) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.GROUND_TRUTH_FAILURE,
                "annotations array must not be empty");
        }
        List<GeneratedCorpusGroundTruth.EventAnnotation> annotations = new ArrayList<>(objects.size());
        for (String object : objects) {
            String eventScenario = optionalString(object, "scenarioId");
            if (eventScenario.isEmpty()) {
                eventScenario = scenarioId;
            }
            annotations.add(new GeneratedCorpusGroundTruth.EventAnnotation(
                requireString(object, "eventId"),
                eventScenario,
                requireString(object, "expectedClass"),
                requireString(object, "category")
            ));
        }
        return new GeneratedCorpusGroundTruth(
            requireString(json, "annotationSchemaVersion"),
            requireString(json, "annotationId"),
            datasetId,
            scenarioId,
            annotations,
            optionalString(json, "notes")
        );
    }

    private static void rejectForbiddenEventFields(byte[] eventBytes) {
        String text = new String(eventBytes, StandardCharsets.UTF_8);
        String[] forbidden = {
            "expectedClass", "groundTruth", "ground_truth", "annotation", "annotations",
            "label", "labels", "anomalyExpected", "maliciousnessAsserted",
            "evaluationExpectations", "scenarioId", "corpusId", "resultId",
            "seed", "generatorContractVersion", "generatorBuildId",
            "desiredAnomalyScore", "expectedAction", "datasetId"
        };
        for (String line : text.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            EvaluationEvent event = EvaluationEventJson.parse(line);
            for (String field : forbidden) {
                if (line.contains("\"" + field + "\"")) {
                    throw new GeneratedCorpusEvaluationException(
                        GeneratedCorpusEvaluationFailureKind.INTEGRITY_FAILURE,
                        "Detector-facing event contains forbidden field " + field
                            + " for eventId=" + event.eventId());
                }
            }
        }
    }

    private static void verifyChecksum(byte[] bytes, String expectedSha, String role) {
        String actual = TrainingFingerprintHashes.sha256HexBytes(bytes);
        if (!actual.equals(expectedSha)) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.INTEGRITY_FAILURE,
                "Checksum mismatch for " + role + ": expected=" + expectedSha + " actual=" + actual);
        }
    }

    private static long countNonBlankLines(byte[] bytes) {
        long count = 0;
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n", -1)) {
            if (!line.isBlank()) {
                count++;
            }
        }
        return count;
    }

    private static Path requireDirectory(Path path) {
        Path safe = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(safe)) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.MISSING_ARTIFACT,
                "Dataset directory does not exist: " + safe);
        }
        return safe;
    }

    private static Path requireFile(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.MISSING_ARTIFACT,
                "Missing dataset artifact: " + path);
        }
        return path;
    }

    private static boolean hasObjectField(String json, String field) {
        return Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\\{").matcher(json).find();
    }

    private static boolean hasStringField(String json, String field) {
        return Pattern.compile(
            "\"" + Pattern.quote(field) + "\"\\s*:\\s*\"").matcher(json).find();
    }

    private static String requireString(String json, String field) {
        Matcher matcher = Pattern.compile(
            "\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(json);
        if (!matcher.find()) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.UNSUPPORTED_SCHEMA,
                "Missing string field: " + field);
        }
        return matcher.group(1);
    }

    private static String optionalString(String json, String field) {
        Matcher matcher = Pattern.compile(
            "\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static int requireInt(String json, String field) {
        Matcher matcher = Pattern.compile(
            "\"" + Pattern.quote(field) + "\"\\s*:\\s*(-?[0-9]+)").matcher(json);
        if (!matcher.find()) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.UNSUPPORTED_SCHEMA,
                "Missing integer field: " + field);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static String requireObject(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\\{");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.UNSUPPORTED_SCHEMA,
                "Missing object field: " + field);
        }
        return extractBalanced(json, matcher.end() - 1, '{', '}');
    }

    private static String requireArrayBody(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\\[");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.UNSUPPORTED_SCHEMA,
                "Missing array field: " + field);
        }
        String array = extractBalanced(json, matcher.end() - 1, '[', ']');
        return array.substring(1, array.length() - 1);
    }

    private static String extractBalanced(String json, int openIndex, char open, char close) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = openIndex; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return json.substring(openIndex, i + 1);
                }
            }
        }
        throw new GeneratedCorpusEvaluationException(
            GeneratedCorpusEvaluationFailureKind.UNSUPPORTED_SCHEMA,
            "Unbalanced JSON for nested value");
    }

    private static List<String> splitTopLevelObjects(String arrayBody) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < arrayBody.length(); i++) {
            char c = arrayBody.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(arrayBody.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return objects;
    }

    record LoadedDataset(
        Path directory,
        EvaluatorProvidedDatasetProvenance provenance,
        GeneratedCorpusGroundTruth groundTruth,
        ReplayDataset replayDataset,
        Path eventsPath,
        Path annotationsPath
    ) {
        boolean labeled() {
            return groundTruth != null;
        }
    }
}
