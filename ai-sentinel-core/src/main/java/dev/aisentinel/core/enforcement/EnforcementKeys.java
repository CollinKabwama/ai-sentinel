package dev.aisentinel.core.enforcement;

import dev.aisentinel.core.model.IdentityEndpointKey;

/**
 * Stable enforcement key shape aligned with {@link CompositeEnforcementHandler} and cluster Redis keys.
 */
public final class EnforcementKeys {

    private EnforcementKeys() {
    }

    public static String enforcementKey(EnforcementScope scope, String identityHash, String endpoint) {
        return enforcementStateKey(scope, identityHash, endpoint).storageKey();
    }

    public static IdentityEndpointKey enforcementStateKey(EnforcementScope scope, String identityHash, String endpoint) {
        if (identityHash == null || identityHash.isBlank()) {
            return IdentityEndpointKey.forEndpoint("", "");
        }
        if (scope == EnforcementScope.IDENTITY_GLOBAL) {
            return IdentityEndpointKey.forIdentity(identityHash);
        }
        return IdentityEndpointKey.forEndpoint(identityHash, endpoint);
    }
}
