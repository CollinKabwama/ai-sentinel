package io.aisentinel.core.identity.spi;

import io.aisentinel.core.identity.model.IdentityContext;
import io.aisentinel.core.identity.model.TrustEvaluation;
import io.aisentinel.core.model.RequestContext;
import io.aisentinel.core.model.RequestFeatures;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Default when identity or trust evaluation is off: does not adjust trust or risk signals.
 */
public enum NoopTrustEvaluator implements TrustEvaluator {
    INSTANCE;

    @Override
    public TrustEvaluation evaluate(IdentityContext identity, HttpServletRequest request, RequestFeatures features,
                                    RequestContext ctx) {
        return null;
    }
}
