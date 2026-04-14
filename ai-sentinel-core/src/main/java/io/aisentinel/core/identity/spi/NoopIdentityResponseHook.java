package io.aisentinel.core.identity.spi;

import io.aisentinel.core.model.RequestContext;
import io.aisentinel.core.model.RequestFeatures;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public enum NoopIdentityResponseHook implements IdentityResponseHook {
    INSTANCE;

    @Override
    public void afterPipeline(HttpServletRequest request, HttpServletResponse response, String identityHash,
                              RequestFeatures features, RequestContext ctx, boolean requestProceeded) {
        // intentionally empty
    }
}
