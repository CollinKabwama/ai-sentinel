package io.aisentinel.core.identity.model;

/**
 * Opaque session reference for the current request (hashed identifiers only).
 */
public record SessionContext(
    boolean present,
    String sessionIdHash,
    /** From {@link jakarta.servlet.http.HttpSession#isNew()} when {@link #present}. */
    boolean newSession
) {
    public SessionContext {
        sessionIdHash = sessionIdHash != null ? sessionIdHash : "";
    }

    public static SessionContext none() {
        return new SessionContext(false, "", false);
    }

    public static SessionContext ofHashedId(String sessionIdHash) {
        return ofHashedId(sessionIdHash, false);
    }

    public static SessionContext ofHashedId(String sessionIdHash, boolean newSession) {
        boolean hasHash = sessionIdHash != null && !sessionIdHash.isEmpty();
        return new SessionContext(hasHash, hasHash ? sessionIdHash : "", hasHash && newSession);
    }
}
