package io.aisentinel.autoconfigure.distributed;

/**
 * Redis key layout for shared quarantine visibility (read and write paths).
 * <p>
 * Format: {@code {keyPrefix}:{tenant}:q:{enforcementKey}}
 * <ul>
 *   <li>{@code keyPrefix} — from {@code ai.sentinel.distributed.redis.key-prefix} (default {@code aisentinel})</li>
 *   <li>{@code tenant} — logical tenant (from {@code ai.sentinel.distributed.tenant-id} or the
 *       {@code tenantId} argument to {@link io.aisentinel.distributed.quarantine.ClusterQuarantineReader})</li>
 *   <li>{@code enforcementKey} — same shape as local enforcement:
 *       identity hash, or {@code identity|endpoint} per {@link io.aisentinel.core.enforcement.EnforcementScope}</li>
 * </ul>
 * Values should be the quarantine-until epoch millis as a decimal string; writers must set a Redis TTL so keys expire.
 */
public final class DistributedQuarantineKeyBuilder {

    private DistributedQuarantineKeyBuilder() {
    }

    public static String redisKey(String keyPrefix, String tenantId, String enforcementKey) {
        String p = keyPrefix != null && !keyPrefix.isBlank() ? keyPrefix.trim() : "aisentinel";
        String t = tenantId != null && !tenantId.isBlank() ? tenantId.trim() : "default";
        String k = enforcementKey != null ? enforcementKey : "";
        return p + ":" + t + ":q:" + k;
    }
}
