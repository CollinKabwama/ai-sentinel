package io.aisentinel.autoconfigure.metrics;

import io.aisentinel.core.policy.EnforcementAction;
import io.aisentinel.core.metrics.SentinelMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.LinkedHashMap;
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
    private final Counter nanClamped;
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
            .description("Scorer score + update latency")
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

        this.failOpen = Counter.builder("aisentinel.failopen.count").register(registry);
        this.nanClamped = Counter.builder("aisentinel.nan.clamped.count").register(registry);
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
    public void recordNanOrNegativeScoreClamped() {
        nanClamped.increment();
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
