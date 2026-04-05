package io.aisentinel.autoconfigure.distributed;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable snapshot for distributed throttle Redis health (actuator / ops).
 */
public final class DistributedThrottleStatus {

    private final AtomicLong lastRedisErrorTimeMillis = new AtomicLong(0);
    private volatile String lastRedisErrorSummary = "";
    private volatile boolean redisThrottleDegraded;

    public void recordRedisSuccess() {
        redisThrottleDegraded = false;
    }

    public void recordRedisError(String kind, Throwable t) {
        redisThrottleDegraded = true;
        lastRedisErrorTimeMillis.set(System.currentTimeMillis());
        String msg = t != null ? t.getClass().getSimpleName() : "unknown";
        if (kind != null && !kind.isBlank()) {
            lastRedisErrorSummary = kind + ": " + msg;
        } else {
            lastRedisErrorSummary = msg;
        }
        if (lastRedisErrorSummary.length() > 256) {
            lastRedisErrorSummary = lastRedisErrorSummary.substring(0, 256);
        }
    }

    /** In-flight semaphore saturated; fail-open path. */
    public void recordInflightExhausted() {
        recordRedisError("inflight_exhausted", null);
    }

    /** Virtual thread executor rejected the async throttle task (before Redis). */
    public void recordExecutorSubmitRejected(Throwable t) {
        recordRedisError("executor_rejected", t);
    }

    public void recordRedisTimeout(Throwable t) {
        recordRedisError("redis_timeout", t);
    }

    public void recordRedisExecutionFailure(Throwable t) {
        recordRedisError("redis_failure", t);
    }

    public long getLastRedisErrorTimeMillis() {
        return lastRedisErrorTimeMillis.get();
    }

    public String getLastRedisErrorSummary() {
        return lastRedisErrorSummary;
    }

    public boolean isRedisThrottleDegraded() {
        return redisThrottleDegraded;
    }
}
