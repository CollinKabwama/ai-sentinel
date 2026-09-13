package dev.aisentinel.core.evaluation;

/**
 * Artifact-schema constants for the Official Detection Reference Baseline.
 * <p>
 * Independent from product release versions and performance baseline schemas.
 */
public final class DetectionReferenceBaselineSchemas {

    public static final String BASELINE_SCHEMA_VERSION = "1";
    public static final String BASELINE_ID = "official-detection-reference-baseline-v1";
    public static final String BASELINE_KIND = "official-detection-reference-baseline";
    public static final String MANIFEST_FILE_NAME = "manifest.json";
    public static final double REFERENCE_CLASSIFICATION_THRESHOLD = 0.5;
    public static final String THRESHOLD_BOUNDARY =
        DetectionEvaluationEvidenceGenerator.THRESHOLD_BOUNDARY;

    private DetectionReferenceBaselineSchemas() {
    }
}
