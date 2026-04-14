package io.aisentinel.core.identity.model;

import java.util.Objects;

/**
 * Snapshot of authentication state for the current request (no raw credentials).
 */
public record AuthenticationContext(
    boolean authenticated,
    boolean anonymous,
    String principalName
) {
    public AuthenticationContext {
        principalName = principalName != null ? principalName : "";
    }

    public static AuthenticationContext unauthenticated() {
        return new AuthenticationContext(false, true, "");
    }

    public static AuthenticationContext ofPrincipal(String name) {
        Objects.requireNonNull(name, "name");
        return new AuthenticationContext(true, false, name);
    }
}
