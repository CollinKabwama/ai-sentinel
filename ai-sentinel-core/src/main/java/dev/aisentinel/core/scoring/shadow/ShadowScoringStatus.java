package dev.aisentinel.core.scoring.shadow;

/**
 * Execution state of one shadow observation attempt.
 * <p>
 * Describes shadow <em>execution</em>, not candidate load status
 * ({@code NOT_CONFIGURED} / {@code INVALID} / {@code UNAVAILABLE} / {@code READY}).
 */
public enum ShadowScoringStatus {

    /** Shadow configuration is disabled; candidate was not invoked. */
    DISABLED,

    /**
     * Shadow is enabled but the bound candidate failed eligibility
     * (for example accepted-identity mismatch). Candidate was not scored.
     */
    NOT_ELIGIBLE,

    /**
     * Shadow is enabled and identity-eligible, but no callable candidate was
     * bound for this executor.
     */
    CANDIDATE_UNAVAILABLE,

    /** Candidate returned a contract-valid risk score. */
    SCORED,

    /** Candidate returned an invalid score ({@code NaN}, {@code ±Infinity}, or negative). */
    CANDIDATE_INVALID_SCORE,

    /** Candidate {@code score} threw an ordinary runtime exception. */
    CANDIDATE_EXECUTION_FAILED
}
