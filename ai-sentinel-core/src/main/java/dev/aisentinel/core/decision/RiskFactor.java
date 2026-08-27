package dev.aisentinel.core.decision;

import java.util.Objects;

/**
 * One structured contributing factor for a {@link RiskDecision}.
 * <p>
 * {@code contribution} is relative weight among factors on this decision in {@code [0, 1]}.
 * {@code confidence} is evidence certainty in {@code [0, 1]}. Neither selects enforcement.
 *
 * @param code          closed vocabulary code
 * @param category      coarse category
 * @param severity      qualitative importance of this evidence
 * @param contribution  relative weight among factors ({@code [0, 1]}, finite)
 * @param confidence    evidence certainty ({@code [0, 1]}, finite)
 * @param evidenceRef   machine-stable evidence key (feature name, status name, or signal key); never raw identity
 * @param explanation   short deterministic human-readable summary
 * @param source        subsystem that produced the evidence (e.g. {@code statistical}, {@code trust}, {@code status})
 */
public record RiskFactor(
    RiskFactorCode code,
    RiskFactorCategory category,
    RiskFactorSeverity severity,
    double contribution,
    double confidence,
    String evidenceRef,
    String explanation,
    String source
) {
    public RiskFactor {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(severity, "severity");
        if (!Double.isFinite(contribution) || contribution < 0.0 || contribution > 1.0) {
            throw new IllegalArgumentException("contribution must be finite in [0, 1]");
        }
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be finite in [0, 1]");
        }
        evidenceRef = evidenceRef == null ? "" : evidenceRef;
        explanation = explanation == null ? "" : explanation;
        source = source == null ? "" : source;
    }

    RiskFactor withContribution(double newContribution) {
        return new RiskFactor(code, category, severity, newContribution, confidence, evidenceRef, explanation, source);
    }
}
