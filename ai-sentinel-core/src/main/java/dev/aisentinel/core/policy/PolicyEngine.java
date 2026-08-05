package dev.aisentinel.core.policy;

import dev.aisentinel.core.model.RequestFeatures;

/**
 * Maps a single composite risk score (0–1) to an {@link EnforcementAction}.
 * <p>
 * Invoked on the request path after scoring. Implementations must be side-effect free and non-blocking; local
 * enforcement policy remains authoritative (cluster views are merged in the handler layer, not here).
 */
public interface PolicyEngine {

    /**
     * @param riskScore    composite score in {@code [0.0, 1.0]} (may be NaN or out of range in edge cases; callers
     *                     typically clamp before policy)
     * @param features     request features available for policy rules that need more than the scalar score
     * @param endpoint     normalized request path or endpoint key
     * @return enforcement action to apply
     */
    EnforcementAction evaluate(double riskScore, RequestFeatures features, String endpoint);
}
