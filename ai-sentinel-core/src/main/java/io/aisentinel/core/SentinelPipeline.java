package io.aisentinel.core;

import io.aisentinel.core.enforcement.EnforcementHandler;
import io.aisentinel.core.feature.FeatureExtractor;
import io.aisentinel.core.model.RequestContext;
import io.aisentinel.core.model.RequestFeatures;
import io.aisentinel.core.policy.EnforcementAction;
import io.aisentinel.core.policy.PolicyEngine;
import io.aisentinel.core.scoring.AnomalyScorer;
import io.aisentinel.core.telemetry.TelemetryEmitter;
import io.aisentinel.core.telemetry.TelemetryEvent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates feature extraction, scoring, policy, and enforcement.
 */
@Slf4j
@RequiredArgsConstructor
public final class SentinelPipeline {

    private final FeatureExtractor featureExtractor;
    private final AnomalyScorer scorer;
    private final PolicyEngine policyEngine;
    private final EnforcementHandler enforcementHandler;
    private final TelemetryEmitter telemetry;

    /**
     * Process request. Returns true if request should proceed (doFilter), false if already responded.
     */
    public boolean process(HttpServletRequest request, HttpServletResponse response, String identityHash) {
        RequestContext ctx = new RequestContext();
        RequestFeatures features;
        try {
            features = featureExtractor.extract(request, identityHash, ctx);
        } catch (Exception e) {
            log.debug("Feature extraction failed for {}: {}", request.getRequestURI(), e.getMessage());
            return true;
        }

        double score;
        try {
            score = scorer.score(features);
            scorer.update(features);
        } catch (Exception e) {
            log.debug("Scoring failed for {}: {}", features.endpoint(), e.getMessage());
            return true;
        }
        score = clampScore(score);

        EnforcementAction action = policyEngine.evaluate(score, features, features.endpoint());

        telemetry.emit(TelemetryEvent.threatScored(identityHash, features.endpoint(), score));
        if (score > 0.5) {
            telemetry.emit(TelemetryEvent.anomalyDetected(identityHash, features.endpoint(), score));
        }

        if (enforcementHandler.isQuarantined(identityHash)) {
            action = EnforcementAction.QUARANTINE;
        }

        return enforcementHandler.apply(action, request, response, identityHash, features.endpoint());
    }

    /** Prevents NaN or out-of-range scores from causing policy bypass; treat NaN as high risk. */
    private static double clampScore(double score) {
        if (Double.isNaN(score) || score < 0) return 1.0;
        return Math.min(1.0, score);
    }
}
