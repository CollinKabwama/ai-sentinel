package io.aisentinel.autoconfigure.distributed.throttle;

import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.autoconfigure.distributed.DistributedThrottleKeyBuilder;
import io.aisentinel.autoconfigure.distributed.DistributedThrottleStatus;
import io.aisentinel.core.metrics.SentinelMetrics;
import io.aisentinel.distributed.throttle.ClusterThrottleStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Redis fixed-window counter for cluster throttle (THROTTLE enforcement path only). Fail-open on timeout or errors.
 */
@Slf4j
public final class RedisClusterThrottleStore implements ClusterThrottleStore, DisposableBean {

    private static final int MIN_IN_FLIGHT = 1;
    private static final int MAX_IN_FLIGHT_CAP = 50_000;
    private static final int MAX_REQUESTS_CAP = 10_000_000;

    private static final DefaultRedisScript<Long> INCR_EXPIRE = new DefaultRedisScript<>();

    static {
        INCR_EXPIRE.setScriptText(
            "local n = redis.call('INCR', KEYS[1])\n"
                + "if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end\n"
                + "return n");
        INCR_EXPIRE.setResultType(Long.class);
    }

    private final StringRedisTemplate redisTemplate;
    private final SentinelProperties properties;
    private final SentinelMetrics metrics;
    private final DistributedThrottleStatus status;
    private final ExecutorService redisExecutor;
    private final Semaphore inFlight;

    public RedisClusterThrottleStore(StringRedisTemplate redisTemplate,
                                     SentinelProperties properties,
                                     SentinelMetrics metrics,
                                     DistributedThrottleStatus status) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.metrics = metrics != null ? metrics : SentinelMetrics.NOOP;
        this.status = status != null ? status : new DistributedThrottleStatus();
        this.redisExecutor = Executors.newVirtualThreadPerTaskExecutor();
        int configuredInFlight = properties.getDistributed().getClusterThrottleMaxInFlight();
        int cap = Math.max(MIN_IN_FLIGHT, Math.min(configuredInFlight, MAX_IN_FLIGHT_CAP));
        if (cap != configuredInFlight) {
            log.info("ai.sentinel clusterThrottleMaxInFlight clamped from {} to {}", configuredInFlight, cap);
        }
        this.inFlight = new Semaphore(cap);
    }

    @Override
    public boolean tryAcquire(String tenantId, String enforcementKey) {
        metrics.recordDistributedThrottleEvaluation();
        if (!inFlight.tryAcquire()) {
            metrics.recordDistributedThrottleExecutorRejected();
            status.recordInflightExhausted();
            return true;
        }

        long windowMs = windowMillis();
        if (windowMs <= 0) {
            windowMs = 1000L;
        }
        long bucketId = System.currentTimeMillis() / windowMs;
        int max = maxPerWindowEffective();
        var redis = properties.getDistributed().getRedis();
        String redisKey = DistributedThrottleKeyBuilder.redisKey(
            redis.getKeyPrefix(),
            tenantId != null && !tenantId.isBlank() ? tenantId : "default",
            bucketId,
            enforcementKey);
        long ttlSec = Math.max(1L, (windowMs + 999) / 1000 + 1);

        long ms = evalTimeoutMillis();
        long t0 = System.nanoTime();
        CompletableFuture<Long> fut;
        try {
            fut = CompletableFuture.supplyAsync(() -> {
                try {
                    return redisTemplate.execute(INCR_EXPIRE, Collections.singletonList(redisKey), Long.toString(ttlSec));
                } finally {
                    inFlight.release();
                }
            }, redisExecutor);
        } catch (RejectedExecutionException ex) {
            inFlight.release();
            metrics.recordDistributedThrottleExecutorRejected();
            status.recordExecutorSubmitRejected(ex);
            metrics.recordDistributedThrottleEvalDurationNanos(System.nanoTime() - t0);
            return true;
        }
        try {
            Long count = fut.get(ms, TimeUnit.MILLISECONDS);
            status.recordRedisSuccess();
            metrics.recordDistributedThrottleEvalDurationNanos(System.nanoTime() - t0);
            if (count == null) {
                return true;
            }
            if (count > max) {
                metrics.recordDistributedThrottleClusterReject();
                return false;
            }
            metrics.recordDistributedThrottleClusterAllow();
            return true;
        } catch (TimeoutException e) {
            metrics.recordDistributedThrottleRedisTimeout();
            status.recordRedisTimeout(e);
            log.debug("Cluster throttle Redis eval timed out for key={}", redisKey);
            metrics.recordDistributedThrottleEvalDurationNanos(System.nanoTime() - t0);
            return true;
        } catch (Exception e) {
            metrics.recordDistributedThrottleRedisFailure();
            status.recordRedisExecutionFailure(e);
            log.debug("Cluster throttle Redis eval failed: {}", e.toString());
            metrics.recordDistributedThrottleEvalDurationNanos(System.nanoTime() - t0);
            return true;
        }
    }

    private long windowMillis() {
        Duration w = properties.getDistributed().getClusterThrottleWindow();
        return w != null ? w.toMillis() : 1000L;
    }

    private int maxPerWindowEffective() {
        int configured = properties.getDistributed().getClusterThrottleMaxRequestsPerWindow();
        int effective = Math.max(1, Math.min(configured, MAX_REQUESTS_CAP));
        if (effective != configured) {
            log.info("ai.sentinel clusterThrottleMaxRequestsPerWindow clamped from {} to {}", configured, effective);
        }
        return effective;
    }

    private long evalTimeoutMillis() {
        Duration ct = properties.getDistributed().getClusterThrottleTimeout();
        if (ct != null && !ct.isNegative() && ct.toMillis() > 0) {
            return ct.toMillis();
        }
        Duration lt = properties.getDistributed().getRedis().getLookupTimeout();
        long ms = lt != null && !lt.isNegative() ? lt.toMillis() : 50L;
        return ms > 0 ? ms : 50L;
    }

    @Override
    public void destroy() {
        redisExecutor.shutdown();
        try {
            if (!redisExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                redisExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            redisExecutor.shutdownNow();
        }
    }
}
