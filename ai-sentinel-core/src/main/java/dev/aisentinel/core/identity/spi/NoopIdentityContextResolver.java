package dev.aisentinel.core.identity.spi;

import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.http.HttpRequestView;

/**
 * Default when {@code ai.sentinel.identity.enabled=false}: leaves {@link RequestContext} unchanged.
 */
public enum NoopIdentityContextResolver implements IdentityContextResolver {
    INSTANCE;

    @Override
    public void resolve(HttpRequestView request, String identityHash, RequestContext ctx) {
        // intentionally empty
    }
}
