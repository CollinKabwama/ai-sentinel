package io.aisentinel.core.identity.trust;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local-only bounded memory of recent per-key behavioral snapshots for identity trust (Phase 2).
 * Thread-safe; not distributed.
 */
public final class IdentityBehavioralBaselineStore {

    private final long ttlMs;
    private final int maxKeys;
    private final Map<String, BaselineEntry> map = new ConcurrentHashMap<>();

    public IdentityBehavioralBaselineStore(Duration ttl, int maxKeys) {
        this.ttlMs = Math.max(1_000L, ttl.toMillis());
        this.maxKeys = Math.max(1, maxKeys);
    }

    /**
     * @return previous entry for this key before this update, or {@code null} if none
     */
    public BaselineEntry updateAndGetPrevious(String key, String endpoint, long headerFingerprintHash, int ipBucket,
                                              long nowMillis) {
        pruneExpired(nowMillis);
        if (map.size() >= maxKeys) {
            evictOneOldest();
        }
        final BaselineEntry[] previous = new BaselineEntry[1];
        map.compute(key, (k, old) -> {
            previous[0] = old;
            BaselineEntry n = new BaselineEntry();
            n.lastSeenMs = nowMillis;
            n.observationCount = old == null ? 1L : old.observationCount + 1;
            n.lastEndpoint = endpoint != null ? endpoint : "";
            n.lastHeaderFingerprintHash = headerFingerprintHash;
            n.lastIpBucket = ipBucket;
            return n;
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
     * Removes the coldest entry only if its {@link BaselineEntry#lastSeenMs} is still unchanged since we chose it,
     * so a concurrent refresh of that key is not mistaken for eviction.
     */
    private void evictOneOldest() {
        String victim = null;
        long victimLastSeen = Long.MAX_VALUE;
        for (Map.Entry<String, BaselineEntry> e : map.entrySet()) {
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

    /** Mutable snapshot stored per baseline key. */
    public static final class BaselineEntry {
        long lastSeenMs;
        long observationCount;
        String lastEndpoint = "";
        long lastHeaderFingerprintHash;
        int lastIpBucket = Integer.MIN_VALUE;
    }
}
