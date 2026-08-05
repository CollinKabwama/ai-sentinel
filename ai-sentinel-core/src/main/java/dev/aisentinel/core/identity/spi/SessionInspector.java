package dev.aisentinel.core.identity.spi;

import dev.aisentinel.core.identity.model.IdentityContext;
import dev.aisentinel.core.identity.model.SessionContext;
import dev.aisentinel.core.http.HttpRequestView;

/**
 * Inspects session metadata exposed by {@link HttpRequestView} for {@link IdentityContext} assembly
 * (hashed identifiers only — never store raw session ids on the context).
 */
@FunctionalInterface
public interface SessionInspector {

    /**
     * @param request      current request view
     * @param identityHash hashed enforcement identity (may be unused by some inspectors)
     * @return session snapshot; use {@link SessionContext#none()} when no session is present
     */
    SessionContext inspect(HttpRequestView request, String identityHash);
}
