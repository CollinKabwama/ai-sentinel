package dev.aisentinel.core.identity.spi;

import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.http.HttpRequestView;

/**
 * Default when identity context resolution is disabled: leaves {@link RequestContext} unchanged.
 * Thread-safe singleton; safe to share across requests.
 */
public enum NoopIdentityContextResolver implements IdentityContextResolver {
    INSTANCE;

    @Override
    public void resolve(HttpRequestView request, String identityHash, RequestContext ctx) {
        // intentionally empty
    }
}
