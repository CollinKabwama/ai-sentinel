package io.aisentinel.distributed.health;

/**
 * Coarse health for actuator / metrics (Phase 5 observability).
 */
public enum DistributedSubsystemStatus {
    /** Not configured or disabled. */
    NOT_CONFIGURED,
    OK,
    DEGRADED,
    UNAVAILABLE
}
