package dev.aisentinel.core.identity.spi;

import dev.aisentinel.core.identity.model.AuthenticationContext;
import dev.aisentinel.core.http.HttpRequestView;

/**
 * Inspects the ambient authentication of the current request for {@link IdentityContext} assembly.
 */
@FunctionalInterface
public interface AuthenticationInspector {

    AuthenticationContext inspect(HttpRequestView request, String identityHash);
}
