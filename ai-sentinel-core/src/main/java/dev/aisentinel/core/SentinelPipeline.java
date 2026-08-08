package dev.aisentinel.core;

import dev.aisentinel.core.baseline.BaselineLifecycle;
import dev.aisentinel.core.baseline.BaselineUpdatePolicy;
import dev.aisentinel.core.baseline.ConfigurableBaselineUpdatePolicy;
import dev.aisentinel.core.decision.RiskDecision;
import dev.aisentinel.core.decision.SentinelDecisionEngine;
import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import dev.aisentinel.core.enforcement.EnforcementScope;
import dev.aisentinel.core.feature.FeatureExtractor;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.identity.spi.IdentityContextResolver;
import dev.aisentinel.core.identity.spi.IdentityResponseHook;
import dev.aisentinel.core.identity.spi.NoopIdentityContextResolver;
import dev.aisentinel.core.identity.spi.NoopIdentityResponseHook;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
import dev.aisentinel.core.identity.spi.TrustEvaluator;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.NoopTrustPolicyAdjuster;
import dev.aisentinel.core.policy.PolicyEngine;
import dev.aisentinel.core.policy.TrustPolicyAdjuster;
import dev.aisentinel.core.metrics.FailOpenReason;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.scoring.CompositeScorer;
import dev.aisentinel.core.fusion.FusedRisk;
import dev.aisentinel.core.fusion.FusionContextKeys;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.fusion.RequestRiskFusion;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import dev.aisentinel.distributed.training.NoopTrainingCandidatePublisher;
import dev.aisentinel.distributed.training.TrainingCandidatePublishRequest;
import dev.aisentinel.distributed.training.TrainingCandidatePublisher;
import lombok.extern.slf4j.Slf4j;

/**
 * Central orchestration of identity resolution, feature extraction, the {@link SentinelDecisionEngine}, enforcement,
 * and optional training export. Invoked from the servlet filter (Spring Boot starter) on each request via the
 * framework-neutral {@link HttpRequestView} / {@link EnforcementResponse} boundary.
 * <p>
 * Request-path invariants: scoring and policy run synchronously; training publish is async and fail-open; failures in
 * scoring may fail-open per pipeline logic without blocking indefinitely.
 */
@Slf4j
public final class SentinelPipeline {

    private final FeatureExtractor featureExtractor;
    private final CompositeScorer compositeScorerOrNull;
    private final EnforcementHandler enforcementHandler;
    private final SentinelDecisionEngine decisionEngine;
    private final SentinelMetrics metrics;
    private final TrainingCandidatePublisher trainingCandidatePublisher;
    private final EnforcementScope enforcementScope;
    private final String trainingTenantId;
    private final String trainingNodeId;
    private final String sentinelModeName;
    private final IdentityContextResolver identityContextResolver;
    private final IdentityResponseHook identityResponseHook;

    public SentinelPipeline(FeatureExtractor featureExtractor, AnomalyScorer scorer, PolicyEngine policyEngine,
                            EnforcementHandler enforcementHandler, TelemetryEmitter telemetry, StartupGrace startupGrace,
                            SentinelMetrics metrics) {
        this(featureExtractor, scorer, null, policyEngine, enforcementHandler, telemetry, startupGrace, metrics,
            NoopTrainingCandidatePublisher.INSTANCE, EnforcementScope.IDENTITY_ENDPOINT, "default", "", "ENFORCE",
            NoopIdentityContextResolver.INSTANCE, NoopTrustEvaluator.INSTANCE, NoopTrustPolicyAdjuster.INSTANCE,
            NoopIdentityResponseHook.INSTANCE, NoopRequestRiskFusion.INSTANCE, EnforcementAction.MONITOR,
            ConfigurableBaselineUpdatePolicy.allowOrMonitor());
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
        this(featureExtractor, scorer, compositeScorerOrNull, policyEngine, enforcementHandler, telemetry, startupGrace,
            metrics, trainingCandidatePublisher, enforcementScope, trainingTenantId, trainingNodeId, sentinelModeName,
            identityContextResolver, trustEvaluator, trustPolicyAdjuster, identityResponseHook, riskFusion,
            EnforcementAction.MONITOR, ConfigurableBaselineUpdatePolicy.allowOrMonitor());
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
                            RequestRiskFusion riskFusion,
                            EnforcementAction statisticalWarmupAction) {
        this(featureExtractor, scorer, compositeScorerOrNull, policyEngine, enforcementHandler, telemetry, startupGrace,
            metrics, trainingCandidatePublisher, enforcementScope, trainingTenantId, trainingNodeId, sentinelModeName,
            identityContextResolver, trustEvaluator, trustPolicyAdjuster, identityResponseHook, riskFusion,
            statisticalWarmupAction, ConfigurableBaselineUpdatePolicy.allowOrMonitor());
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
                            RequestRiskFusion riskFusion,
                            EnforcementAction statisticalWarmupAction,
                            BaselineUpdatePolicy baselineUpdatePolicy) {
        this(featureExtractor, scorer, compositeScorerOrNull, policyEngine, enforcementHandler, telemetry, startupGrace,
            metrics, trainingCandidatePublisher, enforcementScope, trainingTenantId, trainingNodeId, sentinelModeName,
            identityContextResolver, trustEvaluator, trustPolicyAdjuster, identityResponseHook, riskFusion,
            statisticalWarmupAction, baselineUpdatePolicy, BaselineLifecycle.disabled());
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
                            RequestRiskFusion riskFusion,
                            EnforcementAction statisticalWarmupAction,
                            BaselineUpdatePolicy baselineUpdatePolicy,
                            BaselineLifecycle baselineLifecycle) {
        this.featureExtractor = featureExtractor;
        this.compositeScorerOrNull = compositeScorerOrNull;
        this.enforcementHandler = enforcementHandler;
        this.metrics = metrics != null ? metrics : SentinelMetrics.NOOP;
        this.decisionEngine = new SentinelDecisionEngine(scorer, policyEngine, enforcementHandler, telemetry,
            startupGrace, this.metrics, trustEvaluator, trustPolicyAdjuster, riskFusion, statisticalWarmupAction,
            baselineUpdatePolicy, baselineLifecycle);
        this.trainingCandidatePublisher = trainingCandidatePublisher != null
            ? trainingCandidatePublisher
            : NoopTrainingCandidatePublisher.INSTANCE;
        this.enforcementScope = enforcementScope != null ? enforcementScope : EnforcementScope.IDENTITY_ENDPOINT;
        this.trainingTenantId = trainingTenantId != null && !trainingTenantId.isBlank() ? trainingTenantId : "default";
        this.trainingNodeId = trainingNodeId != null ? trainingNodeId : "";
        this.sentinelModeName = sentinelModeName != null ? sentinelModeName : "ENFORCE";
        this.identityContextResolver = identityContextResolver != null ? identityContextResolver : NoopIdentityContextResolver.INSTANCE;
        this.identityResponseHook = identityResponseHook != null ? identityResponseHook : NoopIdentityResponseHook.INSTANCE;
    }

    /**
     * Runs identity resolution, feature extraction, decision evaluation, enforcement, and optional training publish.
     *
     * @return {@code true} if the filter chain should continue; {@code false} if the response was already committed
     */
    public boolean process(HttpRequestView request, EnforcementResponse response, String identityHash) {
        long pipelineStart = System.nanoTime();
        RequestContext ctx = new RequestContext();
        RequestFeatures features = null;
        boolean hookEligible = false;
        boolean returnValue = true;
        try {
            try {
                identityContextResolver.resolve(request, identityHash, ctx);
            } catch (Exception e) {
                log.debug("Identity resolution failed for {} (fail-open reason={}): {}: {}",
                    request.getRequestURI(), FailOpenReason.IDENTITY_RESOLUTION_FAILURE,
                    e.getClass().getSimpleName(), e.getMessage());
                metrics.recordFailOpen(FailOpenReason.IDENTITY_RESOLUTION_FAILURE);
            }

            try {
                features = featureExtractor.extract(request, identityHash, ctx);
            } catch (Exception e) {
                log.debug("Feature extraction failed for {} (fail-open reason={}): {}: {}",
                    request.getRequestURI(), FailOpenReason.FEATURE_EXTRACTION_FAILURE,
                    e.getClass().getSimpleName(), e.getMessage());
                metrics.recordFailOpen(FailOpenReason.FEATURE_EXTRACTION_FAILURE);
                returnValue = true;
                return returnValue;
            }

            hookEligible = true;

            RiskDecision decision = decisionEngine.evaluate(request, identityHash, features, ctx);
            if (decision == null) {
                returnValue = true;
                return returnValue;
            }

            EnforcementAction action = decision.action();
            boolean proceed = enforcementHandler.apply(action, request, response, identityHash, features.endpoint());

            offerTrainingCandidate(features, identityHash, action, decision.anomalyScore(), proceed,
                decision.startupGraceActive(), ctx);

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
}
