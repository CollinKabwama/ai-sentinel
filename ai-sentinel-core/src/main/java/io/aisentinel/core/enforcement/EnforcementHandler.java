package io.aisentinel.core.enforcement;

import io.aisentinel.core.policy.EnforcementAction;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Applies {@link EnforcementAction} to the HTTP request/response (throttle, block, quarantine, etc.).
 * <p>
 * Runs on the request path. Local maps (throttle/quarantine) are the <strong>source of truth</strong> for
 * {@link #apply}; optional cluster merges are additive in {@link io.aisentinel.distributed.enforcement.ClusterAwareEnforcementHandler}.
 * Implementations must not block indefinitely on remote I/O.
 *
 * @return {@code true} if the filter chain should continue; {@code false} if the response was already committed
 */
public interface EnforcementHandler {

    boolean apply(EnforcementAction action, HttpServletRequest request, HttpServletResponse response,
                  String identityHash, String endpoint);

    /**
     * Whether the identity (and endpoint) is under active quarantine for {@code isQuarantined} checks.
     *
     * @param endpoint request path or normalized endpoint; used when enforcement scope is per-endpoint
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
