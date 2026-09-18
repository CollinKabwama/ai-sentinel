package dev.aisentinel.core.scoring.lifecycle;

/**
 * Technical or governance-precondition failure for model lifecycle operations.
 * <p>
 * Distinct from an explicit human rejection decision
 * ({@link ModelPromotionDecisionStatus#REJECTED}).
 */
public final class ModelLifecycleException extends RuntimeException {

    public enum Code {
        INVALID_INPUT,
        CHAMPION_REQUIRED,
        CHALLENGER_REQUIRED,
        CHALLENGER_ALREADY_DESIGNATED,
        CHALLENGER_NOT_DESIGNATED,
        IDENTITY_MISMATCH,
        EVIDENCE_MISMATCH,
        NOT_ELIGIBLE,
        APPROVAL_REQUIRED,
        ALREADY_DECIDED,
        ALREADY_PROMOTED,
        REJECTED_NOT_PROMOTABLE,
        STALE_CHAMPION,
        STALE_APPROVAL,
        STALE_ROLLBACK,
        HISTORY_CONFLICT,
        HISTORY_NOT_FOUND,
        NO_OP_IDENTICAL,
        PRECONDITION_FAILED,
        PUBLICATION_FAILURE,
        CONCURRENT_MODIFICATION
    }

    private final Code code;

    public ModelLifecycleException(Code code, String message) {
        super(message);
        this.code = code == null ? Code.PRECONDITION_FAILED : code;
    }

    public ModelLifecycleException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code == null ? Code.PRECONDITION_FAILED : code;
    }

    public Code code() {
        return code;
    }
}
