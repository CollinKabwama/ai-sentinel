package io.aisentinel.core.identity.trust;

/**
 * Shared merge logic for in-memory and distributed behavioral baseline updates.
 */
public final class BehavioralBaselineMerge {

    private BehavioralBaselineMerge() {}

    /**
     * @param previous entry before this observation, or {@code null}
     * @return new entry after applying this observation (does not mutate {@code previous})
     */
    public static BehavioralBaselineEntry merge(BehavioralBaselineEntry previous,
                                                String endpoint,
                                                long headerFingerprintHash,
                                                int ipBucket,
                                                long nowMillis) {
        BehavioralBaselineEntry n = new BehavioralBaselineEntry();
        n.lastSeenMs = nowMillis;
        n.observationCount = previous == null ? 1L : previous.observationCount + 1;
        n.lastEndpoint = endpoint != null ? endpoint : "";
        n.lastHeaderFingerprintHash = headerFingerprintHash;
        n.lastIpBucket = ipBucket;
        return n;
    }
}
