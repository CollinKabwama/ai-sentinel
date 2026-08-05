package dev.aisentinel.distributed.enforcement;

import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementScope;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.distributed.quarantine.ClusterQuarantineReader;
import dev.aisentinel.distributed.quarantine.NoopClusterQuarantineReader;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.enforcement.EnforcementResponse;

import java.util.OptionalLong;

/**
 * Decorates an {@link EnforcementHandler} by OR-ing {@link #isQuarantined(String, String)} with a
 * {@link ClusterQuarantineReader} (e.g. Redis-backed). Apply path is delegated unchanged; local quarantine
 * writes remain on the delegate (typically {@link dev.aisentinel.core.enforcement.CompositeEnforcementHandler}).
 * <p>
 * <strong>Fail-open:</strong> if the reader returns empty, only local quarantine applies.
 */
public final class ClusterAwareEnforcementHandler implements EnforcementHandler {

    private final EnforcementHandler delegate;
    private final ClusterQuarantineReader clusterReader;
    private final String tenantId;
    private final EnforcementScope enforcementScope;

    public ClusterAwareEnforcementHandler(EnforcementHandler delegate,
                                          ClusterQuarantineReader clusterReader,
                                          String tenantId,
                                          EnforcementScope enforcementScope) {
        this.delegate = delegate;
        this.clusterReader = clusterReader != null ? clusterReader : NoopClusterQuarantineReader.INSTANCE;
        this.tenantId = tenantId != null && !tenantId.isBlank() ? tenantId : "default";
        this.enforcementScope = enforcementScope != null ? enforcementScope : EnforcementScope.IDENTITY_ENDPOINT;
    }

    @Override
    public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                         String identityHash, String endpoint) {
        return delegate.apply(action, request, response, identityHash, endpoint);
    }

    @Override
    public boolean isQuarantined(String identityHash, String endpoint) {
        if (delegate.isQuarantined(identityHash, endpoint)) {
            return true;
        }
        String key = buildEnforcementStateKey(identityHash, endpoint);
        OptionalLong until = clusterReader.quarantineUntil(tenantId, key);
        long now = System.currentTimeMillis();
        return until.isPresent() && until.getAsLong() > now;
    }

    private String buildEnforcementStateKey(String identityHash, String endpoint) {
        if (enforcementScope == EnforcementScope.IDENTITY_GLOBAL) {
            return identityHash;
        }
        return identityHash + "|" + (endpoint != null ? endpoint : "");
    }

    public EnforcementHandler getDelegate() {
        return delegate;
    }
}
