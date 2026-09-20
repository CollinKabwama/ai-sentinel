package dev.aisentinel.core.evaluation;

import java.util.Objects;

/**
 * One failed candidate-acceptance criterion, including the observed and required values.
 */
public record CandidateEvaluationAcceptanceIssue(
    CandidateEvaluationAcceptanceIssueCode code,
    String actual,
    String required,
    String message
) {
    public CandidateEvaluationAcceptanceIssue {
        code = Objects.requireNonNull(code, "code");
        actual = requireNotBlank("actual", actual);
        required = requireNotBlank("required", required);
        message = requireNotBlank("message", message);
    }

    private static String requireNotBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
