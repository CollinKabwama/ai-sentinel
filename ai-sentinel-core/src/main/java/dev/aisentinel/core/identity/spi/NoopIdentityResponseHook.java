package dev.aisentinel.core.identity.spi;

import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.enforcement.EnforcementResponse;

/**
 * No-op {@link IdentityResponseHook}: does nothing after the pipeline completes.
 * Used when no post-pipeline identity side effects are configured.
 */
public enum NoopIdentityResponseHook implements IdentityResponseHook {
    INSTANCE;

    @Override
    public void afterPipeline(HttpRequestView request, EnforcementResponse response, String identityHash,
                              RequestFeatures features, RequestContext ctx, boolean requestProceeded) {
        // intentionally empty
    }
}
