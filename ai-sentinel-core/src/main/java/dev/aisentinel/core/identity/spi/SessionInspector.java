package dev.aisentinel.core.identity.spi;

import dev.aisentinel.core.identity.model.SessionContext;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Inspects servlet session metadata for {@link IdentityContext} assembly (hashed identifiers only).
 */
@FunctionalInterface
public interface SessionInspector {

    SessionContext inspect(HttpServletRequest request, String identityHash);
}
