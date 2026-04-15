package io.aisentinel.autoconfigure.identity;

import io.aisentinel.core.identity.model.SessionContext;
import io.aisentinel.core.identity.spi.SessionInspector;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Exposes a hashed servlet session id when a session exists (never stores raw session ids on the context).
 */
public final class HttpSessionSessionInspector implements SessionInspector {

    @Override
    public SessionContext inspect(HttpServletRequest request, String identityHash) {
        try {
            HttpSession session = request.getSession(false);
            if (session == null) {
                return SessionContext.none();
            }
            String id = session.getId();
            if (id == null || id.isEmpty()) {
                return SessionContext.none();
            }
            return SessionContext.ofHashedId(IdentityHashing.sha256Hex(id), session.isNew());
        } catch (Exception e) {
            return SessionContext.none();
        }
    }
}
