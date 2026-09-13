package dev.aisentinel.core.evaluation;

import java.util.List;
import java.util.Objects;

/**
 * Compact provenance binding for one Official Detection Reference Baseline capture.
 * <p>
 * Canonical quality and temporal evidence remain in {@code evaluation.json} /
 * {@code evaluation.md}. This manifest binds those artifacts and material inputs.
 */
public record DetectionReferenceBaselineManifest(
    String baselineSchemaVersion,
    String baselineId,
    String baselineKind,
    String purpose,
    DetectionEvaluationEvidence.ReferenceProvenance reference,
    String annotationsSha256,
    DetectionEvaluationEvidence.ReplayProvenance replay,
    DetectionEvaluationEvidence.ClassificationProvenance classification,
    DetectionEvaluationEvidence.StructuralCounts structure,
    ArtifactDigests artifacts,
    List<String> limitations
) {
    public DetectionReferenceBaselineManifest {
        baselineSchemaVersion = requireNotBlank("baselineSchemaVersion", baselineSchemaVersion);
        baselineId = requireNotBlank("baselineId", baselineId);
        baselineKind = requireNotBlank("baselineKind", baselineKind);
        purpose = requireNotBlank("purpose", purpose);
        reference = Objects.requireNonNull(reference, "reference");
        annotationsSha256 = requireSha256("annotationsSha256", annotationsSha256);
        replay = Objects.requireNonNull(replay, "replay");
        classification = Objects.requireNonNull(classification, "classification");
        structure = Objects.requireNonNull(structure, "structure");
        artifacts = Objects.requireNonNull(artifacts, "artifacts");
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        for (String limitation : limitations) {
            requireNotBlank("limitation", limitation);
        }
        if (!DetectionReferenceBaselineSchemas.BASELINE_SCHEMA_VERSION.equals(baselineSchemaVersion)) {
            throw new IllegalArgumentException("unsupported baselineSchemaVersion: " + baselineSchemaVersion);
        }
        if (!DetectionReferenceBaselineSchemas.BASELINE_ID.equals(baselineId)) {
            throw new IllegalArgumentException("unexpected baselineId: " + baselineId);
        }
        if (!DetectionReferenceBaselineSchemas.BASELINE_KIND.equals(baselineKind)) {
            throw new IllegalArgumentException("unexpected baselineKind: " + baselineKind);
        }
        if (Double.compare(
            classification.anomalyThreshold(),
            DetectionReferenceBaselineSchemas.REFERENCE_CLASSIFICATION_THRESHOLD) != 0) {
            throw new IllegalArgumentException("baseline classification threshold must be 0.5");
        }
        if (!DetectionReferenceBaselineSchemas.THRESHOLD_BOUNDARY.equals(classification.thresholdBoundary())) {
            throw new IllegalArgumentException("baseline thresholdBoundary must match accepted evidence boundary");
        }
        if (!reference.datasetId().equals(replay.datasetId())) {
            throw new IllegalArgumentException("datasetId must reconcile across baseline reference and replay");
        }
    }

    /**
     * Exact SHA-256 digests of published evaluation artifacts.
     */
    public record ArtifactDigests(
        String evaluationJsonFile,
        String evaluationMarkdownFile,
        String evaluationJsonSha256,
        String evaluationMarkdownSha256,
        long evaluationJsonBytes,
        long evaluationMarkdownBytes
    ) {
        public ArtifactDigests {
            evaluationJsonFile = requireNotBlank("evaluationJsonFile", evaluationJsonFile);
            evaluationMarkdownFile = requireNotBlank("evaluationMarkdownFile", evaluationMarkdownFile);
            evaluationJsonSha256 = requireSha256("evaluationJsonSha256", evaluationJsonSha256);
            evaluationMarkdownSha256 = requireSha256("evaluationMarkdownSha256", evaluationMarkdownSha256);
            if (evaluationJsonBytes <= 0L || evaluationMarkdownBytes <= 0L) {
                throw new IllegalArgumentException("evaluation artifact byte counts must be > 0");
            }
        }
    }

    private static String requireNotBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String requireSha256(String field, String value) {
        String safe = requireNotBlank(field, value);
        if (!safe.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be 64 lowercase hex characters");
        }
        return safe;
    }
}
