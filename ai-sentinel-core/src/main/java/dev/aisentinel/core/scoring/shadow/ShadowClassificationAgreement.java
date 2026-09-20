package dev.aisentinel.core.scoring.shadow;

/**
 * Observational agreement under an explicitly supplied diagnostic anomaly threshold.
 * <p>
 * {@code ANOMALOUS != MALICIOUS}<br>
 * {@code DISAGREEMENT != DEFECT}<br>
 * {@code DISAGREEMENT != GROUND TRUTH}
 * <p>
 * These labels are not true/false positive/negative outcomes; live shadow scoring
 * has no ground truth at scoring time.
 */
public enum ShadowClassificationAgreement {

    AGREE_NORMAL,
    AGREE_ANOMALOUS,
    AUTHORITATIVE_ONLY_ANOMALOUS,
    CANDIDATE_ONLY_ANOMALOUS
}
