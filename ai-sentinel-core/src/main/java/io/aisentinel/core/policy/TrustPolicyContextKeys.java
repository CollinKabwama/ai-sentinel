package io.aisentinel.core.policy;

/**
 * Request context keys for trust-aware policy (Phase 3). Values are optional diagnostics only.
 */
public final class TrustPolicyContextKeys {

    /** Non-empty when {@link TrustPolicyAdjuster} escalates the action beyond anomaly-only policy. */
    public static final String TRUST_POLICY_DETAIL = "io.aisentinel.trustPolicy.detail";

    private TrustPolicyContextKeys() {}
}
