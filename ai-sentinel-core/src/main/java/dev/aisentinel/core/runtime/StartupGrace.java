package dev.aisentinel.core.runtime;

/**
 * Indicates whether the JVM is still within a configurable post-startup window where enforcement may be relaxed.
 * <p>
 * Read on the request path inside {@link dev.aisentinel.core.decision.SentinelDecisionEngine}.
 * {@link #isGraceActive()} should be cheap (typically a time comparison). When grace is active, the engine forces
 * {@link dev.aisentinel.core.policy.EnforcementAction#MONITOR} before quarantine overrides are applied.
 */
public interface StartupGrace {

    /** Never grants grace (always {@code false}). */
    StartupGrace NEVER = () -> false;

    /** {@code true} while the grace window has not elapsed; {@code false} after. */
    boolean isGraceActive();
}
