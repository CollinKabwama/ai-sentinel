package dev.aisentinel.core.evaluation;

/**
 * Overall status for one Official Detection Reference Baseline verification run.
 * <p>
 * {@link #DRIFT_DETECTED} means deterministic difference from the official baseline.
 * It is not a production quality rejection or release gate.
 */
public enum DetectionReferenceBaselineVerificationStatus {
    MATCH,
    DRIFT_DETECTED,
    BASELINE_INTEGRITY_FAILURE,
    CURRENT_EVALUATION_FAILURE
}
