package dev.aisentinel.core.identity.spi;

import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.enforcement.EnforcementResponse;

/**
 * Optional callback after the pipeline completes (success or early return after feature extraction).
 * <p>
 * Invoked from {@link dev.aisentinel.core.SentinelPipeline} in a {@code finally} block. Implementations must be
 * fail-open (exceptions are swallowed by the pipeline) and must not block indefinitely.
 */
public interface IdentityResponseHook {

    /**
     * @param request           current request view
     * @param response          enforcement response adapter (may already have been written)
     * @param identityHash      hashed enforcement identity
     * @param features          extracted features, or {@code null} if extraction failed / skipped
     * @param ctx               per-request context
     * @param requestProceeded  whether the filter chain should continue ({@code true}) or was blocked
     */
    void afterPipeline(HttpRequestView request, EnforcementResponse response, String identityHash,
                       RequestFeatures features, RequestContext ctx, boolean requestProceeded);
}
