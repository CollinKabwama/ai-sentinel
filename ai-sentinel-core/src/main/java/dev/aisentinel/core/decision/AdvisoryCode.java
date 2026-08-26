package dev.aisentinel.core.decision;

/**
 * Operator/system advisory recommendation. Distinct from {@link EnforcementAction}.
 * Answers "what should an operator consider?" — never "what must enforcement do now?"
 */
public enum AdvisoryCode {
    OBSERVE,
    INVESTIGATE,
    REQUIRE_ADDITIONAL_VERIFICATION,
    REVIEW_BASELINE,
    REVIEW_SCORER_HEALTH,
    RELEASE_QUARANTINE_AFTER_REVIEW,
    OTHER_OPERATOR_REVIEW
}
