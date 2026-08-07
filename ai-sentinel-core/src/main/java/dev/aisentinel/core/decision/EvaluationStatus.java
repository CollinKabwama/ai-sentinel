package dev.aisentinel.core.decision;

/**
 * Lifecycle / degradation markers for a single {@link RiskDecision}.
 * <p>
 * A decision may carry multiple statuses (e.g. statistical live + model fallback).
 * {@link #COMPLETE} means no degradation or fallback occurred and must not be combined with
 * {@link #MODEL_FALLBACK_USED}, {@link #MODEL_UNAVAILABLE}, or {@link #STATISTICAL_WARMUP}.
 * {@link #BASELINE_UPDATE_SKIPPED} may appear alongside {@link #COMPLETE} or {@link #STATISTICAL_LIVE}.
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
    MODEL_FALLBACK_USED,

    /**
     * Online baseline / scorer {@code update} was skipped by the configured
     * {@link dev.aisentinel.core.baseline.BaselineUpdatePolicy}.
     */
    BASELINE_UPDATE_SKIPPED,

    /**
     * Statistical Welford state for this identity|endpoint was reset on this request
     * (explicit operator relearn). The next observations re-enter {@link #STATISTICAL_WARMUP}.
     */
    BASELINE_RELEARNED
}
