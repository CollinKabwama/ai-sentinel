package dev.aisentinel.autoconfigure.identity;

import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.identity.model.SessionContext;
import dev.aisentinel.core.identity.spi.SessionInspector;

/**
 * Exposes a hashed session id when a session exists (never stores raw session ids on the context).
 * <p>
 * A session counts as new when {@link HttpRequestView#isNewSession()} is true (servlet
 * {@code HttpSession#isNew()} via the servlet adapter).
 */
public final class HttpSessionSessionInspector implements SessionInspector {

    @Override
    public SessionContext inspect(HttpRequestView request, String identityHash) {
        try {
            if (!request.hasSession()) {
                return SessionContext.none();
            }
            String id = request.getSessionId();
            if (id == null || id.isEmpty()) {
                return SessionContext.none();
            }
            return SessionContext.ofHashedId(IdentityHashing.sha256Hex(id), request.isNewSession());
        } catch (Exception e) {
            return SessionContext.none();
        }
    }
}
