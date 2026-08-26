package dev.aisentinel.distributed.quarantine;

/**
 * Publishes quarantine decisions to shared storage (e.g. Redis) for other nodes.
 * <p>
 * <strong>Contract:</strong> {@link #publishQuarantine} and {@link #clearQuarantine} must return quickly and
 * must not throw to callers (typically the request / operator thread). Implementations may perform I/O
 * asynchronously; failures are fail-open for the cluster and should be reflected in metrics / status.
 */
public interface ClusterQuarantineWriter {

    /**
     * @param tenantId logical tenant (same segment as {@link ClusterQuarantineReader})
     * @param enforcementKey same shape as local enforcement / reader (identity or identity|endpoint)
     * @param untilEpochMillis when quarantine ends (wall clock), aligned with local {@code quarantinedUntil}
     */
    void publishQuarantine(String tenantId, String enforcementKey, long untilEpochMillis);

    /**
     * Best-effort delete of a previously published quarantine key for the exact tenant + enforcement key.
     * Idempotent: missing keys are success. Must not wildcard-delete. Default is a no-op for source
     * compatibility with existing writer implementations.
     */
    default void clearQuarantine(String tenantId, String enforcementKey) {
    }
}
