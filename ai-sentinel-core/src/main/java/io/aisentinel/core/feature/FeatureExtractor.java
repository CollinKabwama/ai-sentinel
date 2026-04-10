package io.aisentinel.core.feature;

import io.aisentinel.core.model.RequestContext;
import io.aisentinel.core.model.RequestFeatures;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Extracts privacy-aware numeric features from an HTTP request.
 * <p>
 * Runs on the <strong>request path</strong> inside {@link io.aisentinel.core.SentinelPipeline}. Implementations must be
 * thread-safe and should not perform network I/O; state must remain bounded (see {@link #metricsEndpointHistoryEntryCount}).
 */
public interface FeatureExtractor {

    /**
     * @param request      current HTTP request
     * @param identityHash stable hash of identity (never raw PII)
     * @param ctx          optional request context
     * @return immutable feature vector for scoring
     */
    RequestFeatures extract(HttpServletRequest request, String identityHash, RequestContext ctx);

    /**
     * Approximate size of extractor-internal caches for actuator/metrics (e.g. endpoint history). Default none.
     */
    default int metricsEndpointHistoryEntryCount() {
        return 0;
    }
}
