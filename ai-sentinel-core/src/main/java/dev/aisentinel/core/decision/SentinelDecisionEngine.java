package dev.aisentinel.core.decision;

import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.fusion.FusedRisk;
import dev.aisentinel.core.fusion.FusionContextKeys;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.fusion.RequestRiskFusion;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.identity.IdentityContextKeys;
import dev.aisentinel.core.identity.model.IdentityContext;
import dev.aisentinel.core.identity.model.TrustEvaluation;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
import dev.aisentinel.core.identity.spi.TrustEvaluator;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.NoopTrustPolicyAdjuster;
import dev.aisentinel.core.policy.PolicyEngine;
import dev.aisentinel.core.policy.TrustPolicyAdjuster;
import dev.aisentinel.core.policy.TrustPolicyAdjustment;
import dev.aisentinel.core.policy.TrustPolicyContextKeys;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import dev.aisentinel.core.telemetry.TelemetryEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * Framework-independent risk decision for one request: trust evaluation, anomaly scoring, optional risk fusion,
 * policy, trust-policy escalation, and the startup-grace / quarantine overrides.
 * <p>
 * Depends only on {@link HttpRequestView} and core SPIs — no servlet, Spring, or reactive types — so it can be
 * driven directly from tests or from a non-servlet integration. It never writes to the HTTP response; applying the
 * returned {@link RiskDecision#action()} is the caller's responsibility.
 */
@Slf4j
public final class SentinelDecisionEngine {

    private final AnomalyScorer scorer;
    private final PolicyEngine policyEngine;
    private final EnforcementHandler enforcementHandler;
    private final TelemetryEmitter telemetry;
    private final StartupGrace startupGrace;
    private final SentinelMetrics metrics;
    private final TrustEvaluator trustEvaluator;
    private final TrustPolicyAdjuster trustPolicyAdjuster;
    private final RequestRiskFusion riskFusion;

    /**
     * @param enforcementHandler consulted only for {@link EnforcementHandler#isQuarantined(String, String)}
     */
    public SentinelDecisionEngine(AnomalyScorer scorer,
                                  PolicyEngine policyEngine,
                                  EnforcementHandler enforcementHandler,
                                  TelemetryEmitter telemetry,
                                  StartupGrace startupGrace,
                                  SentinelMetrics metrics,
                                  TrustEvaluator trustEvaluator,
                                  TrustPolicyAdjuster trustPolicyAdjuster,
                                  RequestRiskFusion riskFusion) {
        this.scorer = scorer;
        this.policyEngine = policyEngine;
        this.enforcementHandler = enforcementHandler;
        this.telemetry = telemetry;
        this.startupGrace = startupGrace != null ? startupGrace : StartupGrace.NEVER;
        this.metrics = metrics != null ? metrics : SentinelMetrics.NOOP;
        this.trustEvaluator = trustEvaluator != null ? trustEvaluator : NoopTrustEvaluator.INSTANCE;
        this.trustPolicyAdjuster = trustPolicyAdjuster != null ? trustPolicyAdjuster : NoopTrustPolicyAdjuster.INSTANCE;
        this.riskFusion = riskFusion != null ? riskFusion : NoopRequestRiskFusion.INSTANCE;
    }

    /**
     * Evaluates the request. Trust evaluation, fusion, and trust-policy failures are fail-open (logged, counted,
     * ignored); a scoring failure aborts the decision entirely.
     * <p>
     * Never writes to the HTTP response. Does enrich {@code ctx} in place (trust evaluation, fused risk,
     * trust-policy detail) — same side-effect contract as the pre-extraction pipeline logic.
     *
     * @param ctx per-request context; enriched in-place with trust, fusion, and policy detail keys
     * @return the decision, or {@code null} when scoring failed and the caller must fail open
     */
    public RiskDecision evaluate(HttpRequestView request, String identityHash, RequestFeatures features,
                                 RequestContext ctx) {
        IdentityContext identityCtx = ctx.get(IdentityContextKeys.IDENTITY_CONTEXT, IdentityContext.class);
        if (identityCtx != null) {
            try {
                TrustEvaluation te = trustEvaluator.evaluate(identityCtx, request, features, ctx);
                if (te != null) {
                    ctx.put(IdentityContextKeys.IDENTITY_CONTEXT,
                        identityCtx.withTrustAndRisk(te.trustScore(), te.riskSignals()));
                }
            } catch (Exception e) {
                log.debug("Trust evaluation failed for {}: {}", features.endpoint(), e.getMessage());
                metrics.recordFailOpen();
            }
        }

        double rawScore;
        long scoreStart = System.nanoTime();
        try {
            rawScore = scorer.score(features);
            scorer.update(features);
        } catch (Exception e) {
            log.debug("Scoring failed for {}: {}", features.endpoint(), e.getMessage());
            metrics.recordScoringError();
            metrics.recordFailOpen();
            return null;
        } finally {
            metrics.recordScoringLatencyNanos(System.nanoTime() - scoreStart);
        }

        // Safety net for custom AnomalyScorer beans that return NaN/negative without going through
        // CompositeScorer. Default wiring clamps inside CompositeScorer first (and records this metric
        // there), so this branch does not double-count under the stock CompositeScorer path.
        if (Double.isNaN(rawScore) || rawScore < 0) {
            metrics.recordNanOrNegativeScoreClamped();
        }
        double score = clampScore(rawScore);

        double policyScore = score;
        if (riskFusion.enabled()) {
            IdentityContext identityForFusion = ctx.get(IdentityContextKeys.IDENTITY_CONTEXT, IdentityContext.class);
            if (identityForFusion != null) {
                try {
                    double trust = identityForFusion.trust().value();
                    if (!Double.isNaN(trust)) {
                        FusedRisk fused = riskFusion.fuse(score, trust);
                        policyScore = fused.fusedScore();
                        ctx.put(FusionContextKeys.FUSED_RISK, fused);
                    }
                } catch (Exception e) {
                    log.debug("Risk fusion failed for {}: {}", features.endpoint(), e.getMessage());
                    metrics.recordFailOpen();
                }
            }
        }

        EnforcementAction action = policyEngine.evaluate(policyScore, features, features.endpoint());
        try {
            TrustPolicyAdjustment tp = trustPolicyAdjuster.adjust(action, score, features, features.endpoint(),
                request, ctx);
            action = tp.action();
            if (tp.trustPolicyDetail() != null && !tp.trustPolicyDetail().isBlank()) {
                ctx.put(TrustPolicyContextKeys.TRUST_POLICY_DETAIL, tp.trustPolicyDetail());
            }
        } catch (Exception e) {
            log.debug("Trust policy adjustment failed for {}: {}", features.endpoint(), e.getMessage());
            metrics.recordFailOpen();
        }

        telemetry.emit(TelemetryEvent.threatScored(identityHash, features.endpoint(), policyScore));
        if (score > 0.5) {
            telemetry.emit(TelemetryEvent.anomalyDetected(identityHash, features.endpoint(), score));
        }

        boolean startupGraceActive = startupGrace.isGraceActive();
        if (startupGraceActive) {
            action = EnforcementAction.MONITOR;
        } else if (enforcementHandler.isQuarantined(identityHash, features.endpoint())) {
            action = EnforcementAction.QUARANTINE;
        }

        metrics.recordPolicyAction(action);
        return new RiskDecision(action, score, policyScore, features, ctx, startupGraceActive);
    }

    /**
     * Prevents NaN or out-of-range scores from causing policy bypass; treat NaN as high risk.
     * Fusion runs only after this clamp, so {@link dev.aisentinel.core.fusion.DeterministicRequestRiskFusion}'s internal
     * {@code clamp01} treating NaN as 0 is not used for raw scorer output on the request path.
     */
    static double clampScore(double score) {
        if (Double.isNaN(score) || score < 0) return 1.0;
        return Math.min(1.0, score);
    }
}
