package dev.aisentinel.autoconfigure.metrics;

import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.metrics.FailOpenReason;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.policy.EnforcementAction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Micrometer-backed {@link SentinelMetrics}. Registers meters once; recording is lightweight.
 */
public final class MicrometerSentinelMetrics implements SentinelMetrics {

    private final DistributionSummary scoreComposite;
    private final DistributionSummary scoreIf;
    private final DistributionSummary scoreStatistical;

    private final Counter actionAllow;
    private final Counter actionMonitor;
    private final Counter actionThrottle;
    private final Counter actionBlock;
    private final Counter actionQuarantine;

    private final Timer latencyPipeline;
    private final Timer latencyScoring;
    private final Timer latencyIf;

    private final Timer modelRetrainDuration;
    private final Counter modelRetrainSuccess;
    private final Counter modelRetrainFailure;

    private final Counter failOpen;
    private final Map<String, Counter> failOpenByReason = new LinkedHashMap<>();
    private final Map<String, Counter> evaluationStatusByName = new LinkedHashMap<>();
    private final Map<String, Counter> isolationForestScoreModeByName = new LinkedHashMap<>();
    private final Counter nanClamped;
    private final Counter invalidScoreRejected;
    private final Counter scoringErrors;

    private final Counter distributedQuarantineLookup;
    private final Counter distributedQuarantineClusterHit;
    private final Counter distributedQuarantineCacheHit;
    private final Counter distributedQuarantineCacheMiss;
    private final Counter distributedRedisTimeout;
    private final Counter distributedRedisFailure;
    private final Timer distributedRedisLookup;

    private final Counter distributedQuarantineWriteAttempt;
    private final Counter distributedQuarantineWriteSuccess;
    private final Counter distributedQuarantineWriteFailure;
    private final Counter distributedQuarantineWriteSkippedExpired;
    private final Counter distributedQuarantineWriteDropped;
    private final Counter distributedQuarantineWriteSchedulerRejected;
    private final Timer distributedQuarantineWrite;
    private final Counter quarantineReleasedHadLocal;
    private final Counter quarantineReleasedMissing;
    private final Counter distributedQuarantineClearAttempt;
    private final Counter distributedQuarantineClearSuccess;
    private final Counter distributedQuarantineClearFailure;

    private final Counter distributedThrottleEval;
    private final Counter distributedThrottleAllow;
    private final Counter distributedThrottleReject;
    private final Counter distributedThrottleRedisTimeout;
    private final Counter distributedThrottleRedisFailure;
    private final Counter distributedThrottleExecutorRejected;
    private final Timer distributedThrottleEvalTimer;

    private final Counter trainingPublishAttempt;
    private final Counter trainingPublishSuccess;
    private final Counter trainingPublishFailure;
    private final Counter trainingPublishDropped;
    private final Counter trainingPublishSkippedSample;
    private final Counter trainingPublishSkippedGate;
    private final Counter trainingPublishExecutorRejected;
    private final Counter trainingPublishUnexpectedFailure;
    private final Timer trainingPublishTransport;
    private final Counter trainingPublishFailureTimeout;
    private final Counter trainingPublishFailureSerialization;

    private final Counter modelRegistryRefreshAttempt;
    private final Counter modelRegistryRefreshSkippedSameVersion;
    private final Counter modelRegistryRefreshSuccess;
    private final Counter modelRegistryRefreshFailure;
    private final Counter modelRegistryInstallSuccess;
    private final Counter modelRegistryInstallFailure;

    private final Counter trustBaselineRedisSuccess;
    private final Counter trustBaselineRedisFailure;
    private final Counter trustBaselineRedisFallback;

    private final Map<String, Counter> baselineUpdateAcceptedByMode = new LinkedHashMap<>();
    private final Map<String, Counter> baselineUpdateSkippedByMode = new LinkedHashMap<>();
    private final Counter baselineUpdateAcceptedWarmup;
    private final Map<String, Counter> baselineRelearnByReason = new LinkedHashMap<>();

    public MicrometerSentinelMetrics(MeterRegistry registry) {
        this.scoreComposite = DistributionSummary.builder("aisentinel.score.composite")
            .description("Blended anomaly score after composite weighting")
            .publishPercentiles(0.5, 0.9, 0.99)
            .register(registry);
        this.scoreIf = DistributionSummary.builder("aisentinel.score.if")
            .description("Isolation Forest (or fallback) score contribution")
            .publishPercentiles(0.5, 0.9, 0.99)
            .register(registry);
        this.scoreStatistical = DistributionSummary.builder("aisentinel.score.statistical")
            .description("Statistical scorer output")
            .publishPercentiles(0.5, 0.9, 0.99)
            .register(registry);

        this.actionAllow = Counter.builder("aisentinel.action.allow").register(registry);
        this.actionMonitor = Counter.builder("aisentinel.action.monitor").register(registry);
        this.actionThrottle = Counter.builder("aisentinel.action.throttle").register(registry);
        this.actionBlock = Counter.builder("aisentinel.action.block").register(registry);
        this.actionQuarantine = Counter.builder("aisentinel.action.quarantine").register(registry);

        this.latencyPipeline = Timer.builder("aisentinel.latency.pipeline")
            .description("End-to-end Sentinel pipeline latency")
            .publishPercentiles(0.5, 0.99)
            .register(registry);
        this.latencyScoring = Timer.builder("aisentinel.latency.scoring")
            .description("AnomalyScorer score latency")
            .publishPercentiles(0.5, 0.99)
            .register(registry);
        this.latencyIf = Timer.builder("aisentinel.latency.if")
            .description("Isolation Forest inference latency")
            .publishPercentiles(0.5, 0.99)
            .register(registry);

        this.modelRetrainDuration = Timer.builder("aisentinel.model.retrain.duration")
            .description("Isolation Forest retrain duration")
            .register(registry);
        this.modelRetrainSuccess = Counter.builder("aisentinel.model.retrain.success").register(registry);
        this.modelRetrainFailure = Counter.builder("aisentinel.model.retrain.failure").register(registry);

        this.failOpen = Counter.builder("aisentinel.failopen.count")
            .description("Aggregate fail-open count (all reasons)")
            .register(registry);
        for (FailOpenReason reason : FailOpenReason.values()) {
            failOpenByReason.put(reason.name(), Counter.builder("aisentinel.failopen.reason")
                .description("Fail-open by structured reason")
                .tag("reason", reason.name())
                .register(registry));
        }
        for (EvaluationStatus status : EvaluationStatus.values()) {
            evaluationStatusByName.put(status.name(), Counter.builder("aisentinel.evaluation.status")
                .description("EvaluationStatus observed on completed RiskDecision")
                .tag("status", status.name())
                .register(registry));
        }
        for (String mode : List.of("MODEL", "FALLBACK_NO_MODEL", "FALLBACK_INVALID")) {
            isolationForestScoreModeByName.put(mode, Counter.builder("aisentinel.isolationforest.score.mode")
                .description("Isolation Forest request-path score resolution mode")
                .tag("mode", mode)
                .register(registry));
        }
        this.nanClamped = Counter.builder("aisentinel.nan.clamped.count")
            .description("Legacy: historically NaN/negative clamped to 1.0; prefer aisentinel.invalid.score.rejected")
            .register(registry);
        this.invalidScoreRejected = Counter.builder("aisentinel.invalid.score.rejected")
            .description("Scorer returned NaN/±Infinity/negative; classified INVALID_SCORE (not max risk)")
            .register(registry);
        this.scoringErrors = Counter.builder("aisentinel.errors.scoring.count").register(registry);

        this.distributedQuarantineLookup = Counter.builder("aisentinel.distributed.quarantine.lookup")
            .description("Cluster quarantine read invocations (Redis reader path)")
            .register(registry);
        this.distributedQuarantineClusterHit = Counter.builder("aisentinel.distributed.quarantine.cluster_hit")
            .description("Times cluster quarantine was active (until after now)")
            .register(registry);
        this.distributedQuarantineCacheHit = Counter.builder("aisentinel.distributed.quarantine.cache_hit").register(registry);
        this.distributedQuarantineCacheMiss = Counter.builder("aisentinel.distributed.quarantine.cache_miss").register(registry);
        this.distributedRedisTimeout = Counter.builder("aisentinel.distributed.redis.timeout").register(registry);
        this.distributedRedisFailure = Counter.builder("aisentinel.distributed.redis.failure").register(registry);
        this.distributedRedisLookup = Timer.builder("aisentinel.distributed.redis.lookup")
            .description("Redis GET for cluster quarantine (runs on cache miss)")
            .publishPercentiles(0.5, 0.99)
            .register(registry);

        this.distributedQuarantineWriteAttempt = Counter.builder("aisentinel.distributed.quarantine.write.attempt")
            .description("Cluster quarantine Redis write tasks scheduled")
            .register(registry);
        this.distributedQuarantineWriteSuccess = Counter.builder("aisentinel.distributed.quarantine.write.success")
            .description("Cluster quarantine Redis SET completed successfully")
            .register(registry);
        this.distributedQuarantineWriteFailure = Counter.builder("aisentinel.distributed.quarantine.write.failure")
            .description("Cluster quarantine Redis SET failures on worker thread")
            .register(registry);
        this.distributedQuarantineWriteSkippedExpired = Counter.builder("aisentinel.distributed.quarantine.write.skipped_expired")
            .description("Worker skipped SET because untilEpochMillis was already past")
            .register(registry);
        this.distributedQuarantineWriteDropped = Counter.builder("aisentinel.distributed.quarantine.write.dropped")
            .description("Publish dropped due to in-flight cap (semaphore)")
            .register(registry);
        this.distributedQuarantineWriteSchedulerRejected = Counter.builder("aisentinel.distributed.quarantine.write.scheduler_rejected")
            .description("Executor rejected task after permit acquired (rare)")
            .register(registry);
        this.distributedQuarantineWrite = Timer.builder("aisentinel.distributed.quarantine.write")
            .description("Redis SET for cluster quarantine (async worker)")
            .publishPercentiles(0.5, 0.99)
            .register(registry);
        this.quarantineReleasedHadLocal = Counter.builder("aisentinel.quarantine.released")
            .description("Quarantine release removed a local map entry")
            .tag("had_local", "true")
            .register(registry);
        this.quarantineReleasedMissing = Counter.builder("aisentinel.quarantine.released")
            .description("Quarantine release invoked with no local entry (idempotent)")
            .tag("had_local", "false")
            .register(registry);
        this.distributedQuarantineClearAttempt = Counter.builder("aisentinel.distributed.quarantine.clear.attempt")
            .description("Cluster quarantine Redis DEL / clear attempts")
            .register(registry);
        this.distributedQuarantineClearSuccess = Counter.builder("aisentinel.distributed.quarantine.clear.success")
            .description("Cluster quarantine clear succeeded (including missing key)")
            .register(registry);
        this.distributedQuarantineClearFailure = Counter.builder("aisentinel.distributed.quarantine.clear.failure")
            .description("Cluster quarantine clear failures")
            .register(registry);

        this.distributedThrottleEval = Counter.builder("aisentinel.distributed.throttle.evaluation")
            .description("Cluster throttle Redis evaluations (THROTTLE path)")
            .register(registry);
        this.distributedThrottleAllow = Counter.builder("aisentinel.distributed.throttle.allow")
            .description("Cluster throttle under window cap")
            .register(registry);
        this.distributedThrottleReject = Counter.builder("aisentinel.distributed.throttle.reject")
            .description("Cluster throttle window exhausted")
            .register(registry);
        this.distributedThrottleRedisTimeout = Counter.builder("aisentinel.distributed.throttle.redis.timeout")
            .register(registry);
        this.distributedThrottleRedisFailure = Counter.builder("aisentinel.distributed.throttle.redis.failure")
            .register(registry);
        this.distributedThrottleExecutorRejected = Counter.builder("aisentinel.distributed.throttle.executor.rejected")
            .description("Semaphore saturated or executor rejected async throttle work (fail-open path)")
            .register(registry);
        this.distributedThrottleEvalTimer = Timer.builder("aisentinel.distributed.throttle.redis.eval")
            .description("Redis Lua eval for cluster throttle")
            .publishPercentiles(0.5, 0.99)
            .register(registry);

        this.trainingPublishAttempt = Counter.builder("aisentinel.distributed.training.publish.attempt")
            .description("Training candidate transport send started on worker")
            .register(registry);
        this.trainingPublishSuccess = Counter.builder("aisentinel.distributed.training.publish.success")
            .register(registry);
        this.trainingPublishFailure = Counter.builder("aisentinel.distributed.training.publish.failure")
            .register(registry);
        this.trainingPublishDropped = Counter.builder("aisentinel.distributed.training.publish.dropped")
            .description("Dropped before worker (in-flight cap)")
            .register(registry);
        this.trainingPublishSkippedSample = Counter.builder("aisentinel.distributed.training.publish.skipped_sample")
            .register(registry);
        this.trainingPublishSkippedGate = Counter.builder("aisentinel.distributed.training.publish.skipped_gate")
            .register(registry);
        this.trainingPublishExecutorRejected = Counter.builder("aisentinel.distributed.training.publish.executor_rejected")
            .register(registry);
        this.trainingPublishUnexpectedFailure = Counter.builder("aisentinel.distributed.training.publish.unexpected_failure")
            .description("Publisher threw on request thread (defensive)")
            .register(registry);
        this.trainingPublishTransport = Timer.builder("aisentinel.distributed.training.publish.transport")
            .description("Worker-side transport send wall time (Kafka or logging)")
            .publishPercentiles(0.5, 0.99)
            .register(registry);
        this.trainingPublishFailureTimeout = Counter.builder("aisentinel.distributed.training.publish.failure.timeout")
            .description("Send future timed out on worker")
            .register(registry);
        this.trainingPublishFailureSerialization = Counter.builder("aisentinel.distributed.training.publish.failure.serialization")
            .description("JSON serialization failed before send")
            .register(registry);

        this.modelRegistryRefreshAttempt = Counter.builder("aisentinel.model.registry.refresh.attempt")
            .description("Background poll for active model pointer")
            .register(registry);
        this.modelRegistryRefreshSkippedSameVersion = Counter.builder("aisentinel.model.registry.refresh.skipped_same_version")
            .register(registry);
        this.modelRegistryRefreshSuccess = Counter.builder("aisentinel.model.registry.refresh.success")
            .register(registry);
        this.modelRegistryRefreshFailure = Counter.builder("aisentinel.model.registry.refresh.failure")
            .register(registry);
        this.modelRegistryInstallSuccess = Counter.builder("aisentinel.model.registry.install.success")
            .description("Artifact validated and swapped into IsolationForestScorer")
            .register(registry);
        this.modelRegistryInstallFailure = Counter.builder("aisentinel.model.registry.install.failure")
            .description("Checksum/decode/dimension rejection")
            .register(registry);

        this.trustBaselineRedisSuccess = Counter.builder("aisentinel.identity.trust.baseline.redis.success")
            .description("Behavioral trust baseline Redis read/write succeeded")
            .register(registry);
        this.trustBaselineRedisFailure = Counter.builder("aisentinel.identity.trust.baseline.redis.failure")
            .description("Behavioral trust baseline Redis operation failed")
            .register(registry);
        this.trustBaselineRedisFallback = Counter.builder("aisentinel.identity.trust.baseline.redis.fallback")
            .description("Behavioral trust baseline used in-memory store after Redis failure")
            .register(registry);

        for (String mode : List.of("ALWAYS", "ALLOW_ONLY", "ALLOW_OR_MONITOR", "SCORE_BELOW_THRESHOLD")) {
            baselineUpdateAcceptedByMode.put(mode, Counter.builder("aisentinel.baseline.update.accepted")
                .description("Online scorer update accepted by baseline-update policy")
                .tag("policy", mode)
                .register(registry));
            baselineUpdateSkippedByMode.put(mode, Counter.builder("aisentinel.baseline.update.skipped")
                .description("Online scorer update skipped by baseline-update policy")
                .tag("policy", mode)
                .register(registry));
        }
        this.baselineUpdateAcceptedWarmup = Counter.builder("aisentinel.baseline.update.accepted.warmup")
            .description("Baseline updates accepted while statistical warmup is active")
            .register(registry);
        for (String reason : List.of("EXPLICIT")) {
            baselineRelearnByReason.put(reason, Counter.builder("aisentinel.baseline.relearn")
                .description("Statistical baseline key reset (controlled explicit relearn)")
                .tag("reason", reason)
                .register(registry));
        }
    }

    @Override
    public void recordCompositeScore(double score) {
        if (!Double.isNaN(score) && score >= 0) {
            scoreComposite.record(score);
        }
    }

    @Override
    public void recordStatisticalScore(double score) {
        if (!Double.isNaN(score) && score >= 0) {
            scoreStatistical.record(score);
        }
    }

    @Override
    public void recordIsolationForestScore(double score) {
        if (!Double.isNaN(score) && score >= 0) {
            scoreIf.record(score);
        }
    }

    @Override
    public void recordIsolationForestScoreMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return;
        }
        Counter counter = isolationForestScoreModeByName.get(mode);
        if (counter != null) {
            counter.increment();
        }
    }

    @Override
    public void recordPipelineLatencyNanos(long nanos) {
        if (nanos >= 0) {
            latencyPipeline.record(nanos, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void recordScoringLatencyNanos(long nanos) {
        if (nanos >= 0) {
            latencyScoring.record(nanos, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void recordBaselineUpdateAccepted(String policyMode, boolean warmup) {
        Counter counter = baselineUpdateAcceptedByMode.get(normalizePolicyMode(policyMode));
        if (counter != null) {
            counter.increment();
        }
        if (warmup) {
            baselineUpdateAcceptedWarmup.increment();
        }
    }

    @Override
    public void recordBaselineUpdateSkipped(String policyMode) {
        Counter counter = baselineUpdateSkippedByMode.get(normalizePolicyMode(policyMode));
        if (counter != null) {
            counter.increment();
        }
    }

    @Override
    public void recordBaselineRelearn(String reason) {
        String normalized = reason == null || reason.isBlank() ? "EXPLICIT" : reason;
        Counter counter = baselineRelearnByReason.get(normalized);
        if (counter != null) {
            counter.increment();
        }
    }

    private static String normalizePolicyMode(String policyMode) {
        if (policyMode == null || policyMode.isBlank()) {
            return "ALLOW_OR_MONITOR";
        }
        return policyMode;
    }

    @Override
    public void recordIsolationForestInferenceLatencyNanos(long nanos) {
        if (nanos >= 0) {
            latencyIf.record(nanos, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void recordPolicyAction(EnforcementAction action) {
        switch (action) {
            case ALLOW -> actionAllow.increment();
            case MONITOR -> actionMonitor.increment();
            case THROTTLE -> actionThrottle.increment();
            case BLOCK -> actionBlock.increment();
            case QUARANTINE -> actionQuarantine.increment();
        }
    }

    @Override
    public void recordFailOpen() {
        failOpen.increment();
    }

    @Override
    public void recordFailOpen(FailOpenReason reason) {
        failOpen.increment();
        if (reason != null) {
            Counter counter = failOpenByReason.get(reason.name());
            if (counter != null) {
                counter.increment();
            }
        }
    }

    @Override
    public void recordEvaluationStatuses(Collection<? extends Enum<?>> statuses) {
        if (statuses == null) {
            return;
        }
        for (Enum<?> status : statuses) {
            if (status == null) {
                continue;
            }
            Counter counter = evaluationStatusByName.get(status.name());
            if (counter != null) {
                counter.increment();
            }
        }
    }

    @Override
    public void recordNanOrNegativeScoreClamped() {
        nanClamped.increment();
    }

    @Override
    public void recordInvalidScoreRejected() {
        invalidScoreRejected.increment();
    }

    @Override
    public void recordQuarantineReleased(boolean hadLocalEntry) {
        if (hadLocalEntry) {
            quarantineReleasedHadLocal.increment();
        } else {
            quarantineReleasedMissing.increment();
        }
    }

    @Override
    public void recordDistributedQuarantineClearAttempt() {
        distributedQuarantineClearAttempt.increment();
    }

    @Override
    public void recordDistributedQuarantineClearSuccess() {
        distributedQuarantineClearSuccess.increment();
    }

    @Override
    public void recordDistributedQuarantineClearFailure() {
        distributedQuarantineClearFailure.increment();
    }

    @Override
    public void recordScoringError() {
        scoringErrors.increment();
    }

    @Override
    public void recordRetrainSuccessNanos(long nanos) {
        if (nanos >= 0) {
            modelRetrainDuration.record(nanos, TimeUnit.NANOSECONDS);
        }
        modelRetrainSuccess.increment();
    }

    @Override
    public void recordRetrainFailureNanos(long nanos) {
        if (nanos >= 0) {
            modelRetrainDuration.record(nanos, TimeUnit.NANOSECONDS);
        }
        modelRetrainFailure.increment();
    }

    @Override
    public void recordDistributedQuarantineLookup() {
        distributedQuarantineLookup.increment();
    }

    @Override
    public void recordDistributedQuarantineClusterHit() {
        distributedQuarantineClusterHit.increment();
    }

    @Override
    public void recordDistributedQuarantineCacheHit() {
        distributedQuarantineCacheHit.increment();
    }

    @Override
    public void recordDistributedQuarantineCacheMiss() {
        distributedQuarantineCacheMiss.increment();
    }

    @Override
    public void recordDistributedRedisTimeout() {
        distributedRedisTimeout.increment();
    }

    @Override
    public void recordDistributedRedisFailure() {
        distributedRedisFailure.increment();
    }

    @Override
    public void recordDistributedRedisLookupDurationNanos(long nanos) {
        if (nanos >= 0) {
            distributedRedisLookup.record(nanos, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void recordDistributedQuarantineWriteAttempt() {
        distributedQuarantineWriteAttempt.increment();
    }

    @Override
    public void recordDistributedQuarantineWriteSuccess() {
        distributedQuarantineWriteSuccess.increment();
    }

    @Override
    public void recordDistributedQuarantineWriteFailure() {
        distributedQuarantineWriteFailure.increment();
    }

    @Override
    public void recordDistributedQuarantineWriteDurationNanos(long nanos) {
        if (nanos >= 0) {
            distributedQuarantineWrite.record(nanos, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void recordDistributedQuarantineWriteSkippedExpired() {
        distributedQuarantineWriteSkippedExpired.increment();
    }

    @Override
    public void recordDistributedQuarantineWriteDropped() {
        distributedQuarantineWriteDropped.increment();
    }

    @Override
    public void recordDistributedQuarantineWriteSchedulerRejected() {
        distributedQuarantineWriteSchedulerRejected.increment();
    }

    @Override
    public void recordDistributedThrottleEvaluation() {
        distributedThrottleEval.increment();
    }

    @Override
    public void recordDistributedThrottleClusterAllow() {
        distributedThrottleAllow.increment();
    }

    @Override
    public void recordDistributedThrottleClusterReject() {
        distributedThrottleReject.increment();
    }

    @Override
    public void recordDistributedThrottleRedisTimeout() {
        distributedThrottleRedisTimeout.increment();
    }

    @Override
    public void recordDistributedThrottleRedisFailure() {
        distributedThrottleRedisFailure.increment();
    }

    @Override
    public void recordDistributedThrottleExecutorRejected() {
        distributedThrottleExecutorRejected.increment();
    }

    @Override
    public void recordDistributedThrottleEvalDurationNanos(long nanos) {
        if (nanos >= 0) {
            distributedThrottleEvalTimer.record(nanos, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void recordTrainingCandidatePublishAttempt() {
        trainingPublishAttempt.increment();
    }

    @Override
    public void recordTrainingCandidatePublishSuccess() {
        trainingPublishSuccess.increment();
    }

    @Override
    public void recordTrainingCandidatePublishFailure() {
        trainingPublishFailure.increment();
    }

    @Override
    public void recordTrainingCandidatePublishDropped() {
        trainingPublishDropped.increment();
    }

    @Override
    public void recordTrainingCandidatePublishSkippedSample() {
        trainingPublishSkippedSample.increment();
    }

    @Override
    public void recordTrainingCandidatePublishSkippedGate() {
        trainingPublishSkippedGate.increment();
    }

    @Override
    public void recordTrainingCandidatePublishExecutorRejected() {
        trainingPublishExecutorRejected.increment();
    }

    @Override
    public void recordTrainingCandidatePublishUnexpectedFailure() {
        trainingPublishUnexpectedFailure.increment();
    }

    @Override
    public void recordTrainingCandidatePublishTransportDurationNanos(long nanos) {
        if (nanos >= 0) {
            trainingPublishTransport.record(nanos, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void recordTrainingCandidatePublishFailureTimeout() {
        trainingPublishFailureTimeout.increment();
    }

    @Override
    public void recordTrainingCandidatePublishFailureSerialization() {
        trainingPublishFailureSerialization.increment();
    }

    @Override
    public void recordModelRegistryRefreshAttempt() {
        modelRegistryRefreshAttempt.increment();
    }

    @Override
    public void recordModelRegistryRefreshSkippedSameVersion() {
        modelRegistryRefreshSkippedSameVersion.increment();
    }

    @Override
    public void recordModelRegistryRefreshSuccess() {
        modelRegistryRefreshSuccess.increment();
    }

    @Override
    public void recordModelRegistryRefreshFailure() {
        modelRegistryRefreshFailure.increment();
    }

    @Override
    public void recordModelRegistryInstallSuccess() {
        modelRegistryInstallSuccess.increment();
    }

    @Override
    public void recordModelRegistryInstallFailure() {
        modelRegistryInstallFailure.increment();
    }

    @Override
    public void recordTrustBaselineRedisSuccess() {
        trustBaselineRedisSuccess.increment();
    }

    @Override
    public void recordTrustBaselineRedisFailure() {
        trustBaselineRedisFailure.increment();
    }

    @Override
    public void recordTrustBaselineRedisFallback() {
        trustBaselineRedisFallback.increment();
    }

    public Map<String, Object> scoreSummaryForActuator() {
        Map<String, Object> m = new LinkedHashMap<>();
        putSummary(m, "composite", scoreComposite);
        putSummary(m, "statistical", scoreStatistical);
        putSummary(m, "if", scoreIf);
        return m;
    }

    public Map<String, Object> latencySummaryForActuator() {
        Map<String, Object> m = new LinkedHashMap<>();
        putTimer(m, "pipeline", latencyPipeline);
        putTimer(m, "scoring", latencyScoring);
        putTimer(m, "if", latencyIf);
        return m;
    }

    public long getRetrainSuccessCount() {
        return (long) modelRetrainSuccess.count();
    }

    public long getRetrainFailureCount() {
        return (long) modelRetrainFailure.count();
    }

    public long getDistributedQuarantineLookupCount() {
        return (long) distributedQuarantineLookup.count();
    }

    public long getDistributedQuarantineClusterHitCount() {
        return (long) distributedQuarantineClusterHit.count();
    }

    public long getDistributedCacheHitCount() {
        return (long) distributedQuarantineCacheHit.count();
    }

    public long getDistributedCacheMissCount() {
        return (long) distributedQuarantineCacheMiss.count();
    }

    public long getDistributedRedisTimeoutCount() {
        return (long) distributedRedisTimeout.count();
    }

    public long getDistributedRedisFailureCount() {
        return (long) distributedRedisFailure.count();
    }

    public long getDistributedQuarantineWriteAttemptCount() {
        return (long) distributedQuarantineWriteAttempt.count();
    }

    public long getDistributedQuarantineWriteSuccessCount() {
        return (long) distributedQuarantineWriteSuccess.count();
    }

    public long getDistributedQuarantineWriteFailureCount() {
        return (long) distributedQuarantineWriteFailure.count();
    }

    public long getDistributedQuarantineWriteSkippedExpiredCount() {
        return (long) distributedQuarantineWriteSkippedExpired.count();
    }

    public long getDistributedQuarantineWriteDroppedCount() {
        return (long) distributedQuarantineWriteDropped.count();
    }

    public long getDistributedQuarantineWriteSchedulerRejectedCount() {
        return (long) distributedQuarantineWriteSchedulerRejected.count();
    }

    public long getDistributedThrottleEvaluationCount() {
        return (long) distributedThrottleEval.count();
    }

    public long getDistributedThrottleAllowCount() {
        return (long) distributedThrottleAllow.count();
    }

    public long getDistributedThrottleRejectCount() {
        return (long) distributedThrottleReject.count();
    }

    public long getDistributedThrottleRedisTimeoutCount() {
        return (long) distributedThrottleRedisTimeout.count();
    }

    public long getDistributedThrottleRedisFailureCount() {
        return (long) distributedThrottleRedisFailure.count();
    }

    public long getDistributedThrottleExecutorRejectedCount() {
        return (long) distributedThrottleExecutorRejected.count();
    }

    public Map<String, Object> distributedThrottleSummaryForActuator() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("throttleEvaluationCount", getDistributedThrottleEvaluationCount());
        m.put("throttleClusterAllowCount", getDistributedThrottleAllowCount());
        m.put("throttleClusterRejectCount", getDistributedThrottleRejectCount());
        m.put("throttleRedisTimeoutCount", getDistributedThrottleRedisTimeoutCount());
        m.put("throttleRedisFailureCount", getDistributedThrottleRedisFailureCount());
        m.put("throttleExecutorRejectedCount", getDistributedThrottleExecutorRejectedCount());
        putTimer(m, "throttleRedisEval", distributedThrottleEvalTimer);
        return m;
    }

    public long getTrainingPublishAttemptCount() {
        return (long) trainingPublishAttempt.count();
    }

    public long getTrainingPublishSuccessCount() {
        return (long) trainingPublishSuccess.count();
    }

    public long getTrainingPublishFailureCount() {
        return (long) trainingPublishFailure.count();
    }

    public long getTrainingPublishDroppedCount() {
        return (long) trainingPublishDropped.count();
    }

    public long getTrainingPublishSkippedSampleCount() {
        return (long) trainingPublishSkippedSample.count();
    }

    public long getTrainingPublishSkippedGateCount() {
        return (long) trainingPublishSkippedGate.count();
    }

    public long getTrainingPublishExecutorRejectedCount() {
        return (long) trainingPublishExecutorRejected.count();
    }

    public long getTrainingPublishFailureTimeoutCount() {
        return (long) trainingPublishFailureTimeout.count();
    }

    public long getTrainingPublishFailureSerializationCount() {
        return (long) trainingPublishFailureSerialization.count();
    }

    public Map<String, Object> distributedTrainingPublishSummaryForActuator() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("publishAttemptCount", getTrainingPublishAttemptCount());
        m.put("publishSuccessCount", getTrainingPublishSuccessCount());
        m.put("publishFailureCount", getTrainingPublishFailureCount());
        m.put("publishFailureTimeoutCount", getTrainingPublishFailureTimeoutCount());
        m.put("publishFailureSerializationCount", getTrainingPublishFailureSerializationCount());
        m.put("publishDroppedCount", getTrainingPublishDroppedCount());
        m.put("publishSkippedSampleCount", getTrainingPublishSkippedSampleCount());
        m.put("publishSkippedGateCount", getTrainingPublishSkippedGateCount());
        m.put("publishExecutorRejectedCount", getTrainingPublishExecutorRejectedCount());
        putTimer(m, "publishTransport", trainingPublishTransport);
        return m;
    }

    public Map<String, Object> modelRegistrySummaryForActuator() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("refreshAttemptCount", (long) modelRegistryRefreshAttempt.count());
        m.put("refreshSkippedSameVersionCount", (long) modelRegistryRefreshSkippedSameVersion.count());
        m.put("refreshSuccessCount", (long) modelRegistryRefreshSuccess.count());
        m.put("refreshFailureCount", (long) modelRegistryRefreshFailure.count());
        m.put("installSuccessCount", (long) modelRegistryInstallSuccess.count());
        m.put("installFailureCount", (long) modelRegistryInstallFailure.count());
        return m;
    }

    public Map<String, Object> distributedSummaryForActuator() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("quarantineLookupCount", getDistributedQuarantineLookupCount());
        m.put("quarantineClusterHitCount", getDistributedQuarantineClusterHitCount());
        m.put("cacheHitCount", getDistributedCacheHitCount());
        m.put("cacheMissCount", getDistributedCacheMissCount());
        m.put("redisTimeoutCount", getDistributedRedisTimeoutCount());
        m.put("redisFailureCount", getDistributedRedisFailureCount());
        putTimer(m, "redisLookup", distributedRedisLookup);
        m.put("quarantineWriteAttemptCount", getDistributedQuarantineWriteAttemptCount());
        m.put("quarantineWriteSuccessCount", getDistributedQuarantineWriteSuccessCount());
        m.put("quarantineWriteFailureCount", getDistributedQuarantineWriteFailureCount());
        m.put("quarantineWriteSkippedExpiredCount", getDistributedQuarantineWriteSkippedExpiredCount());
        m.put("quarantineWriteDroppedCount", getDistributedQuarantineWriteDroppedCount());
        m.put("quarantineWriteSchedulerRejectedCount", getDistributedQuarantineWriteSchedulerRejectedCount());
        putTimer(m, "quarantineWrite", distributedQuarantineWrite);
        return m;
    }

    private static void putSummary(Map<String, Object> out, String key, DistributionSummary summary) {
        var snap = summary.takeSnapshot();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("count", snap.count());
        if (snap.count() > 0) {
            row.put("mean", snap.mean());
            for (var pv : snap.percentileValues()) {
                double p = pv.percentile();
                if (Math.abs(p - 0.5) < 1e-6) row.put("p50", pv.value());
                else if (Math.abs(p - 0.9) < 1e-6) row.put("p90", pv.value());
                else if (Math.abs(p - 0.99) < 1e-6) row.put("p99", pv.value());
            }
        }
        out.put(key, row);
    }

    private static void putTimer(Map<String, Object> out, String key, Timer timer) {
        var snap = timer.takeSnapshot();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("count", snap.count());
        if (snap.count() > 0) {
            for (var pv : snap.percentileValues()) {
                double p = pv.percentile();
                if (Math.abs(p - 0.5) < 1e-6) row.put("p50Ms", pv.value(TimeUnit.MILLISECONDS));
                else if (Math.abs(p - 0.99) < 1e-6) row.put("p99Ms", pv.value(TimeUnit.MILLISECONDS));
            }
        }
        out.put(key, row);
    }
}
