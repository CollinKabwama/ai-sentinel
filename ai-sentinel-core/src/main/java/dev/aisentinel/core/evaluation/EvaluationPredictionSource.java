package dev.aisentinel.core.evaluation;

/**
 * Deterministic boundary that produced the evaluation prediction.
 */
public enum EvaluationPredictionSource {
    REPLAY_SCORE,
    /**
     * Prediction produced by deterministic replay of an explicitly loaded candidate scorer.
     * This is not evaluation truth and not a production prediction source.
     */
    CANDIDATE_REPLAY_SCORE
}
