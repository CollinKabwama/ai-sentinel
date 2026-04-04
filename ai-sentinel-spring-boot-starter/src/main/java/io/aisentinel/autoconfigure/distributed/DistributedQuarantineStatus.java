package io.aisentinel.autoconfigure.distributed;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable snapshot for actuator / ops (updated by {@link io.aisentinel.autoconfigure.distributed.quarantine.RedisClusterQuarantineReader}).
 */
public final class DistributedQuarantineStatus {

    private final AtomicLong lastRedisErrorTimeMillis = new AtomicLong(0);
    private final AtomicInteger approximateCacheSize = new AtomicInteger(0);
    private volatile String lastRedisErrorSummary = "";
    private volatile boolean redisReaderDegraded;

    public void recordRedisSuccess() {
        redisReaderDegraded = false;
    }

    public void recordRedisError(String kind, Throwable t) {
        redisReaderDegraded = true;
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

    public void setApproximateCacheSize(int size) {
        approximateCacheSize.set(Math.max(0, size));
    }

    public long getLastRedisErrorTimeMillis() {
        return lastRedisErrorTimeMillis.get();
    }

    public String getLastRedisErrorSummary() {
        return lastRedisErrorSummary;
    }

    public boolean isRedisReaderDegraded() {
        return redisReaderDegraded;
    }

    public int getApproximateCacheSize() {
        return approximateCacheSize.get();
    }
}
