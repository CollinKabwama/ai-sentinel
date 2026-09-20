package dev.aisentinel.core.evaluation;

/**
 * Integrity failure while loading or validating a persisted Official Detection Reference Baseline.
 */
public final class DetectionReferenceBaselineIntegrityException extends RuntimeException {

    public DetectionReferenceBaselineIntegrityException(String message) {
        super(message);
    }

    public DetectionReferenceBaselineIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
