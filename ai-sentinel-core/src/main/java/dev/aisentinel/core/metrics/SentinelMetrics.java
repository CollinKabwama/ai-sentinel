package dev.aisentinel.core.metrics;

import dev.aisentinel.core.policy.EnforcementAction;

import java.util.Collection;

/**
 * Optional observability hooks for Sentinel (no Micrometer/Spring dependency in core).
 * Default methods are no-ops; production wiring is provided by the Spring Boot starter.
 */
public interface SentinelMetrics {

    SentinelMetrics NOOP = new SentinelMetrics() {};

    /** Final blended score after {@link dev.aisentinel.core.scoring.CompositeScorer} aggregation. */
    default void recordCompositeScore(double score) {}

    /** Statistical (Welford) sub-score before blending. */
    default void recordStatisticalScore(double score) {}

    /** Isolation Forest sub-score (or fallback when no model). */
    default void recordIsolationForestScore(double score) {}

    /**
     * Isolation Forest request-path resolution mode ({@code MODEL}, {@code FALLBACK_NO_MODEL},
     * {@code FALLBACK_INVALID}). Call in addition to {@link #recordIsolationForestScore(double)}.
     */
    default void recordIsolationForestScoreMode(String mode) {}

    default void recordPipelineLatencyNanos(long nanos) {}

    /** Time spent in {@link dev.aisentinel.core.scoring.AnomalyScorer#score} for the request. */
    default void recordScoringLatencyNanos(long nanos) {}

    /**
     * Online baseline / scorer update ran for this request.
     *
     * @param policyMode low-cardinality {@link dev.aisentinel.core.baseline.BaselineUpdateMode#name()}
     * @param warmup     {@code true} when update was forced because evaluation was in statistical warmup
     */
    default void recordBaselineUpdateAccepted(String policyMode, boolean warmup) {}

    /**
     * Online baseline / scorer update was skipped by the configured policy.
     *
     * @param policyMode low-cardinality {@link dev.aisentinel.core.baseline.BaselineUpdateMode#name()}
     */
    default void recordBaselineUpdateSkipped(String policyMode) {}

    /**
     * Statistical baseline key was reset (controlled relearn).
     *
     * @param reason low-cardinality reason such as {@code EXPLICIT}
     */
    default void recordBaselineRelearn(String reason) {}

    /** IF model inference only (hot path inside {@link dev.aisentinel.core.scoring.IsolationForestScorer#score}). */
    default void recordIsolationForestInferenceLatencyNanos(long nanos) {}

    /** Policy outcome applied (after grace / quarantine overrides). */
    default void recordPolicyAction(EnforcementAction action) {}

    /**
     * Low-cardinality risk-explanation summary (factor count / top factor code / advisory code).
     * Implementations must not use free-form descriptions as metric labels.
     */
    default void recordRiskExplanation(dev.aisentinel.core.decision.RiskExplanation explanation) {}

    /**
     * Request allowed despite pipeline error (fail-open). Aggregate counter for back-compat;
     * prefer {@link #recordFailOpen(FailOpenReason)} so operators can split by cause.
     */
    default void recordFailOpen() {}

    /**
     * Fail-open with a structured reason. Default implementation delegates to {@link #recordFailOpen()}
     * so existing {@link SentinelMetrics} implementations remain source-compatible.
     */
    default void recordFailOpen(FailOpenReason reason) {
        recordFailOpen();
    }

    /**
     * Records each {@link dev.aisentinel.core.decision.EvaluationStatus} observed on a completed decision.
     * Default no-op; Micrometer tags by status name (low cardinality enum).
     */
    default void recordEvaluationStatuses(Collection<? extends Enum<?>> statuses) {}

    /**
     * Legacy meter: historically incremented when NaN/negative scores were clamped to {@code 1.0}.
     * Prefer {@link #recordInvalidScoreRejected()} for new call sites. Retained for meter continuity;
     * Increment 1 no longer clamps invalid scores to maximum risk.
     */
    default void recordNanOrNegativeScoreClamped() {}

    /**
     * Scorer returned NaN, {@code ±Infinity}, or a negative finite value; decision path rejected it as
     * {@link dev.aisentinel.core.decision.EvaluationStatus#INVALID_SCORE} (not maximum risk).
     */
    default void recordInvalidScoreRejected() {}

    /**
     * Operator (or automated) quarantine release was invoked.
     *
     * @param hadLocalEntry {@code true} when a local map entry was removed
     */
    default void recordQuarantineReleased(boolean hadLocalEntry) {}

    /** Cluster quarantine clear/delete was attempted for an exact tenant+key. */
    default void recordDistributedQuarantineClearAttempt() {}

    /** Cluster quarantine clear/delete completed successfully (including missing-key idempotent success). */
    default void recordDistributedQuarantineClearSuccess() {}

    /** Cluster quarantine clear/delete failed (observable; local release still retained). */
    default void recordDistributedQuarantineClearFailure() {}

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

    /** Background poll for registry active pointer started. */
    default void recordModelRegistryRefreshAttempt() {}

    /** Active registry version matches the loaded artifact; no fetch performed. */
    default void recordModelRegistryRefreshSkippedSameVersion() {}

    /** New artifact fetched and installed successfully on the scorer. */
    default void recordModelRegistryRefreshSuccess() {}

    /** Poll or post-fetch install path failed (last-known-good model retained). */
    default void recordModelRegistryRefreshFailure() {}

    /** {@link dev.aisentinel.core.scoring.IsolationForestScorer#tryInstallFromRegistry} succeeded. */
    default void recordModelRegistryInstallSuccess() {}

    /** Registry artifact rejected (checksum, decode, or dimension mismatch). */
    default void recordModelRegistryInstallFailure() {}

    /** Redis read/write for behavioral trust baseline succeeded. */
    default void recordTrustBaselineRedisSuccess() {}

    /** Redis read/write for behavioral trust baseline failed (connection, timeout, etc.). */
    default void recordTrustBaselineRedisFailure() {}

    /** Behavioral baseline update used in-memory path after Redis failure (fail-open). */
    default void recordTrustBaselineRedisFallback() {}
}
