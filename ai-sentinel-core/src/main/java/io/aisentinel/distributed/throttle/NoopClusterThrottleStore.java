package io.aisentinel.distributed.throttle;

/**
 * Default no-op: never applies distributed throttling.
 */
public enum NoopClusterThrottleStore implements ClusterThrottleStore {
    INSTANCE;

    @Override
    public boolean tryAcquire(String tenantId, String enforcementKey) {
        return true;
    }
}
