package io.aisentinel.core.identity.spi;

import io.aisentinel.core.identity.model.IdentityContext;
import io.aisentinel.core.identity.model.TrustEvaluation;
import io.aisentinel.core.model.RequestContext;
import io.aisentinel.core.model.RequestFeatures;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Behavioral / session trust evaluation for the Identity arm. Must not alter API anomaly scores or policy.
 * <p>
 * {@link NoopTrustEvaluator} returns {@code null} so {@link IdentityContext} trust/risk from resolution are unchanged.
 * Phase 2 implementations return a {@link TrustEvaluation} with {@link io.aisentinel.core.identity.model.TrustScore}
 * and {@link io.aisentinel.core.identity.model.IdentityRiskSignals}.
 *
 * @return {@code null} to skip updating trust and risk signals on the context
 */
public interface TrustEvaluator {

    TrustEvaluation evaluate(IdentityContext identity, HttpServletRequest request, RequestFeatures features, RequestContext ctx);
}
