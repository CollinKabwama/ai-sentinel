package dev.aisentinel.core.identity.spi;

import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.http.HttpRequestView;

/**
 * Populates identity-related state on the shared {@link RequestContext} before feature extraction.
 * <p>
 * Implementations should be fail-open: unexpected errors are caught by {@link dev.aisentinel.core.SentinelPipeline}
 * so the request can continue without a rich identity context. Must not write to the HTTP response.
 */
public interface IdentityContextResolver {

    /**
     * Resolves authentication/session (and related) identity data into {@code ctx}.
     *
     * @param request      current request view
     * @param identityHash hashed enforcement identity for this request
     * @param ctx          mutable per-request context; may receive {@code IdentityContext} and related keys
     */
    void resolve(HttpRequestView request, String identityHash, RequestContext ctx);
}
