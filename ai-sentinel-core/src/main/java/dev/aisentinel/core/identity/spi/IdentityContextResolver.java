package dev.aisentinel.core.identity.spi;

import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.http.HttpRequestView;

/**
 * Populates identity-related state on the shared {@link RequestContext} before feature extraction.
 */
public interface IdentityContextResolver {

    void resolve(HttpRequestView request, String identityHash, RequestContext ctx);
}
