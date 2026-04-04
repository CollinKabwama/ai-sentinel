package io.aisentinel.distributed.quarantine;

import java.util.OptionalLong;

/**
 * Read-only view of cluster-wide quarantine state (e.g. Redis). Implementations must be safe to call from
 * the request path: bounded latency, fail-open on error.
 * <p>
 * <strong>Fail-open:</strong> return {@link OptionalLong#empty()} when the identity is not quarantined
 * <em>or</em> when the backing store is unavailable — callers merge with local quarantine using OR logic;
 * an empty result must not expand quarantine beyond local state.
 */
public interface ClusterQuarantineReader {

    /**
     * @param tenantId logical tenant (prefix for shared keys)
     * @param enforcementKey same key shape as {@link io.aisentinel.core.enforcement.CompositeEnforcementHandler}
     *        uses internally (identity or identity|endpoint per {@link io.aisentinel.core.enforcement.EnforcementScope})
     * @return millis-since-epoch when quarantine lifts, or empty if not quarantined / unknown / error
     */
    OptionalLong quarantineUntil(String tenantId, String enforcementKey);
}
