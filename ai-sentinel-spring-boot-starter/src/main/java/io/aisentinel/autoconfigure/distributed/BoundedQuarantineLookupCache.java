package io.aisentinel.autoconfigure.distributed;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded TTL cache for Redis quarantine GET results.
 * <p>
 * Eviction when at capacity removes a batch of arbitrary entries (not strict LRU). Entries also expire by wall-clock
 * {@code expiresAtMillis} derived from positive/negative TTL. Clock skew between nodes can make “until” values
 * appear slightly early/late; enforcement still compares {@code until} to local {@code System.currentTimeMillis()}.
 */
public final class BoundedQuarantineLookupCache {

    private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();
    private final int maxEntries;
    private final long positiveTtlMs;
    private final long negativeTtlMs;

    public BoundedQuarantineLookupCache(int maxEntries, long positiveTtlMs, long negativeTtlMs) {
        this.maxEntries = Math.max(1, maxEntries);
        this.positiveTtlMs = Math.max(1L, positiveTtlMs);
        this.negativeTtlMs = Math.max(1L, Math.min(negativeTtlMs, positiveTtlMs));
    }

    /**
     * @return {@link Optional#empty()} cache miss; else {@link Optional} of {@link OptionalLong} (empty = cached negative).
     */
    public Optional<OptionalLong> lookup(String redisKey, long nowMillis) {
        Entry e = map.get(redisKey);
        if (e == null) {
            return Optional.empty();
        }
        if (nowMillis > e.expiresAtMillis) {
            map.remove(redisKey, e);
            return Optional.empty();
        }
        return Optional.of(e.untilMillis);
    }

    public void putPositive(String redisKey, long untilMillis, long nowMillis) {
        evictIfNeeded();
        long exp = nowMillis + positiveTtlMs;
        map.put(redisKey, new Entry(OptionalLong.of(untilMillis), exp));
    }

    public void putNegative(String redisKey, long nowMillis) {
        evictIfNeeded();
        long exp = nowMillis + negativeTtlMs;
        map.put(redisKey, new Entry(OptionalLong.empty(), exp));
    }

    public int size() {
        return map.size();
    }

    private void evictIfNeeded() {
        while (map.size() >= maxEntries) {
            Iterator<Map.Entry<String, Entry>> it = map.entrySet().iterator();
            if (!it.hasNext()) {
                return;
            }
            int toRemove = Math.max(1, maxEntries / 10);
            for (int i = 0; i < toRemove && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
    }

    private static final class Entry {
        final OptionalLong untilMillis;
        final long expiresAtMillis;

        Entry(OptionalLong untilMillis, long expiresAtMillis) {
            this.untilMillis = untilMillis;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
