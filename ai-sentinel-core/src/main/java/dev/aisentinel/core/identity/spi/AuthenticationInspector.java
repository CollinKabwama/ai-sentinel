package dev.aisentinel.core.identity.spi;

import dev.aisentinel.core.identity.model.AuthenticationContext;
import dev.aisentinel.core.identity.model.IdentityContext;
import dev.aisentinel.core.http.HttpRequestView;

/**
 * Inspects the ambient authentication of the current request for {@link IdentityContext} assembly.
 * <p>
 * Starter default uses Spring Security when present. Implementations must not mutate the request or create sessions.
 */
@FunctionalInterface
public interface AuthenticationInspector {

    /**
     * @param request      current request view
     * @param identityHash hashed enforcement identity (may be unused by some inspectors)
     * @return normalized authentication snapshot; never {@code null} (use unauthenticated when unknown)
     */
    AuthenticationContext inspect(HttpRequestView request, String identityHash);
}
