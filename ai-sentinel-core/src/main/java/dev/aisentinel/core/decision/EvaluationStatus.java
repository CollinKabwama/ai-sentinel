package dev.aisentinel.core.decision;

/**
 * Lifecycle / degradation markers for a single {@link RiskDecision}.
 * <p>
 * A decision may carry multiple statuses (e.g. statistical live + model fallback).
 * {@link #COMPLETE} means no degradation or fallback occurred and must not be combined with
 * {@link #MODEL_FALLBACK_USED}, {@link #MODEL_UNAVAILABLE}, or {@link #STATISTICAL_WARMUP}.
 */
public enum EvaluationStatus {

    /** No degradation or fallback; live evaluation completed normally. */
    COMPLETE,

    /** Statistical baseline lacks enough samples for live z-score scoring. */
    STATISTICAL_WARMUP,

    /** Statistical scorer evaluated against an established baseline. */
    STATISTICAL_LIVE,

    /** A configured Isolation Forest model is not loaded. */
    MODEL_UNAVAILABLE,

    /** Configured IF fallback score was used instead of model inference. */
    MODEL_FALLBACK_USED
}
