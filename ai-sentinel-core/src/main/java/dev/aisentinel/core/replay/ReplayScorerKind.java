package dev.aisentinel.core.replay;

/**
 * Replay scorer modes.
 */
public enum ReplayScorerKind {
    STATISTICAL,
    ISOLATION_FOREST,
    COMPOSITE,
    /**
     * Explicit caller-supplied evaluation scorer. Default replay construction
     * cannot materialize this kind; {@link ReplayEngine#withEvaluationScorer} is required.
     */
    CANDIDATE
}
