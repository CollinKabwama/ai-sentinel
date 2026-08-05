package dev.aisentinel.core.identity.spi;

import dev.aisentinel.core.identity.model.IdentityContext;
import dev.aisentinel.core.identity.model.TrustEvaluation;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.http.HttpRequestView;

/**
 * Behavioral / session trust evaluation for the identity arm. Must not alter API anomaly scores or policy thresholds
 * directly; results are attached to {@link IdentityContext} and may later influence fusion or trust-aware policy.
 * <p>
 * {@link NoopTrustEvaluator} returns {@code null} so trust/risk from resolution are unchanged.
 * Concrete implementations return a {@link TrustEvaluation} with a trust score and optional risk signals.
 * <p>
 * Invoked on the request path inside {@link dev.aisentinel.core.decision.SentinelDecisionEngine}; keep work bounded.
 */
public interface TrustEvaluator {

    /**
     * @return evaluation to merge into the identity context, or {@code null} to leave trust/risk unchanged
     */
    TrustEvaluation evaluate(IdentityContext identity, HttpRequestView request, RequestFeatures features, RequestContext ctx);
}
