package io.aisentinel.core.identity.spi;

import io.aisentinel.core.identity.model.AuthenticationContext;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Inspects servlet-layer authentication for {@link IdentityContext} assembly.
 */
@FunctionalInterface
public interface AuthenticationInspector {

    AuthenticationContext inspect(HttpServletRequest request, String identityHash);
}
