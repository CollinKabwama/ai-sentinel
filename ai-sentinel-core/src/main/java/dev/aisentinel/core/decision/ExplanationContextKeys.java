package dev.aisentinel.core.decision;

/**
 * {@link dev.aisentinel.core.model.RequestContext} keys for request-scoped decision explanation.
 */
public final class ExplanationContextKeys {

    /** {@link DecisionExplanationEvidence} captured during scoring for this request. */
    public static final String DECISION_EXPLANATION = "dev.aisentinel.decision.DECISION_EXPLANATION";

    private ExplanationContextKeys() {
    }
}
