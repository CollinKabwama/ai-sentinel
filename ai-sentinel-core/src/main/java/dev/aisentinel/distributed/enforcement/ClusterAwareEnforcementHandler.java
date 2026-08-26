package dev.aisentinel.distributed.enforcement;

import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementScope;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.distributed.quarantine.ClusterQuarantineReader;
import dev.aisentinel.distributed.quarantine.NoopClusterQuarantineReader;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.enforcement.EnforcementResponse;

import java.util.Iterator;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decorates an {@link EnforcementHandler} by OR-ing {@link #isQuarantined(String, String)} with a
 * {@link ClusterQuarantineReader} (e.g. Redis-backed). Apply path is delegated unchanged; local quarantine
 * writes remain on the delegate (typically {@link dev.aisentinel.core.enforcement.CompositeEnforcementHandler}).
 * <p>
 * <strong>Fail-open:</strong> if the reader returns empty, only local quarantine applies.
 */
public final class ClusterAwareEnforcementHandler implements EnforcementHandler {

    private static final long RELEASE_SUPPRESSION_TTL_MS = 2_000L;
    private static final int MAX_RELEASE_SUPPRESSIONS = 10_000;

    private final EnforcementHandler delegate;
    private final ClusterQuarantineReader clusterReader;
    private final String tenantId;
    private final EnforcementScope enforcementScope;
    private final ConcurrentHashMap<String, Long> recentlyReleasedUntil = new ConcurrentHashMap<>();

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
        if (action == EnforcementAction.QUARANTINE) {
            recentlyReleasedUntil.remove(buildEnforcementStateKey(identityHash, endpoint));
        }
        return delegate.apply(action, request, response, identityHash, endpoint);
    }

    @Override
    public boolean isQuarantined(String identityHash, String endpoint) {
        if (delegate.isQuarantined(identityHash, endpoint)) {
            return true;
        }
        String key = buildEnforcementStateKey(identityHash, endpoint);
        if (isRecentlyReleased(key)) {
            return false;
        }
        OptionalLong until = clusterReader.quarantineUntil(tenantId, key);
        long now = System.currentTimeMillis();
        return until.isPresent() && until.getAsLong() > now;
    }

    /**
     * Releases local quarantine via the delegate, then invalidates any cluster lookup cache for the
     * same key so a just-cleared Redis entry cannot be reasserted from a stale positive cache hit.
     */
    @Override
    public boolean releaseQuarantine(String identityHash, String endpoint) {
        boolean removed = delegate.releaseQuarantine(identityHash, endpoint);
        String key = buildEnforcementStateKey(identityHash, endpoint);
        rememberRelease(key);
        try {
            clusterReader.invalidateQuarantineLookup(tenantId, key);
        } catch (RuntimeException ignored) {
            // fail-open: local release already applied
        }
        return removed;
    }

    private boolean isRecentlyReleased(String key) {
        Long suppressUntil = recentlyReleasedUntil.get(key);
        if (suppressUntil == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (suppressUntil <= now) {
            recentlyReleasedUntil.remove(key, suppressUntil);
            return false;
        }
        return true;
    }

    private void rememberRelease(String key) {
        if (recentlyReleasedUntil.size() >= MAX_RELEASE_SUPPRESSIONS) {
            long now = System.currentTimeMillis();
            for (Iterator<Map.Entry<String, Long>> it = recentlyReleasedUntil.entrySet().iterator(); it.hasNext();) {
                if (it.next().getValue() <= now) {
                    it.remove();
                }
            }
            while (recentlyReleasedUntil.size() >= MAX_RELEASE_SUPPRESSIONS) {
                Iterator<String> it = recentlyReleasedUntil.keySet().iterator();
                if (!it.hasNext()) {
                    break;
                }
                it.next();
                it.remove();
            }
        }
        recentlyReleasedUntil.put(key, System.currentTimeMillis() + RELEASE_SUPPRESSION_TTL_MS);
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
