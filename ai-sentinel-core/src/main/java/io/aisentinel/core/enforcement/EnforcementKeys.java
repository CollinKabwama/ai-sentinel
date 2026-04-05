package io.aisentinel.core.enforcement;

/**
 * Stable enforcement key shape aligned with {@link CompositeEnforcementHandler} and cluster Redis keys.
 */
public final class EnforcementKeys {

    private EnforcementKeys() {
    }

    public static String enforcementKey(EnforcementScope scope, String identityHash, String endpoint) {
        if (identityHash == null || identityHash.isBlank()) {
            return "";
        }
        if (scope == EnforcementScope.IDENTITY_GLOBAL) {
            return identityHash;
        }
        return identityHash + "|" + (endpoint != null ? endpoint : "");
    }
}
