package dev.aisentinel.core.policy;

/**
 * Request context keys for trust-aware policy. Values are optional diagnostics only.
 */
public final class TrustPolicyContextKeys {

    /** Non-empty when {@link TrustPolicyAdjuster} escalates the action beyond anomaly-only policy. */
    public static final String TRUST_POLICY_DETAIL = "dev.aisentinel.trustPolicy.detail";

    private TrustPolicyContextKeys() {}
}
