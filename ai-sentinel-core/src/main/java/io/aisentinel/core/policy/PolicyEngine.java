package io.aisentinel.core.policy;

import io.aisentinel.core.model.RequestFeatures;

/**
 * Maps a single composite risk score (0–1) to an {@link EnforcementAction}.
 * <p>
 * Invoked on the request path after scoring. Implementations must be side-effect free and non-blocking; local
 * enforcement policy remains authoritative (cluster views are merged in the handler layer, not here).
 */
public interface PolicyEngine {

    /**
     * @param riskScore    composite score in {@code [0.0, 1.0]} (may be NaN or out of range in edge cases; callers
     *                     may clamp before policy)
     * @param features     request features (for future policy extensions)
     * @param endpoint     normalized request path or endpoint key
     * @return enforcement action to apply
     */
    EnforcementAction evaluate(double riskScore, RequestFeatures features, String endpoint);
}
