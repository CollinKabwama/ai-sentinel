package dev.aisentinel.core.evaluation;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Structural validator for deterministic detection-evaluation evidence.
 */
public final class DetectionEvaluationEvidenceValidator {
    private static final List<String> FORBIDDEN_MARKERS = List.of(
        "bearer ",
        "\"authorization\"",
        "\"cookie\"",
        "\"set-cookie\"",
        "?token=",
        "@example.com",
        "\"features\"",
        "requestsPerWindow",
        "endpointEntropy",
        "endpointConcentration",
        "tokenAgeSeconds",
        "parameterCount",
        "payloadSizeBytes",
        "headerFingerprintHash",
        "ipBucket",
        "id:synthetic-",
        "route:"
    );
    private static final Pattern ABSOLUTE_PATH = Pattern.compile("(/[^\\s`]+)+");

    public void validate(DetectionEvaluationEvidence evidence) {
        DetectionEvaluationEvidence safeEvidence = Objects.requireNonNull(evidence, "evidence");
        if (!DetectionEvaluationEvidenceGenerator.EVIDENCE_SCHEMA_VERSION.equals(safeEvidence.evidenceSchemaVersion())) {
            throw new IllegalArgumentException("unsupported evidence schema version: " + safeEvidence.evidenceSchemaVersion());
        }
        if (!DetectionEvaluationEvidenceGenerator.REPORT_KIND.equals(safeEvidence.reportKind())) {
            throw new IllegalArgumentException("unsupported report kind: " + safeEvidence.reportKind());
        }
        if (!DetectionEvaluationEvidenceGenerator.THRESHOLD_BOUNDARY.equals(safeEvidence.classification().thresholdBoundary())) {
            throw new IllegalArgumentException("unsupported threshold boundary semantics");
        }
        if (!safeEvidence.reference().ordering().equals("append-order")) {
            throw new IllegalArgumentException("unsupported reference ordering");
        }
        if (safeEvidence.counts().referenceEventCount() < safeEvidence.counts().alignedObservationCount()) {
            throw new IllegalArgumentException("referenceEventCount must be >= alignedObservationCount");
        }
        requireScenarioConsistency(safeEvidence);
    }

    public void validateArtifacts(DetectionEvaluationEvidence evidence, String json, String markdown) {
        validate(evidence);
        String safeJson = Objects.requireNonNull(json, "json");
        String safeMarkdown = Objects.requireNonNull(markdown, "markdown");
        if (!safeJson.endsWith("\n")) {
            throw new IllegalArgumentException("canonical JSON must end with newline");
        }
        if (!safeMarkdown.endsWith("\n")) {
            throw new IllegalArgumentException("canonical Markdown must end with newline");
        }
        requirePresent(safeJson, "\"evidenceSchemaVersion\":\"" + evidence.evidenceSchemaVersion() + "\"");
        requirePresent(safeJson, "\"reportKind\":\"" + evidence.reportKind() + "\"");
        requirePresent(safeJson, "\"datasetId\":\"" + evidence.reference().datasetId() + "\"");
        requirePresent(safeJson, "\"replayRunId\":\"" + evidence.replay().replayRunId() + "\"");
        requirePresent(safeJson, "\"anomalyThreshold\":" + Double.toString(evidence.classification().anomalyThreshold()));
        requirePresent(safeMarkdown, "# Detection Evaluation Evidence\n");
        requirePresent(safeMarkdown, "Threshold: `" + Double.toString(evidence.classification().anomalyThreshold()) + "`");
        requireAbsentNonFiniteNumbers(safeJson);
        requireNoForbiddenMarkers(safeJson);
        requireNoForbiddenMarkers(safeMarkdown);
        requireNoAbsolutePaths(safeJson);
        requireNoAbsolutePaths(safeMarkdown);
    }

    private static void requireScenarioConsistency(DetectionEvaluationEvidence evidence) {
        for (int i = 0; i < evidence.metrics().scenarios().size(); i++) {
            ScenarioDetectionMetrics metricScenario = evidence.metrics().scenarios().get(i);
            ScenarioTemporalEvaluation temporalScenario = evidence.temporal().scenarios().get(i);
            if (!metricScenario.scenarioId().equals(temporalScenario.scenarioId())) {
                throw new IllegalArgumentException("scenario ids must match across metrics and temporal sections");
            }
            if (metricScenario.scenarioCategory() != temporalScenario.scenarioCategory()) {
                throw new IllegalArgumentException("scenario categories must match across metrics and temporal sections");
            }
            if (metricScenario.totalObservationCount() != temporalScenario.observationCount()) {
                throw new IllegalArgumentException("scenario observation counts must match across metrics and temporal sections");
            }
        }
    }

    private static void requirePresent(String text, String needle) {
        if (!text.contains(needle)) {
            throw new IllegalArgumentException("expected artifact content missing: " + needle);
        }
    }

    private static void requireAbsentNonFiniteNumbers(String json) {
        String lower = json.toLowerCase(java.util.Locale.ROOT);
        for (String marker : List.of("nan", "infinity", "-infinity")) {
            if (lower.contains(marker)) {
                throw new IllegalArgumentException("non-finite numeric value present in canonical JSON");
            }
        }
    }

    private static void requireNoForbiddenMarkers(String text) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (String marker : FORBIDDEN_MARKERS) {
            if (lower.contains(marker.toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("forbidden marker present in evidence artifact");
            }
        }
    }

    private static void requireNoAbsolutePaths(String text) {
        if (ABSOLUTE_PATH.matcher(text).find()) {
            throw new IllegalArgumentException("absolute filesystem path leaked into evidence artifact");
        }
    }
}
