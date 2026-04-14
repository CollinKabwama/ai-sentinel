package io.aisentinel.core.identity.spi;

import io.aisentinel.core.identity.model.IdentityContext;
import io.aisentinel.core.identity.model.TrustScore;
import io.aisentinel.core.model.RequestContext;
import io.aisentinel.core.model.RequestFeatures;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Default when identity is disabled: does not adjust trust.
 */
public enum NoopTrustEvaluator implements TrustEvaluator {
    INSTANCE;

    @Override
    public TrustScore evaluate(IdentityContext identity, HttpServletRequest request, RequestFeatures features,
                               RequestContext ctx) {
        return null;
    }
}
