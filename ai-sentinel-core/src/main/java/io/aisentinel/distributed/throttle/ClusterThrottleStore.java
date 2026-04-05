package io.aisentinel.distributed.throttle;

/**
 * Optional cluster-wide throttle coordination for high-risk traffic. Used only on the THROTTLE enforcement path
 * (see {@link io.aisentinel.core.enforcement.CompositeEnforcementHandler#tryAcquireThrottlePermit}).
 * <p>
 * Implementations must be safe on the request thread: bounded wait, fail-open on Redis errors.
 */
public interface ClusterThrottleStore {

    /**
     * Attempt to consume one unit of cluster-wide throttle budget for the enforcement key.
     *
     * @param tenantId      logical tenant (same as distributed quarantine)
     * @param enforcementKey identity hash or identity|endpoint per {@link io.aisentinel.core.enforcement.EnforcementScope}
     * @return {@code true} if the caller may proceed (permit granted or fail-open); {@code false} if the cluster-wide
     * window is exhausted for this key
     */
    boolean tryAcquire(String tenantId, String enforcementKey);
}
