package dev.aisentinel.core.decision;

import dev.aisentinel.core.baseline.BaselineLifecycle;
import dev.aisentinel.core.baseline.BaselineUpdateContext;
import dev.aisentinel.core.baseline.BaselineUpdateMode;
import dev.aisentinel.core.baseline.BaselineUpdatePolicy;
import dev.aisentinel.core.baseline.ConfigurableBaselineUpdatePolicy;
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
import dev.aisentinel.core.metrics.FailOpenReason;
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
import dev.aisentinel.core.scoring.CompositeScorer;
import dev.aisentinel.core.scoring.IsolationForestScorer;
import dev.aisentinel.core.scoring.StatisticalScorer;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import dev.aisentinel.core.telemetry.TelemetryEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.EnumSet;
import java.util.Set;

/**
 * Framework-independent risk decision for one request: trust evaluation, anomaly scoring, optional risk fusion,
 * policy, trust-policy escalation, statistical-warmup action override, conditional baseline update, optional
 * controlled relearn, and the startup-grace / quarantine overrides.
 * <p>
 * Depends only on {@link HttpRequestView} and core SPIs — no servlet, Spring, or reactive types — so it can be
 * driven directly from tests or from a non-servlet integration. It never writes to the HTTP response; applying the
 * returned {@link RiskDecision#action()} is the caller's responsibility.
 * <p>
 * Baseline learning uses the risk-derived action (after policy and trust adjustment), not operational overrides
 * such as startup grace or quarantine presentation. Statistical warmup always updates so cold-start keys can learn.
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
    private final EnforcementAction statisticalWarmupAction;
    private final BaselineUpdatePolicy baselineUpdatePolicy;
    private final BaselineUpdateMode baselineUpdateMode;
    private final BaselineLifecycle baselineLifecycle;

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
        this(scorer, policyEngine, enforcementHandler, telemetry, startupGrace, metrics,
            trustEvaluator, trustPolicyAdjuster, riskFusion, EnforcementAction.MONITOR,
            ConfigurableBaselineUpdatePolicy.allowOrMonitor(), BaselineLifecycle.disabled());
    }

    public SentinelDecisionEngine(AnomalyScorer scorer,
                                  PolicyEngine policyEngine,
                                  EnforcementHandler enforcementHandler,
                                  TelemetryEmitter telemetry,
                                  StartupGrace startupGrace,
                                  SentinelMetrics metrics,
                                  TrustEvaluator trustEvaluator,
                                  TrustPolicyAdjuster trustPolicyAdjuster,
                                  RequestRiskFusion riskFusion,
                                  EnforcementAction statisticalWarmupAction) {
        this(scorer, policyEngine, enforcementHandler, telemetry, startupGrace, metrics,
            trustEvaluator, trustPolicyAdjuster, riskFusion, statisticalWarmupAction,
            ConfigurableBaselineUpdatePolicy.allowOrMonitor(), BaselineLifecycle.disabled());
    }

    public SentinelDecisionEngine(AnomalyScorer scorer,
                                  PolicyEngine policyEngine,
                                  EnforcementHandler enforcementHandler,
                                  TelemetryEmitter telemetry,
                                  StartupGrace startupGrace,
                                  SentinelMetrics metrics,
                                  TrustEvaluator trustEvaluator,
                                  TrustPolicyAdjuster trustPolicyAdjuster,
                                  RequestRiskFusion riskFusion,
                                  EnforcementAction statisticalWarmupAction,
                                  BaselineUpdatePolicy baselineUpdatePolicy) {
        this(scorer, policyEngine, enforcementHandler, telemetry, startupGrace, metrics,
            trustEvaluator, trustPolicyAdjuster, riskFusion, statisticalWarmupAction,
            baselineUpdatePolicy, BaselineLifecycle.disabled());
    }

    /**
     * @param statisticalWarmupAction action applied when evaluation includes {@link EvaluationStatus#STATISTICAL_WARMUP}
     *                                (default {@link EnforcementAction#MONITOR}); must be {@link EnforcementAction#ALLOW}
     *                                or {@link EnforcementAction#MONITOR} (other values are normalized to {@code MONITOR})
     * @param baselineUpdatePolicy    decides whether {@link AnomalyScorer#update} runs after the risk decision
     * @param baselineLifecycle       controlled relearn / reset (default disabled)
     */
    public SentinelDecisionEngine(AnomalyScorer scorer,
                                  PolicyEngine policyEngine,
                                  EnforcementHandler enforcementHandler,
                                  TelemetryEmitter telemetry,
                                  StartupGrace startupGrace,
                                  SentinelMetrics metrics,
                                  TrustEvaluator trustEvaluator,
                                  TrustPolicyAdjuster trustPolicyAdjuster,
                                  RequestRiskFusion riskFusion,
                                  EnforcementAction statisticalWarmupAction,
                                  BaselineUpdatePolicy baselineUpdatePolicy,
                                  BaselineLifecycle baselineLifecycle) {
        this.scorer = scorer;
        this.policyEngine = policyEngine;
        this.enforcementHandler = enforcementHandler;
        this.telemetry = telemetry;
        this.startupGrace = startupGrace != null ? startupGrace : StartupGrace.NEVER;
        this.metrics = metrics != null ? metrics : SentinelMetrics.NOOP;
        this.trustEvaluator = trustEvaluator != null ? trustEvaluator : NoopTrustEvaluator.INSTANCE;
        this.trustPolicyAdjuster = trustPolicyAdjuster != null ? trustPolicyAdjuster : NoopTrustPolicyAdjuster.INSTANCE;
        this.riskFusion = riskFusion != null ? riskFusion : NoopRequestRiskFusion.INSTANCE;
        this.statisticalWarmupAction = normalizeWarmupAction(statisticalWarmupAction);
        BaselineUpdatePolicy policy = baselineUpdatePolicy != null
            ? baselineUpdatePolicy
            : ConfigurableBaselineUpdatePolicy.allowOrMonitor();
        this.baselineUpdatePolicy = policy;
        this.baselineUpdateMode = policy instanceof ConfigurableBaselineUpdatePolicy configurable
            ? configurable.mode()
            : BaselineUpdateMode.ALLOW_OR_MONITOR;
        this.baselineLifecycle = baselineLifecycle != null ? baselineLifecycle : BaselineLifecycle.disabled();
    }

    static EnforcementAction normalizeWarmupAction(EnforcementAction action) {
        if (action == null) {
            return EnforcementAction.MONITOR;
        }
        return switch (action) {
            case ALLOW, MONITOR -> action;
            case THROTTLE, BLOCK, QUARANTINE -> EnforcementAction.MONITOR;
        };
    }

    /**
     * Evaluates the request. Trust evaluation, fusion, and trust-policy failures are fail-open (logged, counted,
     * ignored); a scoring failure aborts the decision entirely.
     * <p>
     * Never writes to the HTTP response. Does enrich {@code ctx} in place (trust evaluation, fused risk,
     * trust-policy detail) — same side-effect contract as earlier pipeline evaluation.
     * <p>
     * Flow: score → fusion/policy/trust → risk action → baseline-update policy → conditional update →
     * optional controlled relearn → warmup/grace/quarantine enforcement presentation.
     *
     * @param ctx per-request context; enriched in-place with trust, fusion, and policy detail keys
     * @return the decision, or {@code null} when scoring failed and the caller must fail open
     */
    public RiskDecision evaluate(HttpRequestView request, String identityHash, RequestFeatures features,
                                 RequestContext ctx) {
        boolean optionalPathDegraded = false;
        IdentityContext identityCtx = ctx.get(IdentityContextKeys.IDENTITY_CONTEXT, IdentityContext.class);
        if (identityCtx != null) {
            try {
                TrustEvaluation te = trustEvaluator.evaluate(identityCtx, request, features, ctx);
                if (te != null) {
                    ctx.put(IdentityContextKeys.IDENTITY_CONTEXT,
                        identityCtx.withTrustAndRisk(te.trustScore(), te.riskSignals()));
                }
            } catch (Exception e) {
                log.debug("Trust evaluation failed for {} (fail-open reason={}): {}: {}",
                    features.endpoint(), FailOpenReason.TRUST_EVALUATION_FAILURE,
                    e.getClass().getSimpleName(), e.getMessage());
                recordFailOpen(FailOpenReason.TRUST_EVALUATION_FAILURE, features.endpoint());
                optionalPathDegraded = true;
            }
        }

        double rawScore;
        long scoreStart = System.nanoTime();
        Set<EvaluationStatus> evaluationStatuses;
        IsolationForestScorer.LastScoreMode ifModeForStatuses = null;
        try {
            if (scorer instanceof CompositeScorer compositeScorer) {
                CompositeScorer.CompositeScoreOutcome outcome = compositeScorer.scoreWithExplanation(features);
                rawScore = outcome.score();
                DecisionExplanationEvidence evidence = DecisionExplanationEvidence.fromComposite(outcome);
                if (evidence != null) {
                    ctx.put(ExplanationContextKeys.DECISION_EXPLANATION, evidence);
                }
                if (outcome.compositeSnapshot() != null && outcome.compositeSnapshot().isolationForestScoreMode() != null) {
                    ifModeForStatuses = IsolationForestScorer.LastScoreMode.valueOf(
                        outcome.compositeSnapshot().isolationForestScoreMode());
                }
            } else if (scorer instanceof StatisticalScorer statisticalScorer) {
                StatisticalScorer.StatisticalScoreOutcome outcome = statisticalScorer.scoreWithExplanation(features);
                rawScore = outcome.score();
                ctx.put(ExplanationContextKeys.DECISION_EXPLANATION,
                    DecisionExplanationEvidence.fromStatistical(outcome.score(), outcome.snapshot()));
            } else if (scorer instanceof IsolationForestScorer isolationForestScorer) {
                IsolationForestScorer.IsolationForestScoreOutcome outcome =
                    isolationForestScorer.scoreWithMode(features);
                rawScore = outcome.score();
                ifModeForStatuses = outcome.mode();
                ctx.put(ExplanationContextKeys.DECISION_EXPLANATION, new DecisionExplanationEvidence(
                    null,
                    outcome.score(),
                    outcome.mode() == IsolationForestScorer.LastScoreMode.MODEL,
                    outcome.mode().name(),
                    null,
                    System.currentTimeMillis()
                ));
            } else {
                rawScore = scorer.score(features);
            }
            // Collect before update so STATISTICAL_WARMUP matches the score just returned.
            evaluationStatuses = EvaluationStatusCollector.collect(scorer, features, ifModeForStatuses);
        } catch (Exception e) {
            log.debug("Scoring failed for {} (fail-open reason={}): {}: {}",
                features.endpoint(), FailOpenReason.SCORER_FAILURE,
                e.getClass().getSimpleName(), e.getMessage());
            metrics.recordScoringError();
            recordFailOpen(FailOpenReason.SCORER_FAILURE, features.endpoint());
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
                    log.debug("Risk fusion failed for {} (fail-open reason={}): {}: {}",
                        features.endpoint(), FailOpenReason.RISK_FUSION_FAILURE,
                        e.getClass().getSimpleName(), e.getMessage());
                    recordFailOpen(FailOpenReason.RISK_FUSION_FAILURE, features.endpoint());
                    optionalPathDegraded = true;
                }
            }
        }

        EnforcementAction riskAction = policyEngine.evaluate(policyScore, features, features.endpoint());
        try {
            TrustPolicyAdjustment tp = trustPolicyAdjuster.adjust(riskAction, score, features, features.endpoint(),
                request, ctx);
            riskAction = tp.action();
            if (tp.trustPolicyDetail() != null && !tp.trustPolicyDetail().isBlank()) {
                ctx.put(TrustPolicyContextKeys.TRUST_POLICY_DETAIL, tp.trustPolicyDetail());
            }
        } catch (Exception e) {
            log.debug("Trust policy adjustment failed for {} (fail-open reason={}): {}: {}",
                features.endpoint(), FailOpenReason.TRUST_POLICY_FAILURE,
                e.getClass().getSimpleName(), e.getMessage());
            recordFailOpen(FailOpenReason.TRUST_POLICY_FAILURE, features.endpoint());
            optionalPathDegraded = true;
        }

        boolean warmup = evaluationStatuses.contains(EvaluationStatus.STATISTICAL_WARMUP);
        BaselineUpdateContext updateContext = new BaselineUpdateContext(
            score, policyScore, riskAction, evaluationStatuses);
        boolean shouldUpdate;
        try {
            shouldUpdate = baselineUpdatePolicy.shouldUpdate(updateContext);
        } catch (Exception e) {
            log.debug("Baseline-update policy evaluation failed for {} (fail-open reason={}): {}: {}",
                features.endpoint(), FailOpenReason.BASELINE_UPDATE_POLICY_FAILURE,
                e.getClass().getSimpleName(), e.getMessage());
            recordFailOpen(FailOpenReason.BASELINE_UPDATE_POLICY_FAILURE, features.endpoint());
            optionalPathDegraded = true;
            shouldUpdate = false;
        }
        EnumSet<EvaluationStatus> statuses = EnumSet.copyOf(evaluationStatuses);
        if (optionalPathDegraded) {
            statuses.add(EvaluationStatus.DEGRADED);
            statuses.remove(EvaluationStatus.COMPLETE);
        }
        if (shouldUpdate) {
            try {
                scorer.update(features);
                baselineLifecycle.onUpdateAccepted(features);
                metrics.recordBaselineUpdateAccepted(baselineUpdateMode.name(), warmup);
            } catch (Exception e) {
                log.debug("Baseline update failed for {} (fail-open reason={}): {}: {}",
                    features.endpoint(), FailOpenReason.BASELINE_UPDATE_FAILURE,
                    e.getClass().getSimpleName(), e.getMessage());
                metrics.recordScoringError();
                recordFailOpen(FailOpenReason.BASELINE_UPDATE_FAILURE, features.endpoint());
                return null;
            }
        } else {
            statuses.add(EvaluationStatus.BASELINE_UPDATE_SKIPPED);
            metrics.recordBaselineUpdateSkipped(baselineUpdateMode.name());
            if (baselineLifecycle.onUpdateSkipped(features)) {
                statuses.add(EvaluationStatus.BASELINE_RELEARNED);
            }
        }

        EnforcementAction action = riskAction;
        // Warmup is a lifecycle state: do not treat the numeric warmup score as confirmed elevated risk.
        if (warmup) {
            action = statisticalWarmupAction;
        }

        Set<EvaluationStatus> finalStatuses = Set.copyOf(statuses);
        metrics.recordEvaluationStatuses(finalStatuses);
        telemetry.emit(TelemetryEvent.threatScored(
            identityHash, features.endpoint(), policyScore, finalStatuses,
            isolationForestScoreModeForTelemetry(ifModeForStatuses)));
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
        return new RiskDecision(action, score, policyScore, features, ctx, startupGraceActive, finalStatuses);
    }

    private void recordFailOpen(FailOpenReason reason, String endpoint) {
        metrics.recordFailOpen(reason);
        telemetry.emit(TelemetryEvent.failOpen(reason, endpoint));
    }

    /**
     * Prefers the request-owned IF mode from this evaluation's own scoring invocation; only falls back
     * to the shared, diagnostic-only {@link IsolationForestScorer#lastScoreMode()} when this evaluation
     * did not go through a recognized {@link CompositeScorer}/{@link IsolationForestScorer} path (custom
     * {@link AnomalyScorer} wiring) and therefore never computed a request-scoped mode. Never overrides
     * an already-known request-scoped value with shared state.
     */
    private String isolationForestScoreModeForTelemetry(IsolationForestScorer.LastScoreMode requestScopedModeOrNull) {
        if (requestScopedModeOrNull != null) {
            return requestScopedModeOrNull.name();
        }
        IsolationForestScorer ifScorer = findIsolationForestScorer(scorer);
        return ifScorer != null ? ifScorer.lastScoreMode().name() : null;
    }

    private static IsolationForestScorer findIsolationForestScorer(AnomalyScorer root) {
        if (root instanceof IsolationForestScorer isolationForest) {
            return isolationForest;
        }
        if (root instanceof CompositeScorer composite) {
            for (AnomalyScorer child : composite.scorersView()) {
                IsolationForestScorer found = findIsolationForestScorer(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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
