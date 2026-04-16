package io.aisentinel.core;

import io.aisentinel.core.enforcement.EnforcementHandler;
import io.aisentinel.core.enforcement.EnforcementScope;
import io.aisentinel.core.feature.FeatureExtractor;
import io.aisentinel.core.identity.IdentityContextKeys;
import io.aisentinel.core.identity.model.IdentityContext;
import io.aisentinel.core.identity.model.TrustEvaluation;
import io.aisentinel.core.identity.spi.IdentityContextResolver;
import io.aisentinel.core.identity.spi.IdentityResponseHook;
import io.aisentinel.core.identity.spi.NoopIdentityContextResolver;
import io.aisentinel.core.identity.spi.NoopIdentityResponseHook;
import io.aisentinel.core.identity.spi.NoopTrustEvaluator;
import io.aisentinel.core.identity.spi.TrustEvaluator;
import io.aisentinel.core.model.RequestContext;
import io.aisentinel.core.model.RequestFeatures;
import io.aisentinel.core.policy.EnforcementAction;
import io.aisentinel.core.policy.NoopTrustPolicyAdjuster;
import io.aisentinel.core.policy.PolicyEngine;
import io.aisentinel.core.policy.TrustPolicyAdjuster;
import io.aisentinel.core.policy.TrustPolicyAdjustment;
import io.aisentinel.core.policy.TrustPolicyContextKeys;
import io.aisentinel.core.metrics.SentinelMetrics;
import io.aisentinel.core.runtime.StartupGrace;
import io.aisentinel.core.scoring.AnomalyScorer;
import io.aisentinel.core.scoring.CompositeScorer;
import io.aisentinel.core.fusion.FusedRisk;
import io.aisentinel.core.fusion.FusionContextKeys;
import io.aisentinel.core.fusion.NoopRequestRiskFusion;
import io.aisentinel.core.fusion.RequestRiskFusion;
import io.aisentinel.core.telemetry.TelemetryEmitter;
import io.aisentinel.core.telemetry.TelemetryEvent;
import io.aisentinel.distributed.training.NoopTrainingCandidatePublisher;
import io.aisentinel.distributed.training.TrainingCandidatePublishRequest;
import io.aisentinel.distributed.training.TrainingCandidatePublisher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Central orchestration of feature extraction, scoring, policy, enforcement, telemetry, metrics, and optional
 * training export. Invoked from the servlet filter (Spring Boot starter) on each request.
 * <p>
 * Request-path invariants: scoring and policy run synchronously; training publish is async and fail-open; failures in
 * scoring may fail-open per pipeline logic without blocking indefinitely.
 */
@Slf4j
public final class SentinelPipeline {

    private final FeatureExtractor featureExtractor;
    private final AnomalyScorer scorer;
    private final CompositeScorer compositeScorerOrNull;
    private final PolicyEngine policyEngine;
    private final EnforcementHandler enforcementHandler;
    private final TelemetryEmitter telemetry;
    private final StartupGrace startupGrace;
    private final SentinelMetrics metrics;
    private final TrainingCandidatePublisher trainingCandidatePublisher;
    private final EnforcementScope enforcementScope;
    private final String trainingTenantId;
    private final String trainingNodeId;
    private final String sentinelModeName;
    private final IdentityContextResolver identityContextResolver;
    private final TrustEvaluator trustEvaluator;
    private final TrustPolicyAdjuster trustPolicyAdjuster;
    private final IdentityResponseHook identityResponseHook;
    private final RequestRiskFusion riskFusion;

    public SentinelPipeline(FeatureExtractor featureExtractor, AnomalyScorer scorer, PolicyEngine policyEngine,
                            EnforcementHandler enforcementHandler, TelemetryEmitter telemetry, StartupGrace startupGrace,
                            SentinelMetrics metrics) {
        this(featureExtractor, scorer, null, policyEngine, enforcementHandler, telemetry, startupGrace, metrics,
            NoopTrainingCandidatePublisher.INSTANCE, EnforcementScope.IDENTITY_ENDPOINT, "default", "", "ENFORCE",
            NoopIdentityContextResolver.INSTANCE, NoopTrustEvaluator.INSTANCE, NoopTrustPolicyAdjuster.INSTANCE,
            NoopIdentityResponseHook.INSTANCE, NoopRequestRiskFusion.INSTANCE);
    }

    public SentinelPipeline(FeatureExtractor featureExtractor,
                            AnomalyScorer scorer,
                            CompositeScorer compositeScorerOrNull,
                            PolicyEngine policyEngine,
                            EnforcementHandler enforcementHandler,
                            TelemetryEmitter telemetry,
                            StartupGrace startupGrace,
                            SentinelMetrics metrics,
                            TrainingCandidatePublisher trainingCandidatePublisher,
                            EnforcementScope enforcementScope,
                            String trainingTenantId,
                            String trainingNodeId,
                            String sentinelModeName,
                            IdentityContextResolver identityContextResolver,
                            TrustEvaluator trustEvaluator,
                            TrustPolicyAdjuster trustPolicyAdjuster,
                            IdentityResponseHook identityResponseHook,
                            RequestRiskFusion riskFusion) {
        this.featureExtractor = featureExtractor;
        this.scorer = scorer;
        this.compositeScorerOrNull = compositeScorerOrNull;
        this.policyEngine = policyEngine;
        this.enforcementHandler = enforcementHandler;
        this.telemetry = telemetry;
        this.startupGrace = startupGrace != null ? startupGrace : StartupGrace.NEVER;
        this.metrics = metrics != null ? metrics : SentinelMetrics.NOOP;
        this.trainingCandidatePublisher = trainingCandidatePublisher != null
            ? trainingCandidatePublisher
            : NoopTrainingCandidatePublisher.INSTANCE;
        this.enforcementScope = enforcementScope != null ? enforcementScope : EnforcementScope.IDENTITY_ENDPOINT;
        this.trainingTenantId = trainingTenantId != null && !trainingTenantId.isBlank() ? trainingTenantId : "default";
        this.trainingNodeId = trainingNodeId != null ? trainingNodeId : "";
        this.sentinelModeName = sentinelModeName != null ? sentinelModeName : "ENFORCE";
        this.identityContextResolver = identityContextResolver != null ? identityContextResolver : NoopIdentityContextResolver.INSTANCE;
        this.trustEvaluator = trustEvaluator != null ? trustEvaluator : NoopTrustEvaluator.INSTANCE;
        this.trustPolicyAdjuster = trustPolicyAdjuster != null ? trustPolicyAdjuster : NoopTrustPolicyAdjuster.INSTANCE;
        this.identityResponseHook = identityResponseHook != null ? identityResponseHook : NoopIdentityResponseHook.INSTANCE;
        this.riskFusion = riskFusion != null ? riskFusion : NoopRequestRiskFusion.INSTANCE;
    }

    /**
     * Process request. Returns true if request should proceed (doFilter), false if already responded.
     */
    public boolean process(HttpServletRequest request, HttpServletResponse response, String identityHash) {
        long pipelineStart = System.nanoTime();
        RequestContext ctx = new RequestContext();
        RequestFeatures features = null;
        boolean hookEligible = false;
        boolean returnValue = true;
        try {
            try {
                identityContextResolver.resolve(request, identityHash, ctx);
            } catch (Exception e) {
                log.debug("Identity resolution failed for {}: {}", request.getRequestURI(), e.getMessage());
                metrics.recordFailOpen();
            }

            try {
                features = featureExtractor.extract(request, identityHash, ctx);
            } catch (Exception e) {
                log.debug("Feature extraction failed for {}: {}", request.getRequestURI(), e.getMessage());
                metrics.recordFailOpen();
                returnValue = true;
                return returnValue;
            }

            hookEligible = true;

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
                returnValue = true;
                return returnValue;
            } finally {
                metrics.recordScoringLatencyNanos(System.nanoTime() - scoreStart);
            }

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
            boolean proceed = enforcementHandler.apply(action, request, response, identityHash, features.endpoint());

            offerTrainingCandidate(features, identityHash, action, score, proceed, startupGraceActive, ctx);

            returnValue = proceed;
            return returnValue;
        } finally {
            metrics.recordPipelineLatencyNanos(System.nanoTime() - pipelineStart);
            if (hookEligible) {
                try {
                    identityResponseHook.afterPipeline(request, response, identityHash, features, ctx, returnValue);
                } catch (Exception e) {
                    log.debug("Identity response hook failed: {}", e.getMessage());
                }
            }
        }
    }

    private void offerTrainingCandidate(RequestFeatures features, String identityHash, EnforcementAction action,
                                        double rawAnomalyScoreClamped,
                                        boolean requestProceeded,
                                        boolean startupGraceActive,
                                        RequestContext ctx) {
        try {
            Double statisticalScore = null;
            Double isolationForestScore = null;
            if (compositeScorerOrNull != null) {
                var snap = compositeScorerOrNull.getLastCompositeScoreSnapshot();
                if (snap != null) {
                    double st = snap.statistical();
                    statisticalScore = Double.isNaN(st) ? null : st;
                    isolationForestScore = snap.isolationForest();
                }
            }
            FusedRisk fusedRisk = ctx.get(FusionContextKeys.FUSED_RISK, FusedRisk.class);
            Double trustForTraining = fusedRisk != null ? fusedRisk.trustScore() : null;
            Double fusedForTraining = fusedRisk != null ? fusedRisk.fusedScore() : null;
            trainingCandidatePublisher.publish(new TrainingCandidatePublishRequest(
                features,
                action,
                rawAnomalyScoreClamped,
                statisticalScore,
                isolationForestScore,
                enforcementScope,
                trainingTenantId,
                trainingNodeId,
                sentinelModeName,
                requestProceeded,
                startupGraceActive,
                trustForTraining,
                fusedForTraining
            ));
        } catch (Exception e) {
            log.debug("Training candidate publisher failed: {}", e.toString());
            metrics.recordTrainingCandidatePublishUnexpectedFailure();
        }
    }

    /**
     * Prevents NaN or out-of-range scores from causing policy bypass; treat NaN as high risk.
     * Fusion runs only after this clamp, so {@link io.aisentinel.core.fusion.DeterministicRequestRiskFusion}'s internal
     * {@code clamp01} treating NaN as 0 is not used for raw scorer output on the request path.
     */
    private static double clampScore(double score) {
        if (Double.isNaN(score) || score < 0) return 1.0;
        return Math.min(1.0, score);
    }
}
