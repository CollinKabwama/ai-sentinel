package dev.aisentinel.core.enforcement;

import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.policy.EnforcementAction;

/**
 * Applies {@link EnforcementAction} to the HTTP request/response (throttle, block, quarantine, etc.).
 * <p>
 * Runs on the request path. Local maps (throttle/quarantine) are the <strong>source of truth</strong> for
 * {@link #apply}; optional cluster merges are additive in {@link dev.aisentinel.distributed.enforcement.ClusterAwareEnforcementHandler}.
 * Implementations must not block indefinitely on remote I/O.
 *
 * @return {@code true} if the filter chain should continue; {@code false} if the response was already committed
 */
public interface EnforcementHandler {

    boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                  String identityHash, String endpoint);

    /**
     * Whether the identity (and endpoint) is under active quarantine for {@code isQuarantined} checks.
     *
     * @param endpoint request path or normalized endpoint; used when enforcement scope is per-endpoint
     */
    default boolean isQuarantined(String identityHash, String endpoint) {
        return false;
    }

    /**
     * @deprecated use {@link #isQuarantined(String, String)}. The pipeline and decision engine call only the
     * two-argument form; overriding this overload alone has no effect on quarantine checks.
     */
    @Deprecated
    default boolean isQuarantined(String identityHash) {
        return isQuarantined(identityHash, "");
    }

    /**
     * Releases quarantine for {@code identityHash} / {@code endpoint} according to the handler's
     * enforcement scope ({@code IDENTITY_GLOBAL} ignores endpoint; {@code IDENTITY_ENDPOINT} is exact).
     * <p>
     * Idempotent and targeted: a missing entry is a no-op success. Does not reset baselines, create
     * exemptions, or suppress future valid quarantine decisions. Default is a no-op that returns
     * {@code false} (no local entry removed).
     *
     * @return {@code true} if a local quarantine entry was present and removed; {@code false} if none
     */
    default boolean releaseQuarantine(String identityHash, String endpoint) {
        return false;
    }
}
