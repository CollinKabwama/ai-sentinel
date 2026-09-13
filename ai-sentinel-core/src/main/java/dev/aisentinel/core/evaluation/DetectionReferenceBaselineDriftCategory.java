package dev.aisentinel.core.evaluation;

/**
 * Explicit drift dimensions for Official Detection Reference Baseline verification.
 * <p>
 * Ordering of enum declaration defines canonical category sort order for reports.
 */
public enum DetectionReferenceBaselineDriftCategory {
    BASELINE_INTEGRITY,
    DATASET_PROVENANCE,
    ANNOTATION_PROVENANCE,
    REPLAY_PROVENANCE,
    SCORER_PROVENANCE,
    POLICY_PROVENANCE,
    CLASSIFICATION_CONFIGURATION,
    STRUCTURAL_EVIDENCE,
    DETECTION_METRICS,
    TEMPORAL_EVIDENCE,
    GENERATED_EVIDENCE
}
