package dev.aisentinel.core.identity.spi;

import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.enforcement.EnforcementResponse;

public enum NoopIdentityResponseHook implements IdentityResponseHook {
    INSTANCE;

    @Override
    public void afterPipeline(HttpRequestView request, EnforcementResponse response, String identityHash,
                              RequestFeatures features, RequestContext ctx, boolean requestProceeded) {
        // intentionally empty
    }
}
