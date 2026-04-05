package io.aisentinel.autoconfigure.distributed;

/**
 * Redis key layout for cluster throttle counters (fixed time windows).
 * <p>
 * Format: {@code {keyPrefix}:{tenant}:th:{bucketId}:{enforcementKey}}
 */
public final class DistributedThrottleKeyBuilder {

    private DistributedThrottleKeyBuilder() {
    }

    public static String redisKey(String keyPrefix, String tenantId, long bucketId, String enforcementKey) {
        String p = keyPrefix != null && !keyPrefix.isBlank() ? keyPrefix.trim() : "aisentinel";
        String t = tenantId != null && !tenantId.isBlank() ? tenantId.trim() : "default";
        String k = enforcementKey != null ? enforcementKey : "";
        return p + ":" + t + ":th:" + bucketId + ":" + k;
    }
}
