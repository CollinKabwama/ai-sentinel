package dev.aisentinel.core.scoring.lifecycle;

/**
 * Caller-supplied aggregate of observational shadow scoring.
 * <p>
 * Core does not own durable shadow history. Callers may summarize sink-collected
 * observations for eligibility evidence.
 * <p>
 * {@code SHADOW DISAGREEMENT != GROUND TRUTH}<br>
 * This summary must not encode TP/TN/FP/FN, precision, recall, or F1.
 */
public final class ShadowObservationSummary {

    private final long attemptedShadowExecutions;
    private final long validComparisonCount;
    private final long candidateInvalidScoreCount;
    private final long candidateExecutionFailureCount;
    private final long agreementCount;
    private final long disagreementCount;
    private final double meanAbsoluteScoreDelta;

    public ShadowObservationSummary(
        long attemptedShadowExecutions,
        long validComparisonCount,
        long candidateInvalidScoreCount,
        long candidateExecutionFailureCount,
        long agreementCount,
        long disagreementCount,
        double meanAbsoluteScoreDelta
    ) {
        if (attemptedShadowExecutions < 0
            || validComparisonCount < 0
            || candidateInvalidScoreCount < 0
            || candidateExecutionFailureCount < 0
            || agreementCount < 0
            || disagreementCount < 0) {
            throw new IllegalArgumentException("shadow summary counts must be >= 0");
        }
        if (agreementCount + disagreementCount > validComparisonCount) {
            throw new IllegalArgumentException("agreement + disagreement cannot exceed validComparisonCount");
        }
        if (validComparisonCount > attemptedShadowExecutions) {
            throw new IllegalArgumentException("validComparisonCount cannot exceed attemptedShadowExecutions");
        }
        if (candidateInvalidScoreCount > attemptedShadowExecutions
            || candidateExecutionFailureCount > attemptedShadowExecutions) {
            throw new IllegalArgumentException("failure counts cannot exceed attemptedShadowExecutions");
        }
        if (validComparisonCount + candidateInvalidScoreCount + candidateExecutionFailureCount
            > attemptedShadowExecutions) {
            throw new IllegalArgumentException(
                "valid comparisons plus candidate failures cannot exceed attemptedShadowExecutions");
        }
        if (!Double.isFinite(meanAbsoluteScoreDelta) || meanAbsoluteScoreDelta < 0.0) {
            throw new IllegalArgumentException("meanAbsoluteScoreDelta must be finite and >= 0");
        }
        this.attemptedShadowExecutions = attemptedShadowExecutions;
        this.validComparisonCount = validComparisonCount;
        this.candidateInvalidScoreCount = candidateInvalidScoreCount;
        this.candidateExecutionFailureCount = candidateExecutionFailureCount;
        this.agreementCount = agreementCount;
        this.disagreementCount = disagreementCount;
        this.meanAbsoluteScoreDelta = meanAbsoluteScoreDelta;
    }

    public long attemptedShadowExecutions() {
        return attemptedShadowExecutions;
    }

    public long validComparisonCount() {
        return validComparisonCount;
    }

    public long candidateInvalidScoreCount() {
        return candidateInvalidScoreCount;
    }

    public long candidateExecutionFailureCount() {
        return candidateExecutionFailureCount;
    }

    public long agreementCount() {
        return agreementCount;
    }

    public long disagreementCount() {
        return disagreementCount;
    }

    public double meanAbsoluteScoreDelta() {
        return meanAbsoluteScoreDelta;
    }
}
