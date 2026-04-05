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

    /** Cluster quarantine read path invoked (includes cache hits). */
    default void recordDistributedQuarantineLookup() {}

    /** Cluster reader reported active quarantine (expiry in the future). */
    default void recordDistributedQuarantineClusterHit() {}

    default void recordDistributedQuarantineCacheHit() {}

    default void recordDistributedQuarantineCacheMiss() {}

    default void recordDistributedRedisTimeout() {}

    default void recordDistributedRedisFailure() {}

    /**
     * Wall-clock duration of a Redis GET attempt for cluster quarantine (cache miss path only).
     * Includes successful reads, timeouts, and failures.
     */
    default void recordDistributedRedisLookupDurationNanos(long nanos) {}

    /** Cluster quarantine write requested (before async Redis work). */
    default void recordDistributedQuarantineWriteAttempt() {}

    default void recordDistributedQuarantineWriteSuccess() {}

    /** Redis SET failed on the async worker (not scheduler/backpressure skips). */
    default void recordDistributedQuarantineWriteFailure() {}

    /**
     * Worker determined {@code untilEpochMillis} is already in the past; no SET performed.
     * Does not indicate a healthy write and must not be confused with {@link #recordDistributedQuarantineWriteSuccess()}.
     */
    default void recordDistributedQuarantineWriteSkippedExpired() {}

    /** Dropped because in-flight cap was reached ({@code tryAcquire} on bounded work). */
    default void recordDistributedQuarantineWriteDropped() {}

    /** {@link java.util.concurrent.Executor#execute} rejected the task (after permit was acquired). */
    default void recordDistributedQuarantineWriteSchedulerRejected() {}

    /** Duration of async cluster quarantine write work unit (worker thread, including expired skip). */
    default void recordDistributedQuarantineWriteDurationNanos(long nanos) {}

    /** Cluster throttle evaluation invoked (THROTTLE path only, when store is non-noop). */
    default void recordDistributedThrottleEvaluation() {}

    /** Cluster throttle allowed request (under window cap). */
    default void recordDistributedThrottleClusterAllow() {}

    /** Cluster throttle rejected request (window exhausted). */
    default void recordDistributedThrottleClusterReject() {}

    default void recordDistributedThrottleRedisTimeout() {}

    default void recordDistributedThrottleRedisFailure() {}

    /**
     * In-flight semaphore saturated (tryAcquire failed) or executor rejected the async task before it ran.
     * Distinct from {@link #recordDistributedThrottleRedisFailure()} and {@link #recordDistributedThrottleRedisTimeout()}.
     */
    default void recordDistributedThrottleExecutorRejected() {}

    /** Wall-clock duration of Redis throttle script (success, timeout, or failure). */
    default void recordDistributedThrottleEvalDurationNanos(long nanos) {}

    /** Training candidate async worker began a transport send. */
    default void recordTrainingCandidatePublishAttempt() {}

    default void recordTrainingCandidatePublishSuccess() {}

    default void recordTrainingCandidatePublishFailure() {}

    /** Dropped before worker (in-flight semaphore saturated). */
    default void recordTrainingCandidatePublishDropped() {}

    /** Skipped by probabilistic sample gate. */
    default void recordTrainingCandidatePublishSkippedSample() {}

    /** Skipped by score floor or IF anti-poisoning gate. */
    default void recordTrainingCandidatePublishSkippedGate() {}

    default void recordTrainingCandidatePublishExecutorRejected() {}

    /** Publisher threw from the request-thread hook (should not happen for well-behaved publishers). */
    default void recordTrainingCandidatePublishUnexpectedFailure() {}

    /** Wall-clock time for transport send on the async worker (success or failure). */
    default void recordTrainingCandidatePublishTransportDurationNanos(long nanos) {}

    /** Kafka (or blocking transport) future timed out on the worker. */
    default void recordTrainingCandidatePublishFailureTimeout() {}

    /** JSON serialization of the training record failed before send. */
    default void recordTrainingCandidatePublishFailureSerialization() {}
}
