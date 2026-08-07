package dev.aisentinel.core.store;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory store for rolling request counts using time-bucketed counters.
 * Uses 10-second buckets with TTL eviction and max size cap.
 * <p>
 * Idle keys older than TTL are removed on read/write paths so store lifetime aligns with the
 * statistical scorer's baseline TTL when both are configured from {@code ai.sentinel.baseline-ttl}.
 */
public final class BaselineStore {

    private static final long BUCKET_MS = 10_000L;
    /** Throttles the O(n) idle-expiry scan; bounds worst-case staleness of expiry to about this long past TTL. */
    private static final long EXPIRE_SWEEP_INTERVAL_MS = 1000L;

    private final long ttlMs;
    private final int maxKeys;
    private final Map<String, BucketChain> store = new ConcurrentHashMap<>();
    private final AtomicLong nextExpireSweepMs = new AtomicLong(0);
    private final AtomicLong expireSweepCount = new AtomicLong(0);

    public BaselineStore(Duration ttl, int maxKeys) {
        this.ttlMs = Math.max(1L, ttl.toMillis());
        this.maxKeys = Math.max(1, maxKeys);
    }

    /** Configured rolling-window / idle TTL in milliseconds. */
    public long ttlMs() {
        return ttlMs;
    }

    /** Configured max retained keys. */
    public int maxKeys() {
        return maxKeys;
    }

    /** Current key cardinality (tests / ops). */
    public int size() {
        return store.size();
    }

    /** Number of idle-expiry sweeps actually performed (tests / ops). */
    public long expireSweepCount() {
        return expireSweepCount.get();
    }

    /** Configured idle-expiry sweep interval in milliseconds. */
    public long expireSweepIntervalMs() {
        return EXPIRE_SWEEP_INTERVAL_MS;
    }

    /**
     * Increment request count for the given key and return current count in the active window.
     */
    public int incrementAndGet(String key) {
        long now = System.currentTimeMillis();
        expireIdle(now);
        long bucketId = now / BUCKET_MS;

        BucketChain chain = store.computeIfAbsent(key, k -> new BucketChain());
        if (store.size() > maxKeys) {
            evictOldest(now);
        }

        chain.add(bucketId, now);
        return chain.countWithinWindow(now, ttlMs);
    }

    /**
     * Get current count without incrementing.
     */
    public int get(String key) {
        long now = System.currentTimeMillis();
        expireIdle(now);
        BucketChain chain = store.get(key);
        return chain != null ? chain.countWithinWindow(now, ttlMs) : 0;
    }

    /**
     * Drops keys with no access within TTL (runs even under maxKeys), throttled to at most one
     * full-map scan per {@link #EXPIRE_SWEEP_INTERVAL_MS} regardless of request volume — see
     * {@link dev.aisentinel.core.scoring.StatisticalScorer#expireIdle} for the matching rationale.
     */
    void expireIdle(long now) {
        long next = nextExpireSweepMs.get();
        if (now < next) {
            return;
        }
        if (!nextExpireSweepMs.compareAndSet(next, now + EXPIRE_SWEEP_INTERVAL_MS)) {
            return;
        }
        expireSweepCount.incrementAndGet();
        long cutoff = now - ttlMs;
        store.entrySet().removeIf(e -> {
            BucketChain c = e.getValue();
            return c.lastAccessMs() < cutoff || c.isEmpty();
        });
    }

    private void evictOldest(long now) {
        long cutoff = now - ttlMs;
        store.entrySet().removeIf(e -> {
            BucketChain c = e.getValue();
            return c.lastAccessMs() < cutoff || c.isEmpty();
        });
        while (store.size() > maxKeys) {
            String victim = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<String, BucketChain> e : store.entrySet()) {
                long la = e.getValue().lastAccessMs();
                if (la < oldest) {
                    oldest = la;
                    victim = e.getKey();
                }
            }
            if (victim == null) break;
            store.remove(victim);
        }
    }

    private static final class BucketChain {
        private final Map<Long, AtomicInteger> buckets = new ConcurrentHashMap<>();
        private final AtomicLong lastAccess = new AtomicLong(System.currentTimeMillis());

        void add(long bucketId, long now) {
            lastAccess.set(now);
            buckets.computeIfAbsent(bucketId, k -> new AtomicInteger(0)).incrementAndGet();
        }

        int countWithinWindow(long now, long ttlMs) {
            long cutoff = now - ttlMs;
            long minBucket = cutoff / BUCKET_MS;
            int sum = 0;
            buckets.entrySet().removeIf(e -> e.getKey() < minBucket);
            for (Map.Entry<Long, AtomicInteger> e : buckets.entrySet()) {
                sum += e.getValue().get();
            }
            return sum;
        }

        long lastAccessMs() {
            return lastAccess.get();
        }

        boolean isEmpty() {
            return buckets.isEmpty();
        }
    }
}
