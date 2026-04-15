package io.aisentinel.core.identity;

/**
 * Keys for {@link io.aisentinel.core.identity.model.IdentityRiskSignals#components()} in Phase 2 behavioral trust.
 * Values are bounded severity weights in {@code [0, 1]} contributing to trust penalties.
 */
public final class IdentityRiskSignalKeys {

    public static final String SPARSE_HISTORY = "sparse_history";
    public static final String NEW_SESSION = "new_session";
    public static final String IP_DRIFT = "ip_drift";
    public static final String USER_AGENT_DRIFT = "user_agent_drift";
    public static final String REQUEST_BURST = "request_burst";

    private IdentityRiskSignalKeys() {}
}
