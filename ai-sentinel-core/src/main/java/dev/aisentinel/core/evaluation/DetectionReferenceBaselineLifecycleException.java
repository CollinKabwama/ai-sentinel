package dev.aisentinel.core.evaluation;

/**
 * Technical or governance-precondition failure for baseline lifecycle operations.
 * <p>
 * Distinct from an explicit human rejection decision.
 */
public final class DetectionReferenceBaselineLifecycleException extends RuntimeException {

    public enum Code {
        INVALID_INPUT,
        CANDIDATE_ALREADY_EXISTS,
        CANDIDATE_NOT_FOUND,
        CANDIDATE_STALE,
        CANDIDATE_INTEGRITY_FAILURE,
        OFFICIAL_INTEGRITY_FAILURE,
        COMPARISON_INTEGRITY_FAILURE,
        GOVERNANCE_INTEGRITY_FAILURE,
        APPROVAL_REQUIRED,
        CANDIDATE_REJECTED,
        ALREADY_DECIDED,
        ALREADY_PROMOTED,
        NO_OP_IDENTICAL,
        HISTORY_CONFLICT,
        HISTORY_NOT_FOUND,
        HISTORY_INTEGRITY_FAILURE,
        PRECONDITION_FAILED,
        PUBLICATION_FAILURE
    }

    private final Code code;

    public DetectionReferenceBaselineLifecycleException(Code code, String message) {
        super(message);
        this.code = code == null ? Code.PRECONDITION_FAILED : code;
    }

    public DetectionReferenceBaselineLifecycleException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code == null ? Code.PRECONDITION_FAILED : code;
    }

    public Code code() {
        return code;
    }
}
