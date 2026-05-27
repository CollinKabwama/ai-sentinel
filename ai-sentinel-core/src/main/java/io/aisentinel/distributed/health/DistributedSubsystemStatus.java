package io.aisentinel.distributed.health;

/**
 * Coarse health for actuator and metrics for optional distributed subsystems.
 */
public enum DistributedSubsystemStatus {
    /** Not configured or disabled. */
    NOT_CONFIGURED,
    OK,
    DEGRADED,
    UNAVAILABLE
}
