package io.aisentinel.core.feature;

import io.aisentinel.core.model.RequestContext;
import io.aisentinel.core.model.RequestFeatures;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Extracts privacy-aware numeric features from an HTTP request.
 */
public interface FeatureExtractor {

    RequestFeatures extract(HttpServletRequest request, String identityHash, RequestContext ctx);

    /**
     * Size of extractor-internal caches for metrics (e.g. endpoint history). Default none.
     */
    default int metricsEndpointHistoryEntryCount() {
        return 0;
    }
}
