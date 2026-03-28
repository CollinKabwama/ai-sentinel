package io.aisentinel.core.metrics;

import io.aisentinel.core.policy.EnforcementAction;

/**
 * Optional observability hooks for Sentinel (no Micrometer/Spring dependency in core).
 * Default methods are no-ops; production wiring is provided by the Spring Boot starter.
 */
public interface SentinelMetrics {

    SentinelMetrics NOOP = new SentinelMetrics() {};

    /** Final blended score after {@link io.aisentinel.core.scoring.CompositeScorer} aggregation. */
    default void recordCompositeScore(double score) {}

    /** Statistical (Welford) sub-score before blending. */
    default void recordStatisticalScore(double score) {}

    /** Isolation Forest sub-score (or fallback when no model). */
    default void recordIsolationForestScore(double score) {}

    default void recordPipelineLatencyNanos(long nanos) {}

    /** Time spent in {@link io.aisentinel.core.scoring.AnomalyScorer#score} + {@code update} for the request. */
    default void recordScoringLatencyNanos(long nanos) {}

    /** IF model inference only (hot path inside {@link io.aisentinel.core.scoring.IsolationForestScorer#score}). */
    default void recordIsolationForestInferenceLatencyNanos(long nanos) {}

    /** Policy outcome applied (after grace / quarantine overrides). */
    default void recordPolicyAction(EnforcementAction action) {}

    /** Request allowed despite pipeline error (fail-open). */
    default void recordFailOpen() {}

    /** Illegal raw score corrected before policy (NaN or negative). */
    default void recordNanOrNegativeScoreClamped() {}

    /** Exception during scoring/update. */
    default void recordScoringError() {}

    default void recordRetrainSuccessNanos(long nanos) {}

    default void recordRetrainFailureNanos(long nanos) {}
}
