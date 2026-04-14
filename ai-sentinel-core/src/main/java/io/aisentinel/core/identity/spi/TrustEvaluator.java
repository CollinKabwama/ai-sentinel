package io.aisentinel.core.identity.spi;

import io.aisentinel.core.identity.model.IdentityContext;
import io.aisentinel.core.identity.model.TrustScore;
import io.aisentinel.core.model.RequestContext;
import io.aisentinel.core.model.RequestFeatures;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Hook for future continuous trust / session trust scoring. Must not alter API anomaly scores in Phase 0.
 * <p>
 * The default starter wiring uses {@link NoopTrustEvaluator}, which returns {@code null} so the
 * {@link IdentityContext} trust set during resolution is left unchanged. Supply a custom bean when Phase 1+ needs
 * trust updates without touching the anomaly {@link io.aisentinel.core.scoring.AnomalyScorer}.
 *
 * @return updated trust, or {@code null} to leave the current {@link IdentityContext} trust unchanged
 */
public interface TrustEvaluator {

    TrustScore evaluate(IdentityContext identity, HttpServletRequest request, RequestFeatures features, RequestContext ctx);
}
