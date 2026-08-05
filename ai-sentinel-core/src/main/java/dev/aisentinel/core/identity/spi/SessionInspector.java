package dev.aisentinel.core.identity.spi;

import dev.aisentinel.core.identity.model.SessionContext;
import dev.aisentinel.core.http.HttpRequestView;

/**
 * Inspects session metadata exposed by {@link dev.aisentinel.core.http.HttpRequestView} for
 * {@link IdentityContext} assembly (hashed identifiers only).
 */
@FunctionalInterface
public interface SessionInspector {

    SessionContext inspect(HttpRequestView request, String identityHash);
}
