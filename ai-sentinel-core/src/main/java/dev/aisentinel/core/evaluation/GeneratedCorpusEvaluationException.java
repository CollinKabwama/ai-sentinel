package dev.aisentinel.core.evaluation;

/**
 * Thrown when a generated Evaluation Kit corpus cannot be loaded, validated, or prepared for
 * detection evaluation.
 */
public final class GeneratedCorpusEvaluationException extends RuntimeException {

    private final GeneratedCorpusEvaluationFailureKind failureKind;

    public GeneratedCorpusEvaluationException(GeneratedCorpusEvaluationFailureKind failureKind, String message) {
        super(message);
        this.failureKind = failureKind == null
            ? GeneratedCorpusEvaluationFailureKind.EVALUATION_FAILURE
            : failureKind;
    }

    public GeneratedCorpusEvaluationException(
        GeneratedCorpusEvaluationFailureKind failureKind,
        String message,
        Throwable cause
    ) {
        super(message, cause);
        this.failureKind = failureKind == null
            ? GeneratedCorpusEvaluationFailureKind.EVALUATION_FAILURE
            : failureKind;
    }

    public GeneratedCorpusEvaluationFailureKind failureKind() {
        return failureKind;
    }
}
