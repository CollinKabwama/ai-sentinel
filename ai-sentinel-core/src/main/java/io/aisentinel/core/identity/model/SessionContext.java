package io.aisentinel.core.identity.model;

/**
 * Opaque session reference for the current request (hashed identifiers only).
 */
public record SessionContext(
    boolean present,
    String sessionIdHash
) {
    public SessionContext {
        sessionIdHash = sessionIdHash != null ? sessionIdHash : "";
    }

    public static SessionContext none() {
        return new SessionContext(false, "");
    }

    public static SessionContext ofHashedId(String sessionIdHash) {
        return new SessionContext(sessionIdHash != null && !sessionIdHash.isEmpty(), sessionIdHash != null ? sessionIdHash : "");
    }
}
