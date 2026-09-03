package dev.aisentinel.core.contract;

import java.util.Objects;

/**
 * Transport-safe risk factor row. Codes are stable enum names; contribution/confidence are finite.
 */
public record ContractRiskFactor(
    String code,
    String category,
    String severity,
    double contribution,
    double confidence,
    String evidenceRef,
    String explanation,
    String source
) {
    public ContractRiskFactor {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(severity, "severity");
        if (!Double.isFinite(contribution) || contribution < 0.0 || contribution > 1.0) {
            throw new EvaluationContractException("contribution must be finite in [0,1]");
        }
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new EvaluationContractException("confidence must be finite in [0,1]");
        }
        evidenceRef = evidenceRef == null ? "" : evidenceRef;
        explanation = explanation == null ? "" : explanation;
        source = source == null ? "" : source;
    }
}
