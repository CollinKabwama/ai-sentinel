package io.aisentinel.autoconfigure.distributed.quarantine;

import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.autoconfigure.distributed.DistributedQuarantineKeyBuilder;
import io.aisentinel.autoconfigure.distributed.DistributedQuarantineStatus;
import io.aisentinel.core.metrics.SentinelMetrics;
import io.aisentinel.distributed.quarantine.ClusterQuarantineWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed {@link ClusterQuarantineWriter}. Publishes {@code untilEpochMillis} as a decimal string with a
 * Redis TTL matching remaining quarantine time (aligned with the read path value format).
 * <p>
 * Writes run on a per-bean virtual-thread executor so the servlet thread returns immediately after scheduling.
 * Failures are fail-open for the cluster (key may be missing); local quarantine is unaffected.
 * <p>
 * In-flight work is bounded by a semaphore ({@code maxInFlightQuarantineWrites}); excess publishes are dropped
 * with a metric and do not block the caller.
 * <p>
 * <strong>TTL vs wall clock:</strong> TTL is computed as {@code untilEpochMillis - now} at execution time (not
 * enqueue time), so queueing delay slightly reduces Redis lifetime. The stored value is still the absolute
 * {@code until}; readers compare with local time, so a key that expires slightly early is fail-open (less strict).
 */
@Slf4j
public final class RedisClusterQuarantineWriter implements ClusterQuarantineWriter, DisposableBean {

    /** Safety cap to avoid absurd EX values if misconfigured durations slip through. */
    private static final long MAX_REDIS_TTL_MS = 10L * 365 * 24 * 60 * 60 * 1000;

    private static final int MIN_IN_FLIGHT = 1;
    private static final int MAX_IN_FLIGHT_CAP = 50_000;

    private final StringRedisTemplate redisTemplate;
    private final SentinelProperties properties;
    private final SentinelMetrics metrics;
    private final DistributedQuarantineStatus status;
    private final ExecutorService redisWriteExecutor;
    private final Semaphore inFlight;

    public RedisClusterQuarantineWriter(StringRedisTemplate redisTemplate,
                                        SentinelProperties properties,
                                        SentinelMetrics metrics,
                                        DistributedQuarantineStatus status) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.metrics = metrics != null ? metrics : SentinelMetrics.NOOP;
        this.status = status != null ? status : new DistributedQuarantineStatus();
        this.redisWriteExecutor = Executors.newVirtualThreadPerTaskExecutor();
        int configured = properties.getDistributed().getRedis().getMaxInFlightQuarantineWrites();
        int cap = Math.max(MIN_IN_FLIGHT, Math.min(configured, MAX_IN_FLIGHT_CAP));
        this.inFlight = new Semaphore(cap);
    }

    @Override
    public void destroy() {
        redisWriteExecutor.shutdown();
        try {
            if (!redisWriteExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                redisWriteExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            redisWriteExecutor.shutdownNow();
        }
    }

    @Override
    public void publishQuarantine(String tenantId, String enforcementKey, long untilEpochMillis) {
        metrics.recordDistributedQuarantineWriteAttempt();
        if (!inFlight.tryAcquire()) {
            metrics.recordDistributedQuarantineWriteDropped();
            log.debug("Cluster quarantine write dropped (in-flight cap reached)");
            return;
        }
        String t = tenantId != null && !tenantId.isBlank() ? tenantId : "default";
        String key = enforcementKey != null ? enforcementKey : "";
        try {
            redisWriteExecutor.execute(() -> {
                try {
                    writeToRedis(t, key, untilEpochMillis);
                } finally {
                    inFlight.release();
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.release();
            metrics.recordDistributedQuarantineWriteSchedulerRejected();
            status.recordWriteError("executor_rejected", e);
            log.warn("Cluster quarantine write task rejected: {}", e.toString());
        }
    }

    private void writeToRedis(String tenantId, String enforcementKey, long untilEpochMillis) {
        long start = System.nanoTime();
        try {
            long now = System.currentTimeMillis();
            long remainingMs = untilEpochMillis - now;
            if (remainingMs <= 0) {
                metrics.recordDistributedQuarantineWriteSkippedExpired();
                return;
            }
            long ttlMs = Math.min(remainingMs, MAX_REDIS_TTL_MS);
            long ttlSeconds = Math.max(1L, (ttlMs + 999) / 1000);
            var redis = properties.getDistributed().getRedis();
            String redisKey = DistributedQuarantineKeyBuilder.redisKey(redis.getKeyPrefix(), tenantId, enforcementKey);
            String value = Long.toString(untilEpochMillis);
            redisTemplate.opsForValue().set(redisKey, value, Duration.ofSeconds(ttlSeconds));
            status.recordWriteSuccess();
            metrics.recordDistributedQuarantineWriteSuccess();
        } catch (Exception e) {
            metrics.recordDistributedQuarantineWriteFailure();
            status.recordWriteError("set", e);
            log.warn("Cluster quarantine Redis SET failed for tenant={}: {}", tenantId, e.toString());
        } finally {
            metrics.recordDistributedQuarantineWriteDurationNanos(System.nanoTime() - start);
        }
    }
}
