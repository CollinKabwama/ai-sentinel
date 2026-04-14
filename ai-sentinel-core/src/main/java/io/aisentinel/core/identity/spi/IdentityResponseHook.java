package io.aisentinel.core.identity.spi;

import io.aisentinel.core.model.RequestContext;
import io.aisentinel.core.model.RequestFeatures;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Optional callback after the pipeline completes (success or early failure after feature extraction).
 */
public interface IdentityResponseHook {

    void afterPipeline(HttpServletRequest request, HttpServletResponse response, String identityHash,
                       RequestFeatures features, RequestContext ctx, boolean requestProceeded);
}
