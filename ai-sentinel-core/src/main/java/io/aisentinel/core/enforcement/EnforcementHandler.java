package io.aisentinel.core.enforcement;

import io.aisentinel.core.policy.EnforcementAction;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Applies an enforcement action to the request/response.
 * Returns true if the request should proceed (doFilter), false if response was already written.
 */
public interface EnforcementHandler {

    boolean apply(EnforcementAction action, HttpServletRequest request, HttpServletResponse response,
                  String identityHash, String endpoint);

    /**
     * @param endpoint request path or normalized endpoint; used when enforcement scope is per-endpoint.
     */
    default boolean isQuarantined(String identityHash, String endpoint) {
        return false;
    }

    /** @deprecated use {@link #isQuarantined(String, String)} */
    @Deprecated
    default boolean isQuarantined(String identityHash) {
        return isQuarantined(identityHash, "");
    }
}
