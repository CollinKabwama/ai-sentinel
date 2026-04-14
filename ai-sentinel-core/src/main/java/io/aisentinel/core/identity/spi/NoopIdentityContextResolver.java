package io.aisentinel.core.identity.spi;

import io.aisentinel.core.model.RequestContext;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Default when {@code ai.sentinel.identity.enabled=false}: leaves {@link RequestContext} unchanged.
 */
public enum NoopIdentityContextResolver implements IdentityContextResolver {
    INSTANCE;

    @Override
    public void resolve(HttpServletRequest request, String identityHash, RequestContext ctx) {
        // intentionally empty
    }
}
