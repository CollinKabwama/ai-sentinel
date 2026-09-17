package dev.aisentinel.core.evaluation;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Structural validator for candidate detection-evaluation evidence.
 */
final class CandidateDetectionEvaluationEvidenceValidator {
    static final String EVIDENCE_SCHEMA_VERSION = "1";
    static final String REPORT_KIND = "candidate-detection-evaluation";

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
    private static final Pattern ABSOLUTE_PATH = Pattern.compile("(/[^\\s`\"]+)+");

    void validate(CandidateDetectionEvaluationEvidence evidence) {
        CandidateDetectionEvaluationEvidence safe = Objects.requireNonNull(evidence, "evidence");
        if (!EVIDENCE_SCHEMA_VERSION.equals(safe.evidenceSchemaVersion())) {
            throw new IllegalArgumentException("unsupported candidate evidence schema version: " + safe.evidenceSchemaVersion());
        }
        if (!REPORT_KIND.equals(safe.reportKind())) {
            throw new IllegalArgumentException("unsupported candidate report kind: " + safe.reportKind());
        }
        if (!DetectionEvaluationEvidenceGenerator.THRESHOLD_BOUNDARY.equals(safe.classification().thresholdBoundary())) {
            throw new IllegalArgumentException("unsupported threshold boundary semantics");
        }
        if (safe.status() == CandidateDetectionEvaluationStatus.COMPLETED) {
            DetectionEvaluationEvidence nested = safe.evaluation().orElseThrow();
            new DetectionEvaluationEvidenceValidator().validate(nested);
        }
    }

    void validateArtifacts(CandidateDetectionEvaluationEvidence evidence, String json, String markdown) {
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
        requirePresent(safeJson, "\"status\":\"" + evidence.status().name() + "\"");
        requirePresent(safeMarkdown, "# Candidate Detection Evaluation Evidence\n");
        requirePresent(safeMarkdown, "Status: `" + evidence.status().name() + "`");
        requireAbsent(safeJson, "official-detection-reference-baseline");
        requireAbsent(safeMarkdown, "Official Detection Reference Baseline capture");
        requireNoForbiddenMarkers(safeJson);
        requireNoForbiddenMarkers(safeMarkdown);
        requireNoAbsolutePaths(safeJson);
        requireNoAbsolutePaths(safeMarkdown);
        if (evidence.status() == CandidateDetectionEvaluationStatus.COMPLETED) {
            requirePresent(safeJson, "\"evaluation\":{");
            requireAbsent(safeJson, "\"evaluation\":null");
        } else {
            requirePresent(safeJson, "\"evaluation\":null");
        }
    }

    private static void requirePresent(String text, String snippet) {
        if (!text.contains(snippet)) {
            throw new IllegalArgumentException("candidate evidence is missing required text: " + snippet);
        }
    }

    private static void requireAbsent(String text, String snippet) {
        if (text.contains(snippet)) {
            throw new IllegalArgumentException("candidate evidence contains forbidden text: " + snippet);
        }
    }

    private static void requireNoForbiddenMarkers(String text) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (String marker : FORBIDDEN_MARKERS) {
            if (lower.contains(marker.toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("candidate evidence contains forbidden marker");
            }
        }
    }

    private static void requireNoAbsolutePaths(String text) {
        if (ABSOLUTE_PATH.matcher(text).find() && (text.contains("/Users/") || text.contains("/home/") || text.contains("C:\\\\"))) {
            throw new IllegalArgumentException("candidate evidence must not contain absolute local paths");
        }
    }
}
