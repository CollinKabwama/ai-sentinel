package io.aisentinel.core.identity.trust;

/**
 * Mutable per-key behavioral snapshot for identity trust baselines (in-memory or Redis-backed stores).
 */
public final class BehavioralBaselineEntry {

    public long lastSeenMs;
    public long observationCount;
    public String lastEndpoint = "";
    public long lastHeaderFingerprintHash;
    public int lastIpBucket = Integer.MIN_VALUE;
}
