package dev.aisentinel.core.identity.spi;

import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.enforcement.EnforcementResponse;

/**
 * Optional callback after the pipeline completes (success or early failure after feature extraction).
 */
public interface IdentityResponseHook {

    void afterPipeline(HttpRequestView request, EnforcementResponse response, String identityHash,
                       RequestFeatures features, RequestContext ctx, boolean requestProceeded);
}
