package dev.aisentinel.core.metrics;

/**
 * Structured reason codes for request-path fail-open (availability over strict denial).
 * <p>
 * Fail-open <em>decisions</em> are unchanged by recording a reason: the request is still allowed.
 * Reasons are for metrics, telemetry, and logs so operators can distinguish causes instead of a
 * generic {@code fallback} signal.
 * <p>
 * Operator model mapping:
 * <ul>
 *   <li>{@code FAIL_OPEN} — request allowed without a complete {@link dev.aisentinel.core.decision.RiskDecision}
 *       (scorer/update abort, feature extraction failure, filter catch-all), or after an optional
 *       subsystem exception that continues with a degraded decision</li>
 *   <li>Every reason increments {@code aisentinel.failopen.reason}. Structured {@code FailOpen}
 *       telemetry is emitted for decision-engine reasons; pipeline/filter/lifecycle reasons are
 *       metrics + logs only — see {@code docs/deployment.md} failure-mode profile</li>
 *   <li>Distributed Redis quarantine/throttle/trust fail-open uses dedicated meters
 *       ({@code aisentinel.distributed.*}, {@code aisentinel.identity.trust.baseline.redis.*});
 *       those paths are not duplicated here</li>
 * </ul>
 */
public enum FailOpenReason {

    /** {@link dev.aisentinel.core.scoring.AnomalyScorer#score} threw. */
    SCORER_FAILURE,

    /** Online baseline / scorer {@code update} threw after a successful score. */
    BASELINE_UPDATE_FAILURE,

    /** Trust evaluator threw; decision continues without trust enrichment. */
    TRUST_EVALUATION_FAILURE,

    /** Risk fusion threw; decision continues with anomaly score only. */
    RISK_FUSION_FAILURE,

    /** Trust-policy adjuster threw; decision continues with pre-adjust action. */
    TRUST_POLICY_FAILURE,

    /** Baseline-update policy threw; treated as skip (no update). */
    BASELINE_UPDATE_POLICY_FAILURE,

    /** Identity context resolver threw; pipeline continues. */
    IDENTITY_RESOLUTION_FAILURE,

    /** Feature extraction threw; request allowed without scoring. */
    FEATURE_EXTRACTION_FAILURE,

    /** Servlet filter catch-all around the pipeline. */
    FILTER_FAILURE,

    /** Explicit baseline reset / lifecycle hook threw. */
    BASELINE_LIFECYCLE_FAILURE
}
