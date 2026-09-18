package dev.aisentinel.core.scoring.shadow;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Explicit opt-in configuration for observational shadow scoring.
 * <p>
 * Default is {@linkplain #disabled() disabled}. Enabling requires an
 * {@link AcceptedCandidateIdentity}; acceptance evidence alone never activates
 * shadow execution.
 * <p>
 * {@code CANDIDATE CONFIGURATION != SHADOW EXECUTION CONFIGURATION}<br>
 * {@code ACCEPTANCE != AUTOMATIC SHADOW ENABLEMENT}
 */
public final class ShadowScoringConfiguration {

    private final boolean enabled;
    private final AcceptedCandidateIdentity acceptedIdentity;
    private final OptionalDouble diagnosticAnomalyThreshold;

    private ShadowScoringConfiguration(
        boolean enabled,
        AcceptedCandidateIdentity acceptedIdentity,
        OptionalDouble diagnosticAnomalyThreshold
    ) {
        this.enabled = enabled;
        this.acceptedIdentity = acceptedIdentity;
        this.diagnosticAnomalyThreshold = diagnosticAnomalyThreshold == null
            ? OptionalDouble.empty()
            : diagnosticAnomalyThreshold;
        if (enabled && acceptedIdentity == null) {
            throw new IllegalArgumentException(
                "enabled shadow scoring requires AcceptedCandidateIdentity");
        }
        if (this.diagnosticAnomalyThreshold.isPresent()) {
            double threshold = this.diagnosticAnomalyThreshold.getAsDouble();
            if (Double.isNaN(threshold) || Double.isInfinite(threshold) || threshold < 0.0 || threshold > 1.0) {
                throw new IllegalArgumentException(
                    "diagnosticAnomalyThreshold must be a finite value in [0.0, 1.0]");
            }
        }
    }

    /** Shadow disabled — candidate is never invoked. */
    public static ShadowScoringConfiguration disabled() {
        return new ShadowScoringConfiguration(false, null, OptionalDouble.empty());
    }

    /**
     * Explicitly enables shadow scoring for the accepted candidate identity.
     *
     * @param acceptedIdentity               identity that must match the bound candidate
     * @param diagnosticAnomalyThreshold     optional diagnostic threshold for observational
     *                                       classification agreement; not a production policy threshold
     */
    public static ShadowScoringConfiguration enabled(
        AcceptedCandidateIdentity acceptedIdentity,
        OptionalDouble diagnosticAnomalyThreshold
    ) {
        return new ShadowScoringConfiguration(
            true,
            Objects.requireNonNull(acceptedIdentity, "acceptedIdentity"),
            diagnosticAnomalyThreshold
        );
    }

    public static ShadowScoringConfiguration enabled(AcceptedCandidateIdentity acceptedIdentity) {
        return enabled(acceptedIdentity, OptionalDouble.empty());
    }

    public boolean enabled() {
        return enabled;
    }

    public AcceptedCandidateIdentity acceptedIdentity() {
        return acceptedIdentity;
    }

    /**
     * Diagnostic-only threshold for {@link ShadowClassificationAgreement}.
     * Never applied to production policy or enforcement.
     */
    public OptionalDouble diagnosticAnomalyThreshold() {
        return diagnosticAnomalyThreshold;
    }
}
