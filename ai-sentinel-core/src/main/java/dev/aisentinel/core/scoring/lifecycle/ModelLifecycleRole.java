package dev.aisentinel.core.scoring.lifecycle;

/**
 * Lifecycle designation role for a model identity.
 * <p>
 * Designation is governance state only.
 * {@code PROMOTED != PRODUCTION DEPLOYED}
 */
public enum ModelLifecycleRole {
    /** Explicit initial or retained champion designation. */
    CHAMPION,
    /** Explicitly designated challenger (not automatic from ACCEPTED/SHADOW). */
    CHALLENGER,
    /** Previous champion retained after promotion or rollback. */
    SUPERSEDED,
    /** Challenger that was rolled back from champion designation. */
    ROLLED_BACK
}
