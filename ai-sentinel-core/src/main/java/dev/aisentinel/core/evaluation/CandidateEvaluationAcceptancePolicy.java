package dev.aisentinel.core.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Explicit engineering criteria applied to already-produced candidate evaluation metrics.
 * <p>
 * The policy does not inspect labels, does not affect scoring or replay, and does not
 * grant production authority.
 * <p>
 * {@code ACCEPTANCE POLICY != PRODUCTION POLICY}<br>
 * {@code ACCEPTANCE THRESHOLD != PRODUCTION THRESHOLD}
 */
public record CandidateEvaluationAcceptancePolicy(
    Optional<Long> minimumEvaluableObservations,
    Optional<Long> maximumExcludedObservations,
    Optional<Double> minimumPrecision,
    Optional<Double> minimumRecall,
    Optional<Double> minimumF1,
    Optional<Double> maximumFalsePositiveRate,
    Optional<Double> maximumFalseNegativeRate
) {
    public CandidateEvaluationAcceptancePolicy {
        minimumEvaluableObservations = copyLong(minimumEvaluableObservations, "minimumEvaluableObservations");
        maximumExcludedObservations = copyLong(maximumExcludedObservations, "maximumExcludedObservations");
        minimumPrecision = copyRatio(minimumPrecision, "minimumPrecision");
        minimumRecall = copyRatio(minimumRecall, "minimumRecall");
        minimumF1 = copyRatio(minimumF1, "minimumF1");
        maximumFalsePositiveRate = copyRatio(maximumFalsePositiveRate, "maximumFalsePositiveRate");
        maximumFalseNegativeRate = copyRatio(maximumFalseNegativeRate, "maximumFalseNegativeRate");
        if (minimumEvaluableObservations.isEmpty()
            && maximumExcludedObservations.isEmpty()
            && minimumPrecision.isEmpty()
            && minimumRecall.isEmpty()
            && minimumF1.isEmpty()
            && maximumFalsePositiveRate.isEmpty()
            && maximumFalseNegativeRate.isEmpty()) {
            throw new IllegalArgumentException("acceptance policy must configure at least one criterion");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Assess already-computed metrics. Does not inspect labels or mutate scores.
     */
    public CandidateEvaluationAcceptanceAssessment assess(DetectionEvaluationMetrics metrics) {
        DetectionEvaluationMetrics safe = Objects.requireNonNull(metrics, "metrics");
        List<CandidateEvaluationAcceptanceIssue> issues = new ArrayList<>();
        minimumEvaluableObservations.ifPresent(required -> {
            if (safe.evaluablePredictionCount() < required) {
                issues.add(countIssue(
                    CandidateEvaluationAcceptanceIssueCode.MINIMUM_EVALUABLE_OBSERVATIONS_NOT_MET,
                    safe.evaluablePredictionCount(),
                    required,
                    "evaluable observations " + safe.evaluablePredictionCount()
                        + " are below required minimum " + required
                ));
            }
        });
        maximumExcludedObservations.ifPresent(required -> {
            if (safe.excludedPredictionCount() > required) {
                issues.add(countIssue(
                    CandidateEvaluationAcceptanceIssueCode.MAXIMUM_EXCLUDED_OBSERVATIONS_EXCEEDED,
                    safe.excludedPredictionCount(),
                    required,
                    "excluded observations " + safe.excludedPredictionCount()
                        + " exceed required maximum " + required
                ));
            }
        });
        checkMinimumRatio(
            issues,
            CandidateEvaluationAcceptanceIssueCode.MINIMUM_PRECISION_NOT_MET,
            minimumPrecision,
            safe.metrics().precision(),
            "precision"
        );
        checkMinimumRatio(
            issues,
            CandidateEvaluationAcceptanceIssueCode.MINIMUM_RECALL_NOT_MET,
            minimumRecall,
            safe.metrics().recall(),
            "recall"
        );
        checkMinimumRatio(
            issues,
            CandidateEvaluationAcceptanceIssueCode.MINIMUM_F1_NOT_MET,
            minimumF1,
            safe.metrics().f1(),
            "f1"
        );
        checkMaximumRatio(
            issues,
            CandidateEvaluationAcceptanceIssueCode.MAXIMUM_FALSE_POSITIVE_RATE_EXCEEDED,
            maximumFalsePositiveRate,
            safe.metrics().falsePositiveRate(),
            "false-positive rate"
        );
        checkMaximumRatio(
            issues,
            CandidateEvaluationAcceptanceIssueCode.MAXIMUM_FALSE_NEGATIVE_RATE_EXCEEDED,
            maximumFalseNegativeRate,
            safe.metrics().falseNegativeRate(),
            "false-negative rate"
        );
        if (issues.isEmpty()) {
            return CandidateEvaluationAcceptanceAssessment.accepted(this);
        }
        return CandidateEvaluationAcceptanceAssessment.rejected(this, issues);
    }

    private static void checkMinimumRatio(
        List<CandidateEvaluationAcceptanceIssue> issues,
        CandidateEvaluationAcceptanceIssueCode code,
        Optional<Double> required,
        DetectionMetricValue actual,
        String metricName
    ) {
        required.ifPresent(threshold -> {
            if (!actual.defined() || Double.compare(actual.value(), threshold) < 0) {
                issues.add(ratioIssue(
                    code,
                    actual,
                    threshold,
                    metricName + " " + renderMetric(actual) + " is below required minimum " + Double.toString(threshold)
                ));
            }
        });
    }

    private static void checkMaximumRatio(
        List<CandidateEvaluationAcceptanceIssue> issues,
        CandidateEvaluationAcceptanceIssueCode code,
        Optional<Double> required,
        DetectionMetricValue actual,
        String metricName
    ) {
        required.ifPresent(threshold -> {
            if (!actual.defined() || Double.compare(actual.value(), threshold) > 0) {
                issues.add(ratioIssue(
                    code,
                    actual,
                    threshold,
                    metricName + " " + renderMetric(actual) + " exceeds required maximum " + Double.toString(threshold)
                ));
            }
        });
    }

    private static CandidateEvaluationAcceptanceIssue countIssue(
        CandidateEvaluationAcceptanceIssueCode code,
        long actual,
        long required,
        String message
    ) {
        return new CandidateEvaluationAcceptanceIssue(code, Long.toString(actual), Long.toString(required), message);
    }

    private static CandidateEvaluationAcceptanceIssue ratioIssue(
        CandidateEvaluationAcceptanceIssueCode code,
        DetectionMetricValue actual,
        double required,
        String message
    ) {
        return new CandidateEvaluationAcceptanceIssue(code, renderMetric(actual), Double.toString(required), message);
    }

    private static String renderMetric(DetectionMetricValue actual) {
        return actual.defined() ? Double.toString(actual.value()) : "undefined";
    }

    private static Optional<Long> copyLong(Optional<Long> value, String field) {
        Optional<Long> safe = value == null ? Optional.empty() : value;
        safe.ifPresent(count -> {
            if (count < 0L) {
                throw new IllegalArgumentException(field + " must be >= 0");
            }
        });
        return safe;
    }

    private static Optional<Double> copyRatio(Optional<Double> value, String field) {
        Optional<Double> safe = value == null ? Optional.empty() : value;
        safe.ifPresent(ratio -> {
            if (ratio == null || !Double.isFinite(ratio) || ratio < 0.0 || ratio > 1.0) {
                throw new IllegalArgumentException(field + " must be finite in [0,1]");
            }
        });
        return safe;
    }

    public static final class Builder {
        private Long minimumEvaluableObservations;
        private Long maximumExcludedObservations;
        private Double minimumPrecision;
        private Double minimumRecall;
        private Double minimumF1;
        private Double maximumFalsePositiveRate;
        private Double maximumFalseNegativeRate;

        private Builder() {
        }

        public Builder minimumEvaluableObservations(long value) {
            this.minimumEvaluableObservations = value;
            return this;
        }

        public Builder maximumExcludedObservations(long value) {
            this.maximumExcludedObservations = value;
            return this;
        }

        public Builder minimumPrecision(double value) {
            this.minimumPrecision = value;
            return this;
        }

        public Builder minimumRecall(double value) {
            this.minimumRecall = value;
            return this;
        }

        public Builder minimumF1(double value) {
            this.minimumF1 = value;
            return this;
        }

        public Builder maximumFalsePositiveRate(double value) {
            this.maximumFalsePositiveRate = value;
            return this;
        }

        public Builder maximumFalseNegativeRate(double value) {
            this.maximumFalseNegativeRate = value;
            return this;
        }

        public CandidateEvaluationAcceptancePolicy build() {
            return new CandidateEvaluationAcceptancePolicy(
                Optional.ofNullable(minimumEvaluableObservations),
                Optional.ofNullable(maximumExcludedObservations),
                Optional.ofNullable(minimumPrecision),
                Optional.ofNullable(minimumRecall),
                Optional.ofNullable(minimumF1),
                Optional.ofNullable(maximumFalsePositiveRate),
                Optional.ofNullable(maximumFalseNegativeRate)
            );
        }
    }
}
