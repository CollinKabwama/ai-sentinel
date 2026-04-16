package io.aisentinel.core.identity.trust;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local-only bounded memory of recent per-key behavioral snapshots for identity trust.
 * Thread-safe; not distributed. Use {@link BehavioralBaselineStore} to inject a distributed implementation.
 */
public final class IdentityBehavioralBaselineStore implements BehavioralBaselineStore {

    private final long ttlMs;
    private final int maxKeys;
    private final Map<String, BehavioralBaselineEntry> map = new ConcurrentHashMap<>();

    public IdentityBehavioralBaselineStore(Duration ttl, int maxKeys) {
        this.ttlMs = Math.max(1_000L, ttl.toMillis());
        this.maxKeys = Math.max(1, maxKeys);
    }

    /**
     * @return previous entry for this key before this update, or {@code null} if none
     */
    @Override
    public BehavioralBaselineEntry updateAndGetPrevious(String key, String endpoint, long headerFingerprintHash, int ipBucket,
                                              long nowMillis) {
        pruneExpired(nowMillis);
        if (map.size() >= maxKeys) {
            evictOneOldest();
        }
        final BehavioralBaselineEntry[] previous = new BehavioralBaselineEntry[1];
        map.compute(key, (k, old) -> {
            previous[0] = old;
            return BehavioralBaselineMerge.merge(old, endpoint, headerFingerprintHash, ipBucket, nowMillis);
        });
        return previous[0];
    }

    public int size() {
        return map.size();
    }

    private void pruneExpired(long nowMillis) {
        long cutoff = nowMillis - ttlMs;
        map.entrySet().removeIf(e -> e.getValue().lastSeenMs < cutoff);
    }

    /**
     * Removes the coldest entry only if its {@link BehavioralBaselineEntry#lastSeenMs} is still unchanged since we chose it,
     * so a concurrent refresh of that key is not mistaken for eviction.
     */
    private void evictOneOldest() {
        String victim = null;
        long victimLastSeen = Long.MAX_VALUE;
        for (Map.Entry<String, BehavioralBaselineEntry> e : map.entrySet()) {
            long t = e.getValue().lastSeenMs;
            if (t < victimLastSeen) {
                victimLastSeen = t;
                victim = e.getKey();
            }
        }
        if (victim == null) {
            return;
        }
        final long expectedLastSeen = victimLastSeen;
        map.computeIfPresent(victim, (k, entry) ->
            entry.lastSeenMs == expectedLastSeen ? null : entry);
    }
}
