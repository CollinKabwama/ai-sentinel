package dev.aisentinel.core.evaluation;

/**
 * Signals deterministic alignment failures between annotations and replay output.
 */
public final class EvaluationAlignmentException extends RuntimeException {

    public EvaluationAlignmentException(String message) {
        super(message);
    }
}
