package io.aisentinel.autoconfigure.distributed.quarantine;

import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.autoconfigure.distributed.BoundedQuarantineLookupCache;
import io.aisentinel.autoconfigure.distributed.DistributedQuarantineKeyBuilder;
import io.aisentinel.autoconfigure.distributed.DistributedQuarantineStatus;
import io.aisentinel.core.metrics.SentinelMetrics;
import io.aisentinel.distributed.quarantine.ClusterQuarantineReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Redis-backed {@link ClusterQuarantineReader} with bounded local cache, bounded Redis wait, and fail-open behavior.
 * <p>
 * <strong>Timeouts:</strong> The caller waits at most {@link SentinelProperties.Distributed.Redis#getLookupTimeout()} on
 * a {@link CompletableFuture}. That does not cancel in-flight Lettuce I/O; configure
 * {@code spring.data.redis.timeout} (and client resources) so network/command timeouts align with this budget.
 * See {@code PHASE5_DISTRIBUTED_DESIGN.md} (timeouts section).
 */
@Slf4j
public final class RedisClusterQuarantineReader implements ClusterQuarantineReader, DisposableBean {

    private final StringRedisTemplate redisTemplate;
    private final SentinelProperties properties;
    private final SentinelMetrics metrics;
    private final DistributedQuarantineStatus status;
    private final boolean cacheEnabled;
    private final BoundedQuarantineLookupCache cache;
    /** Per-bean executor; shut down in {@link #destroy()} to avoid leaking threads in tests/restarts. */
    private final ExecutorService redisLookupExecutor;

    public RedisClusterQuarantineReader(StringRedisTemplate redisTemplate,
                                        SentinelProperties properties,
                                        SentinelMetrics metrics,
                                        DistributedQuarantineStatus status) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.metrics = metrics != null ? metrics : SentinelMetrics.NOOP;
        this.status = status != null ? status : new DistributedQuarantineStatus();
        this.redisLookupExecutor = Executors.newVirtualThreadPerTaskExecutor();
        var d = properties.getDistributed();
        var cacheProps = d.getCache();
        this.cacheEnabled = cacheProps.isEnabled();
        if (this.cacheEnabled) {
            int max = cacheProps.getMaxEntries() > 0 ? cacheProps.getMaxEntries() : 10_000;
            long posTtl = cacheProps.getTtl() != null ? Math.max(1L, cacheProps.getTtl().toMillis()) : 2000L;
            long negTtl = resolveNegativeTtlMillis(cacheProps.getNegativeTtl(), posTtl);
            this.cache = new BoundedQuarantineLookupCache(max, posTtl, negTtl);
        } else {
            this.cache = null;
        }
    }

    static long resolveNegativeTtlMillis(Duration configuredNegativeTtl, long positiveTtlMillis) {
        if (configuredNegativeTtl != null) {
            return Math.max(1L, configuredNegativeTtl.toMillis());
        }
        return Math.max(100L, Math.min(positiveTtlMillis / 2, 2000L));
    }

    @Override
    public void destroy() {
        redisLookupExecutor.shutdown();
        try {
            if (!redisLookupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                redisLookupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            redisLookupExecutor.shutdownNow();
        }
    }

    @Override
    public OptionalLong quarantineUntil(String tenantId, String enforcementKey) {
        metrics.recordDistributedQuarantineLookup();
        long now = System.currentTimeMillis();
        var redis = properties.getDistributed().getRedis();
        String redisKey = DistributedQuarantineKeyBuilder.redisKey(redis.getKeyPrefix(), tenantId, enforcementKey);

        if (cacheEnabled) {
            Optional<OptionalLong> cached = cache.lookup(redisKey, now);
            if (cached.isPresent()) {
                metrics.recordDistributedQuarantineCacheHit();
                OptionalLong v = cached.get();
                if (v.isEmpty()) {
                    return OptionalLong.empty();
                }
                long until = v.getAsLong();
                if (until > now) {
                    metrics.recordDistributedQuarantineClusterHit();
                }
                return until > now ? v : OptionalLong.empty();
            }
            metrics.recordDistributedQuarantineCacheMiss();
        }

        OptionalLong fromRedis = fetchWithTimeout(redisKey);
        status.setApproximateCacheSize(cacheEnabled ? cache.size() : 0);

        if (fromRedis.isEmpty()) {
            if (cacheEnabled) {
                cache.putNegative(redisKey, now);
            }
            return OptionalLong.empty();
        }
        long until = fromRedis.getAsLong();
        if (until <= now) {
            if (cacheEnabled) {
                cache.putNegative(redisKey, now);
            }
            return OptionalLong.empty();
        }
        metrics.recordDistributedQuarantineClusterHit();
        if (cacheEnabled) {
            cache.putPositive(redisKey, until, now);
        }
        return OptionalLong.of(until);
    }

    /**
     * Runs Redis GET off the request thread with a future timeout. The future timeout returns control to the caller;
     * it does not reliably interrupt blocking Lettuce work—use Redis client timeouts as the primary bound.
     */
    private OptionalLong fetchWithTimeout(String redisKey) {
        Duration timeout = properties.getDistributed().getRedis().getLookupTimeout();
        long ms = timeout != null && !timeout.isNegative() ? timeout.toMillis() : 50L;
        if (ms <= 0) {
            ms = 50L;
        }
        long t0 = System.nanoTime();
        CompletableFuture<String> fut;
        try {
            fut = CompletableFuture.supplyAsync(() -> redisTemplate.opsForValue().get(redisKey), redisLookupExecutor);
        } catch (RejectedExecutionException ex) {
            metrics.recordDistributedRedisFailure();
            status.recordRedisError("executor_rejected", ex);
            log.debug("Redis quarantine lookup rejected: {}", ex.toString());
            metrics.recordDistributedRedisLookupDurationNanos(System.nanoTime() - t0);
            return OptionalLong.empty();
        }
        try {
            String raw = fut.get(ms, TimeUnit.MILLISECONDS);
            status.recordRedisSuccess();
            if (raw == null || raw.isBlank()) {
                return OptionalLong.empty();
            }
            long until = Long.parseLong(raw.trim());
            return OptionalLong.of(until);
        } catch (TimeoutException e) {
            // Do not cancel(true): it does not stop Lettuce I/O; client/network timeout must be set separately.
            metrics.recordDistributedRedisTimeout();
            status.recordRedisError("timeout", e);
            log.debug("Redis quarantine lookup timed out for key={}", redisKey);
            return OptionalLong.empty();
        } catch (NumberFormatException e) {
            metrics.recordDistributedRedisFailure();
            status.recordRedisError("parse", e);
            log.debug("Redis quarantine value not a long for key={}", redisKey);
            return OptionalLong.empty();
        } catch (Exception e) {
            metrics.recordDistributedRedisFailure();
            status.recordRedisError("redis", e);
            log.debug("Redis quarantine lookup failed: {}", e.toString());
            return OptionalLong.empty();
        } finally {
            metrics.recordDistributedRedisLookupDurationNanos(System.nanoTime() - t0);
        }
    }
}
