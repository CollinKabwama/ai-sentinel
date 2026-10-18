package dev.aisentinel.core.evaluation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class EvaluationComparisonLoader {
    private static final String RESULT_FILE = "kit-evaluation-result.json";
    private static final String INSPECTION_FILE = "event-inspection.json";

    RunEvidence load(Path runDirectory, String side) {
        Path root = runDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            fail(GeneratedCorpusEvaluationFailureKind.MISSING_ARTIFACT,
                side + " run directory does not exist or is not a directory: " + root, null);
        }
        Map<String, Object> result = readObject(root.resolve(RESULT_FILE), side);
        Map<String, Object> inspection = readObject(root.resolve(INSPECTION_FILE), side);
        try {
            validateReferences(result, "$");
            String resultSchema = DeterministicJson.requireString(result, "resultSchemaVersion");
            if (!"1".equals(resultSchema)) {
                throw new IllegalArgumentException("unsupported resultSchemaVersion: " + resultSchema);
            }
            String inspectionSchema = DeterministicJson.requireString(inspection, "inspectionSchemaVersion");
            if (!"1".equals(inspectionSchema)) {
                throw new IllegalArgumentException("unsupported inspectionSchemaVersion: " + inspectionSchema);
            }
            Map<String, Object> provenance =
                DeterministicJson.requireObject(result.get("provenance"), "provenance");
            String source = DeterministicJson.optionalString(
                provenance, "datasetSource", "generated-corpus");
            if (!source.equals("generated-corpus") && !source.equals("evaluator-provided")) {
                throw new IllegalArgumentException("unsupported datasetSource: " + source);
            }
            Map<String, MetricFamily> families = parseFamilies(
                DeterministicJson.requireObject(result.get("metricFamilies"), "metricFamilies"));
            Map<String, EventEvidence> events = parseEvents(inspection);
            long declared = DeterministicJson.requireLong(inspection, "eventCount");
            if (declared != events.size()) {
                throw new IllegalArgumentException(
                    "event-inspection eventCount does not match events array");
            }
            double threshold = inspection.containsKey("anomalyThreshold")
                ? DeterministicJson.requireDouble(inspection, "anomalyThreshold")
                : metricValue(families, "structural", "anomalyThreshold");
            if (threshold < 0.0d || threshold > 1.0d) {
                throw new IllegalArgumentException("anomalyThreshold must be in [0,1]");
            }
            String corpusId = optional(provenance, "corpusId");
            String datasetId = optional(provenance, "datasetId");
            String inspectionIdentity = source.equals("generated-corpus")
                ? optional(inspection, "corpusId") : optional(inspection, "datasetId");
            String resultIdentity = source.equals("generated-corpus") ? corpusId : datasetId;
            if (inspectionIdentity == null || !inspectionIdentity.equals(resultIdentity)) {
                throw new IllegalArgumentException("event-inspection dataset identity differs from result");
            }
            return new RunEvidence(
                source,
                DeterministicJson.requireString(result, "resultId"),
                emptyToNull(optional(result, "evaluationRunId")),
                resultSchema,
                DeterministicJson.requireString(provenance, "featureSchemaVersion"),
                DeterministicJson.requireString(provenance, "evaluationEventSchemaVersion"),
                optional(provenance, "representationMode"),
                corpusId,
                optional(provenance, "corpusEventsSha256"),
                datasetId,
                optional(provenance, "eventsSha256"),
                emptyToNull(optional(provenance, "annotationsSha256")),
                threshold,
                families,
                events
            );
        } catch (IllegalArgumentException ex) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.INTEGRITY_FAILURE,
                "Invalid " + side + " evaluation evidence: " + ex.getMessage(), ex);
        }
    }

    private static Map<String, Object> readObject(Path path, String side) {
        if (!Files.isRegularFile(path)) {
            fail(GeneratedCorpusEvaluationFailureKind.MISSING_ARTIFACT,
                "Missing required " + side + " artifact: " + path, null);
        }
        try {
            return DeterministicJson.requireObject(
                DeterministicJson.parse(Files.readString(path)), path.getFileName().toString());
        } catch (IOException | IllegalArgumentException ex) {
            fail(GeneratedCorpusEvaluationFailureKind.INTEGRITY_FAILURE,
                "Unable to read " + side + " artifact " + path + ": " + ex.getMessage(), ex);
            throw new AssertionError();
        }
    }

    private static Map<String, MetricFamily> parseFamilies(Map<String, Object> raw) {
        Map<String, MetricFamily> result = new LinkedHashMap<>();
        for (String name : List.of("structural", "detectionLabeled", "temporal")) {
            if (!raw.containsKey(name)) {
                continue;
            }
            Map<String, Object> family = DeterministicJson.requireObject(raw.get(name), name);
            String availability = DeterministicJson.requireString(family, "availability");
            if (!List.of("available", "unavailable", "not_applicable").contains(availability)) {
                throw new IllegalArgumentException(name + ".availability is invalid");
            }
            Map<String, Double> values = new LinkedHashMap<>();
            if (family.containsKey("values")) {
                Map<String, Object> rawValues =
                    DeterministicJson.requireObject(family.get("values"), name + ".values");
                rawValues.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                    if (entry.getValue() instanceof Number number) {
                        double value = number.doubleValue();
                        if (!Double.isFinite(value)) {
                            throw new IllegalArgumentException(name + "." + entry.getKey() + " must be finite");
                        }
                        values.put(entry.getKey(), value);
                    } else if (entry.getValue() == null) {
                        values.put(entry.getKey(), null);
                    }
                });
            }
            result.put(name, new MetricFamily(
                availability, Collections.unmodifiableMap(new LinkedHashMap<>(values))));
        }
        return Map.copyOf(result);
    }

    private static Map<String, EventEvidence> parseEvents(Map<String, Object> inspection) {
        List<Object> rawEvents = DeterministicJson.requireArray(inspection.get("events"), "events");
        Map<String, EventEvidence> events = new LinkedHashMap<>();
        for (Object raw : rawEvents) {
            Map<String, Object> event = DeterministicJson.requireObject(raw, "events[]");
            String id = DeterministicJson.requireString(event, "eventId");
            Double score = nullableDouble(event, "anomalyScore");
            Boolean predicted = nullableBoolean(event, "predictedAnomalous");
            List<String> statuses = new ArrayList<>();
            for (Object status : DeterministicJson.requireArray(
                event.get("evaluationStatuses"), "evaluationStatuses")) {
                if (!(status instanceof String text)) {
                    throw new IllegalArgumentException("evaluationStatuses entries must be strings");
                }
                statuses.add(text);
            }
            EventEvidence parsed = new EventEvidence(
                id,
                DeterministicJson.requireLong(event, "sequenceNumber"),
                score,
                predicted,
                DeterministicJson.requireString(event, "action"),
                List.copyOf(statuses),
                DeterministicJson.requireString(event, "outcome"),
                DeterministicJson.requireString(event, "expectedClass"),
                DeterministicJson.requireString(event, "participation"),
                DeterministicJson.requireBoolean(event, "binaryMetricParticipant")
            );
            if (events.put(id, parsed) != null) {
                throw new IllegalArgumentException("duplicate eventId: " + id);
            }
        }
        return Map.copyOf(events);
    }

    private static void validateReferences(Object value, String location) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object child = entry.getValue();
                if ((key.endsWith("Ref") || "path".equals(key)) && child instanceof String ref) {
                    validateReference(ref, location + "." + key);
                }
                validateReferences(child, location + "." + key);
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                validateReferences(list.get(i), location + "[" + i + "]");
            }
        }
    }

    private static void validateReference(String ref, String location) {
        try {
            Path path = Path.of(ref);
            String normalizedSeparators = ref.replace('\\', '/');
            if (ref.isBlank() || path.isAbsolute() || normalizedSeparators.startsWith("/")
                || ref.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")
                || normalizedSeparators.matches("(^|.*/)\\.\\.(/.*|$)")
                || path.normalize().startsWith("..")) {
                throw new IllegalArgumentException(location + " must be a confined run-relative path");
            }
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException(location + " is not a valid path", ex);
        }
    }

    private static Double nullableDouble(Map<String, Object> object, String field) {
        if (!object.containsKey(field) || object.get(field) == null) {
            return null;
        }
        return DeterministicJson.requireDouble(object, field);
    }

    private static Boolean nullableBoolean(Map<String, Object> object, String field) {
        if (!object.containsKey(field) || object.get(field) == null) {
            return null;
        }
        return DeterministicJson.requireBoolean(object, field);
    }

    private static String optional(Map<String, Object> object, String field) {
        return object.containsKey(field)
            ? DeterministicJson.optionalString(object, field, null) : null;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static double metricValue(
        Map<String, MetricFamily> families, String family, String metric
    ) {
        MetricFamily found = families.get(family);
        Double value = found == null ? null : found.values().get(metric);
        if (value == null) {
            throw new IllegalArgumentException("missing anomalyThreshold");
        }
        return value;
    }

    private static void fail(
        GeneratedCorpusEvaluationFailureKind kind, String message, Throwable cause
    ) {
        if (cause == null) {
            throw new GeneratedCorpusEvaluationException(kind, message);
        }
        throw new GeneratedCorpusEvaluationException(kind, message, cause);
    }
}
