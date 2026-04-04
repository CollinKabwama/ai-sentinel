package io.aisentinel.distributed.quarantine;

/**
 * Publishes quarantine decisions to shared storage (e.g. Redis) for other nodes.
 * <p>
 * <strong>Contract:</strong> {@link #publishQuarantine} must return quickly and must not throw to callers
 * (typically the request thread). Implementations may perform I/O asynchronously; failures are fail-open
 * for the cluster (other nodes may not see the key) and should be reflected in metrics / status.
 */
public interface ClusterQuarantineWriter {

    /**
     * @param tenantId logical tenant (same segment as {@link ClusterQuarantineReader})
     * @param enforcementKey same shape as local enforcement / reader (identity or identity|endpoint)
     * @param untilEpochMillis when quarantine ends (wall clock), aligned with local {@code quarantinedUntil}
     */
    void publishQuarantine(String tenantId, String enforcementKey, long untilEpochMillis);
}
