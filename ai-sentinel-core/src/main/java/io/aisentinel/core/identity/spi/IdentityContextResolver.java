package io.aisentinel.core.identity.spi;

import io.aisentinel.core.model.RequestContext;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Populates identity-related state on the shared {@link RequestContext} before feature extraction.
 */
public interface IdentityContextResolver {

    void resolve(HttpServletRequest request, String identityHash, RequestContext ctx);
}
