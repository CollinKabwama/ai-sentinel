package dev.aisentinel.core.decision;

/**
 * Lifecycle / degradation markers for a single {@link RiskDecision}.
 * <p>
 * A decision may carry multiple statuses (e.g. statistical live + model fallback).
 * {@link #COMPLETE} means no degradation or fallback occurred and must not be combined with
 * {@link #MODEL_FALLBACK_USED}, {@link #MODEL_UNAVAILABLE}, {@link #STATISTICAL_WARMUP},
 * {@link #DEGRADED}, {@link #INVALID_SCORE}, or {@link #REMOTE_EVALUATION_FAILURE}.
 * {@link #BASELINE_UPDATE_SKIPPED} may appear alongside {@link #COMPLETE} or {@link #STATISTICAL_LIVE}.
 * <p>
 * <b>Operator-facing aliases</b> (see {@link OperatorEvaluationPhase}):
 * <ul>
 *   <li>{@code WARMUP} ← {@link #STATISTICAL_WARMUP}</li>
 *   <li>{@code LIVE} ← {@link #STATISTICAL_LIVE} / {@link #COMPLETE}</li>
 *   <li>{@code MODEL_FALLBACK} ← {@link #MODEL_FALLBACK_USED} (+ {@link #MODEL_UNAVAILABLE} when no model)</li>
 *   <li>{@code DEGRADED} ← {@link #DEGRADED}</li>
 *   <li>{@code FAIL_OPEN} ← not a decision status; see {@link dev.aisentinel.core.metrics.FailOpenReason}
 *       (also used with {@link #REMOTE_EVALUATION_FAILURE} transport failures)</li>
 * </ul>
 */
public enum EvaluationStatus {

    /** No degradation or fallback; live evaluation completed normally. */
    COMPLETE,

    /** Statistical baseline lacks enough samples for live z-score scoring. */
    STATISTICAL_WARMUP,

    /** Statistical scorer evaluated against an established baseline. */
    STATISTICAL_LIVE,

    /** A configured Isolation Forest model is not loaded. */
    MODEL_UNAVAILABLE,

    /** Configured IF fallback score was used instead of model inference. */
    MODEL_FALLBACK_USED,

    /**
     * An optional request-path subsystem failed but a full decision was still produced
     * (trust evaluation, risk fusion, or trust-policy adjustment). Distinct from
     * {@link #MODEL_FALLBACK_USED} (model path) and from fail-open-without-decision
     * ({@link dev.aisentinel.core.metrics.FailOpenReason} only).
     */
    DEGRADED,

    /**
     * Online baseline / scorer {@code update} was skipped by the configured
     * {@link dev.aisentinel.core.baseline.BaselineUpdatePolicy}.
     */
    BASELINE_UPDATE_SKIPPED,

    /**
     * Statistical Welford state for this identity|endpoint was reset on this request
     * (explicit operator relearn). The next observations re-enter {@link #STATISTICAL_WARMUP}.
     */
    BASELINE_RELEARNED,

    /**
     * The anomaly scorer returned a numeric result that cannot be interpreted as a valid risk score
     * ({@code NaN}, {@code ±Infinity}, or a negative finite value). This is <em>not</em> high risk,
     * not a scorer exception ({@link dev.aisentinel.core.metrics.FailOpenReason#SCORER_FAILURE}),
     * and not optional-subsystem {@link #DEGRADED}. Policy must not run on the invalid scalar;
     * the presented action is fail-open {@link dev.aisentinel.core.policy.EnforcementAction#ALLOW}
     * unless existing quarantine state already applies.
     */
    INVALID_SCORE,

    /**
     * Remote evaluation transport/client failure (timeout, auth, malformed response, unreachable service).
     * This is <em>not</em> high risk, not {@link #INVALID_SCORE}, and not a successful evaluation.
     * Callers should fail-open the application request while exposing this diagnostic status.
     */
    REMOTE_EVALUATION_FAILURE
}
