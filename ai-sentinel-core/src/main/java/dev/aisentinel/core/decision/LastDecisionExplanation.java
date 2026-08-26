package dev.aisentinel.core.decision;

import dev.aisentinel.core.scoring.StatisticalScoreSnapshot;

import java.util.List;

/**
 * JVM-local holder for the most recent completed {@link RiskDecision} explanation (ops / actuator).
 * <p>
 * Not a request history. Overwritten on each successful evaluation in <strong>completion order</strong>
 * (last publisher wins). Contains no identity, endpoint, header, IP, or token fields.
 * Assembled from request-scoped {@link DecisionExplanationEvidence}, never from shared scorer
 * {@code getLast*Snapshot()} getters.
 */
public final class LastDecisionExplanation {

    public static final LastDecisionExplanation NOOP = new LastDecisionExplanation(true);

    private final boolean noop;
    private volatile Snapshot last;

    public LastDecisionExplanation() {
        this(false);
    }

    private LastDecisionExplanation(boolean noop) {
        this.noop = noop;
    }

    public void record(Snapshot snapshot) {
        if (noop || snapshot == null) {
            return;
        }
        this.last = snapshot;
    }

    /** Most recent snapshot, or {@code null} if none recorded. */
    public Snapshot get() {
        return last;
    }

    /**
     * @param action                          final enforcement action name
 * @param anomalyScore                    anomaly score (may be {@code NaN} for INVALID_SCORE internally;
 *                                         actuator presentation maps non-finite to JSON {@code null})
 * @param policyScore                     score handed to policy (may differ when fusion runs; same NaN note)
     * @param policyScoreDiffersFromAnomaly   {@code true} when fusion (or similar) changed the policy input
     * @param evaluationStatuses               sorted status names
     * @param operatorPhases                  sorted operator phase labels
     * @param isolationForestScoreMode        IF mode name, or {@code null}
     * @param statisticalScore                last composite statistical component, or {@code null}
     * @param isolationForestScore            last IF component (may be fallback), or {@code null}
     * @param isolationForestIncludedInBlend  whether IF entered the weighted composite
     * @param statisticalExplanation          dominant statistical signal, or {@code null}
     * @param startupGraceActive              whether startup grace forced MONITOR presentation
     * @param evaluatedAtEpochMillis          wall clock of the decision
     */
    public record Snapshot(
        String action,
        double anomalyScore,
        double policyScore,
        boolean policyScoreDiffersFromAnomaly,
        List<String> evaluationStatuses,
        List<String> operatorPhases,
        String isolationForestScoreMode,
        Double statisticalScore,
        Double isolationForestScore,
        Boolean isolationForestIncludedInBlend,
        StatisticalScoreSnapshot statisticalExplanation,
        boolean startupGraceActive,
        long evaluatedAtEpochMillis
    ) {
    }
}
