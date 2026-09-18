package dev.aisentinel.core.scoring.lifecycle;

import dev.aisentinel.core.evaluation.CandidateEvaluationAcceptanceStatus;

import java.util.Objects;
import java.util.Optional;

/**
 * Caller-supplied binding to offline evaluation evidence used for comparison
 * and promotion governance.
 * <p>
 * Digests are optional when only in-memory metrics are compared, but when
 * present they participate in stale/substitution protection.
 */
public final class EvaluationEvidenceBinding {

    private final String datasetId;
    private final double classificationThreshold;
    private final CandidateEvaluationAcceptanceStatus acceptanceStatus;
    private final String evaluationJsonSha256Hex;
    private final String featureSchemaVersion;
    private final String evaluationSplitId;

    public EvaluationEvidenceBinding(
        String datasetId,
        double classificationThreshold,
        CandidateEvaluationAcceptanceStatus acceptanceStatus,
        String evaluationJsonSha256Hex,
        String featureSchemaVersion
    ) {
        this(datasetId, classificationThreshold, acceptanceStatus, evaluationJsonSha256Hex,
            featureSchemaVersion, null);
    }

    public EvaluationEvidenceBinding(
        String datasetId,
        double classificationThreshold,
        CandidateEvaluationAcceptanceStatus acceptanceStatus,
        String evaluationJsonSha256Hex,
        String featureSchemaVersion,
        String evaluationSplitId
    ) {
        this.datasetId = requireNonBlank(datasetId, "datasetId");
        if (!Double.isFinite(classificationThreshold) || classificationThreshold < 0.0 || classificationThreshold > 1.0) {
            throw new IllegalArgumentException("classificationThreshold must be finite in [0,1]");
        }
        this.classificationThreshold = classificationThreshold;
        this.acceptanceStatus = Objects.requireNonNull(acceptanceStatus, "acceptanceStatus");
        this.evaluationJsonSha256Hex = evaluationJsonSha256Hex == null || evaluationJsonSha256Hex.isBlank()
            ? null
            : ModelLifecycleCanonical.requireSha256Hex(evaluationJsonSha256Hex, "evaluationJsonSha256Hex");
        this.featureSchemaVersion = featureSchemaVersion == null || featureSchemaVersion.isBlank()
            ? null
            : featureSchemaVersion.trim();
        this.evaluationSplitId = evaluationSplitId == null || evaluationSplitId.isBlank()
            ? null
            : evaluationSplitId.trim();
    }

    public static EvaluationEvidenceBinding of(
        String datasetId,
        double classificationThreshold,
        CandidateEvaluationAcceptanceStatus acceptanceStatus
    ) {
        return new EvaluationEvidenceBinding(datasetId, classificationThreshold, acceptanceStatus, null, null, null);
    }

    public String datasetId() {
        return datasetId;
    }

    public double classificationThreshold() {
        return classificationThreshold;
    }

    public CandidateEvaluationAcceptanceStatus acceptanceStatus() {
        return acceptanceStatus;
    }

    public Optional<String> evaluationJsonSha256Hex() {
        return Optional.ofNullable(evaluationJsonSha256Hex);
    }

    public Optional<String> featureSchemaVersion() {
        return Optional.ofNullable(featureSchemaVersion);
    }

    public Optional<String> evaluationSplitId() {
        return Optional.ofNullable(evaluationSplitId);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }
}
