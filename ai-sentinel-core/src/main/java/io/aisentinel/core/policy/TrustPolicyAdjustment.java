package io.aisentinel.core.policy;

/**
 * Result of applying trust-aware policy on top of anomaly-based {@link EnforcementAction}.
 */
public record TrustPolicyAdjustment(EnforcementAction action, String trustPolicyDetail) {
    public TrustPolicyAdjustment {
        if (action == null) {
            throw new IllegalArgumentException("action");
        }
        trustPolicyDetail = trustPolicyDetail != null ? trustPolicyDetail : "";
    }

    public boolean changedFrom(EnforcementAction baseline) {
        return baseline != action;
    }
}
