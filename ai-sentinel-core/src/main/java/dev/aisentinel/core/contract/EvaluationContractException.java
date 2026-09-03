package dev.aisentinel.core.contract;

/**
 * Thrown when an {@link EvaluationRequest} fails contract validation.
 * Represents a transport/contract error — not a security attack classification.
 */
public final class EvaluationContractException extends IllegalArgumentException {
    public EvaluationContractException(String message) {
        super(message);
    }
}
