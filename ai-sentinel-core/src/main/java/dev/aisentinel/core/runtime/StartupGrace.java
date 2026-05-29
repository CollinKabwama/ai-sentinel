package dev.aisentinel.core.runtime;

/**
 * Indicates whether the JVM is still within a configurable post-startup window where enforcement may be relaxed.
 * <p>
 * Read on the request path during pipeline processing. {@link #isGraceActive} should be cheap (typically a time
 * comparison). When grace is active, the pipeline typically applies {@link dev.aisentinel.core.enforcement.CompositeEnforcementHandler}
 * behavior in a monitor-oriented way (see {@link dev.aisentinel.core.SentinelPipeline}).
 */
public interface StartupGrace {

    /** Never grants grace (always {@code false}). */
    StartupGrace NEVER = () -> false;

    /** {@code true} while the grace window has not elapsed; {@code false} after. */
    boolean isGraceActive();
}
