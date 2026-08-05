package dev.aisentinel.distributed.throttle;

/**
 * Default store when cluster throttle is off: always allows (local throttle still applies).
 * Thread-safe singleton.
 */
public enum NoopClusterThrottleStore implements ClusterThrottleStore {
    INSTANCE;

    @Override
    public boolean tryAcquire(String tenantId, String enforcementKey) {
        return true;
    }
}
