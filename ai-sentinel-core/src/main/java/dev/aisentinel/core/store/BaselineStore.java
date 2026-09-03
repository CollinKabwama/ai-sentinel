package dev.aisentinel.core.store;

import dev.aisentinel.core.model.IdentityEndpointKey;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * In-memory store for rolling request counts using time-bucketed counters.
 * Uses 10-second buckets; the returned value is the sum of counts for buckets overlapping the
 * configured TTL window (a rolling count, not a normalized per-second rate).
 * <p>
 * Idle keys older than TTL are removed on read/write paths so store lifetime aligns with the
 * statistical scorer's baseline TTL when both are configured from {@code ai.sentinel.baseline-ttl}.
 * <p>
 * Capacity eviction is serialized so concurrent writers do not each perform a full-map scan when
 * {@code maxKeys} is exceeded. Per-key {@link BucketChain} state is guarded so rolling-window
 * prune and count cannot race with increments.
 */
public final class BaselineStore {

    private static final long BUCKET_MS = 10_000L;
    /** Throttles the O(n) idle-expiry scan; bounds worst-case staleness of expiry to about this long past TTL. */
    private static final long EXPIRE_SWEEP_INTERVAL_MS = 1000L;

    private final long ttlMs;
    private final int maxKeys;
    private final LongSupplier clock;
    private final Map<IdentityEndpointKey, BucketChain> store = new ConcurrentHashMap<>();
    private final AtomicLong nextExpireSweepMs = new AtomicLong(0);
    private final AtomicLong expireSweepCount = new AtomicLong(0);
    /** Serializes capacity eviction so concurrent over-capacity inserts do not stampede O(n) scans. */
    private final Object evictionLock = new Object();

    public BaselineStore(Duration ttl, int maxKeys) {
        this(ttl, maxKeys, System::currentTimeMillis);
    }

    /**
     * @param clock injectable clock (milliseconds since epoch) for deterministic window/TTL tests
     */
    public BaselineStore(Duration ttl, int maxKeys, LongSupplier clock) {
        this.ttlMs = Math.max(1L, ttl.toMillis());
        this.maxKeys = Math.max(1, maxKeys);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Fixed bucket width in milliseconds (10 seconds). */
    public static long bucketMs() {
        return BUCKET_MS;
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
     * Increment request count for the given key and return the rolling count within the active TTL window.
     */
    public int incrementAndGet(String key) {
        return incrementAndGet(IdentityEndpointKey.fromStorageKey(key));
    }

    public int incrementAndGet(IdentityEndpointKey key) {
        long now = clock.getAsLong();
        expireIdle(now);
        long bucketId = now / BUCKET_MS;

        // Publish and touch atomically: a computeIfAbsent + later add left an empty
        // lastAccess=0 chain visible to expireIdle/evictOldest, which could remove it and
        // orphan the local reference (lost increments under concurrency).
        BucketChain chain = store.compute(key, (k, existing) -> {
            BucketChain c = existing != null ? existing : new BucketChain();
            c.add(bucketId, now);
            return c;
        });
        if (store.size() > maxKeys) {
            evictOldest(now);
        }
        return chain.countWithinWindow(now, ttlMs);
    }

    /**
     * Get current rolling count without incrementing.
     */
    public int get(String key) {
        return get(IdentityEndpointKey.fromStorageKey(key));
    }

    public int get(IdentityEndpointKey key) {
        long now = clock.getAsLong();
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

    /**
     * Brings cardinality back to {@code maxKeys}. Serialized so concurrent over-capacity inserts
     * share one eviction pass instead of each scanning the full map. Still O(n) per excess key in
     * the worst case, but excess is typically 1 and concurrent stampede cost is eliminated.
     */
    private void evictOldest(long now) {
        if (store.size() <= maxKeys) {
            return;
        }
        synchronized (evictionLock) {
            if (store.size() <= maxKeys) {
                return;
            }
            long cutoff = now - ttlMs;
            store.entrySet().removeIf(e -> {
                BucketChain c = e.getValue();
                return c.lastAccessMs() < cutoff || c.isEmpty();
            });
            int staleVictimRetries = 0;
            while (store.size() > maxKeys) {
                IdentityEndpointKey victim = null;
                long oldest = Long.MAX_VALUE;
                for (Map.Entry<IdentityEndpointKey, BucketChain> e : store.entrySet()) {
                    long la = e.getValue().lastAccessMs();
                    if (la < oldest) {
                        oldest = la;
                        victim = e.getKey();
                    }
                }
                if (victim == null) {
                    break;
                }
                // Re-check immediately before removal: the O(n) scan above can take long enough
                // for a concurrent request to touch the chosen victim, advancing its lastAccessMs
                // past what the scan observed. Removing it anyway would discard a live key's
                // accumulated rolling-window history. This narrows the race window from the full
                // scan duration to this single re-check; it does not require holding the victim's
                // own per-chain lock, since lastAccessMs is a lock-free atomic read.
                // Bounded retries: guarantee eventual capacity compliance even if a key is touched
                // on every scan (pathological continuous same-key contention) rather than retrying
                // indefinitely.
                BucketChain victimChain = store.get(victim);
                if (victimChain != null && victimChain.lastAccessMs() > oldest && staleVictimRetries < 8) {
                    staleVictimRetries++;
                    continue;
                }
                store.remove(victim);
            }
        }
    }

    /**
     * Per-key rolling buckets. Mutations and window counts are synchronized so prune and sum cannot
     * interleave with {@link #add} on the same chain. Uses a plain {@link HashMap} because the lock
     * already serializes access for this key.
     */
    private static final class BucketChain {
        private final Map<Long, Integer> buckets = new HashMap<>();
        private final AtomicLong lastAccess = new AtomicLong(0);

        synchronized void add(long bucketId, long now) {
            lastAccess.set(now);
            buckets.merge(bucketId, 1, Integer::sum);
        }

        synchronized int countWithinWindow(long now, long ttlMs) {
            long minBucket = (now - ttlMs) / BUCKET_MS;
            int sum = 0;
            var it = buckets.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, Integer> e = it.next();
                if (e.getKey() < minBucket) {
                    it.remove();
                } else {
                    sum += e.getValue();
                }
            }
            return sum;
        }

        long lastAccessMs() {
            return lastAccess.get();
        }

        synchronized boolean isEmpty() {
            return buckets.isEmpty();
        }
    }
}
