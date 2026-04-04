package io.aisentinel.distributed.quarantine;

/**
 * Publishes quarantine decisions to shared storage (e.g. Redis) for other nodes. Implementations must not
 * throw through to the servlet thread; failures are logged and counted elsewhere.
 * <p>
 * <strong>TODO Phase 5.3:</strong> wire from {@link io.aisentinel.core.enforcement.CompositeEnforcementHandler}
 * apply QUARANTINE path (async or fire-and-forget).
 */
public interface ClusterQuarantineWriter {

    /**
     * @param tenantId logical tenant
     * @param enforcementKey identity-scoped key (same as reader)
     * @param untilEpochMillis when quarantine ends
     */
    void publishQuarantine(String tenantId, String enforcementKey, long untilEpochMillis);
}
