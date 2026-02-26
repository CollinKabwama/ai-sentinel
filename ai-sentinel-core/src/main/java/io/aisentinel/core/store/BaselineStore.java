package io.aisentinel.core.store;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory store for rolling request counts using time-bucketed counters.
 * Uses 10-second buckets with TTL eviction and max size cap.
 */
public final class BaselineStore {

    private static final long BUCKET_MS = 10_000L;

    private final long ttlMs;
    private final int maxKeys;
    private final Map<String, BucketChain> store = new ConcurrentHashMap<>();

    public BaselineStore(Duration ttl, int maxKeys) {
        this.ttlMs = ttl.toMillis();
        this.maxKeys = Math.max(1, maxKeys);
    }

    /**
     * Increment request count for the given key and return current count in the active window.
     */
    public int incrementAndGet(String key) {
        long now = System.currentTimeMillis();
        long bucketId = now / BUCKET_MS;

        BucketChain chain = store.computeIfAbsent(key, k -> new BucketChain());
        if (store.size() > maxKeys) {
            evictOldest();
        }

        chain.add(bucketId, now);
        return chain.countWithinWindow(now, ttlMs);
    }

    /**
     * Get current count without incrementing.
     */
    public int get(String key) {
        long now = System.currentTimeMillis();
        BucketChain chain = store.get(key);
        return chain != null ? chain.countWithinWindow(now, ttlMs) : 0;
    }

    private void evictOldest() {
        long cutoff = System.currentTimeMillis() - ttlMs;
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
