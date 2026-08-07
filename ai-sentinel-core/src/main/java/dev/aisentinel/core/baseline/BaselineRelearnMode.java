package dev.aisentinel.core.baseline;

/**
 * Controls whether statistical baselines may be reset after gated learning freezes an identity.
 * <p>
 * Default is {@link #DISABLED}: gated contamination protection stays in force with no reset path.
 * {@link #EXPLICIT_ONLY} allows a deliberate operator {@link BaselineLifecycle#reset(String, String)}.
 * <p>
 * Automatic skip-triggered relearn was removed: the same elevated traffic that forced a reset could
 * immediately train post-reset warmup and defeat gated-learning contamination protection.
 */
public enum BaselineRelearnMode {

    /** No operator reset through {@link BaselineLifecycle}. */
    DISABLED,

    /**
     * Operator may call {@link BaselineLifecycle#reset(String, String)}; no automatic reset.
     * Subsequent traffic trains the replacement baseline — invoke only when that traffic is expected
     * to be legitimate.
     */
    EXPLICIT_ONLY
}
