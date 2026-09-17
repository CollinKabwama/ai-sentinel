package dev.aisentinel.core.evaluation;

/**
 * Structural outcome of one candidate scorer replay/evaluation attempt.
 * <p>
 * {@code COMPLETED} means the existing evaluation framework produced structurally
 * valid evidence for a READY candidate. It does not mean the candidate is approved.
 * {@code CANDIDATE_NOT_READY} means loading failed and replay was not executed.
 * <p>
 * {@code FRAMEWORK ACCEPTANCE != DETECTION QUALITY ACCEPTANCE}<br>
 * {@code CANDIDATE LOAD FAILURE != DETECTOR PREDICTION}<br>
 * {@code CANDIDATE LOAD FAILURE != ATTACK}
 */
public enum CandidateDetectionEvaluationStatus {
    CANDIDATE_NOT_READY,
    COMPLETED
}
