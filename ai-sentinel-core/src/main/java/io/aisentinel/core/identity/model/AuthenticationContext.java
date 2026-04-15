package io.aisentinel.core.identity.model;

import java.util.List;
import java.util.Objects;

/**
 * Snapshot of authentication state for the current request (no raw credentials).
 * <p>
 * Distinguish: {@code authenticationInfrastructurePresent == false} means Spring Security could not be
 * introspected (types absent). {@code authenticated == false} with infrastructure present means the
 * caller is not logged in or is the anonymous principal.
 */
public record AuthenticationContext(
    boolean authenticated,
    boolean anonymous,
    String principalName,
    /** Simple name of the {@code Authentication} implementation when known (e.g. Bearer), otherwise empty. */
    String authenticationType,
    /** Granted authority strings (e.g. roles), capped by the inspector; immutable. */
    List<String> roleNames,
    /**
     * {@code false} when Spring Security was not available for introspection; {@code true} when the
     * security API was usable for this request (including anonymous/unauthenticated outcomes).
     */
    boolean authenticationInfrastructurePresent
) {
    public AuthenticationContext {
        principalName = principalName != null ? principalName : "";
        authenticationType = authenticationType != null ? authenticationType : "";
        roleNames = roleNames != null ? List.copyOf(roleNames) : List.of();
    }

    /** Anonymous / not logged in; security API assumed available (typical servlet + optional SS). */
    public static AuthenticationContext unauthenticated() {
        return unauthenticated(true);
    }

    /**
     * @param authenticationInfrastructurePresent use {@code false} only when security types cannot be loaded
     */
    public static AuthenticationContext unauthenticated(boolean authenticationInfrastructurePresent) {
        return new AuthenticationContext(false, true, "", "", List.of(), authenticationInfrastructurePresent);
    }

    /** Authenticated user with only principal known (minimal). */
    public static AuthenticationContext ofPrincipal(String name) {
        Objects.requireNonNull(name, "name");
        return ofAuthenticated(name, "", List.of());
    }

    public static AuthenticationContext ofAuthenticated(String principalName,
                                                         String authenticationType,
                                                         List<String> roleNames) {
        Objects.requireNonNull(principalName, "principalName");
        return new AuthenticationContext(
            true,
            false,
            principalName,
            authenticationType != null ? authenticationType : "",
            roleNames != null ? List.copyOf(roleNames) : List.of(),
            true
        );
    }
}
