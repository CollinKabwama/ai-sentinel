package dev.aisentinel.benchmark.deployment;

import dev.aisentinel.core.metrics.FailOpenReason;
import dev.aisentinel.core.metrics.SentinelMetrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class BenchmarkSentinelMetrics implements SentinelMetrics {

    private final AtomicLong remoteAttempts = new AtomicLong();
    private final AtomicLong remoteLatencyNanos = new AtomicLong();
    private final AtomicLong trustRedisSuccess = new AtomicLong();
    private final AtomicLong trustRedisFailure = new AtomicLong();
    private final AtomicLong trustRedisFallback = new AtomicLong();
    private final AtomicLong failOpenCount = new AtomicLong();
    private final AtomicLong remoteLocalFallback = new AtomicLong();
    private final Map<String, AtomicLong> remoteOutcomes = new ConcurrentHashMap<>();
    private final ThreadLocal<String> lastRemoteOutcome = new ThreadLocal<>();

    @Override
    public void recordRemoteEvaluationAttempt() {
        remoteAttempts.incrementAndGet();
    }

    @Override
    public void recordRemoteEvaluationOutcome(String outcome) {
        remoteOutcomes.computeIfAbsent(outcome, ignored -> new AtomicLong()).incrementAndGet();
        lastRemoteOutcome.set(outcome);
    }

    @Override
    public void recordRemoteEvaluationLatencyNanos(long nanos) {
        remoteLatencyNanos.addAndGet(nanos);
    }

    @Override
    public void recordTrustBaselineRedisSuccess() {
        trustRedisSuccess.incrementAndGet();
    }

    @Override
    public void recordTrustBaselineRedisFailure() {
        trustRedisFailure.incrementAndGet();
    }

    @Override
    public void recordTrustBaselineRedisFallback() {
        trustRedisFallback.incrementAndGet();
    }

    @Override
    public void recordFailOpen(FailOpenReason reason) {
        failOpenCount.incrementAndGet();
    }

    @Override
    public void recordRemoteLocalFallback() {
        remoteLocalFallback.incrementAndGet();
    }

    String consumeLastRemoteOutcome() {
        String outcome = lastRemoteOutcome.get();
        lastRemoteOutcome.remove();
        return outcome;
    }

    long remoteAttempts() {
        return remoteAttempts.get();
    }

    long trustRedisFailure() {
        return trustRedisFailure.get();
    }

    long trustRedisFallback() {
        return trustRedisFallback.get();
    }

    long failOpenCount() {
        return failOpenCount.get();
    }

    long remoteLocalFallback() {
        return remoteLocalFallback.get();
    }

    long remoteOutcomeCount(String outcome) {
        AtomicLong count = remoteOutcomes.get(outcome);
        return count == null ? 0L : count.get();
    }

    void reset() {
        remoteAttempts.set(0L);
        remoteLatencyNanos.set(0L);
        trustRedisSuccess.set(0L);
        trustRedisFailure.set(0L);
        trustRedisFallback.set(0L);
        failOpenCount.set(0L);
        remoteLocalFallback.set(0L);
        remoteOutcomes.clear();
        lastRemoteOutcome.remove();
    }
}
