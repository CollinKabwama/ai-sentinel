package dev.aisentinel.core.identity.spi;

import dev.aisentinel.core.identity.model.IdentityContext;
import dev.aisentinel.core.identity.model.TrustEvaluation;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.http.HttpRequestView;

/**
 * Default when identity or trust evaluation is off: does not adjust trust or risk signals.
 */
public enum NoopTrustEvaluator implements TrustEvaluator {
    INSTANCE;

    @Override
    public TrustEvaluation evaluate(IdentityContext identity, HttpRequestView request, RequestFeatures features,
                                    RequestContext ctx) {
        return null;
    }
}
