package dev.aisentinel.core.decision;

import java.util.Objects;
import java.util.Set;

/**
 * Mutable sink for one evaluation-status collection pass.
 * Contributors may only add statuses; they cannot alter scoring or enforcement.
 */
public final class EvaluationStatusContributionContext {

    private final Set<EvaluationStatus> statuses;
    private final String isolationForestScoreModeOrNull;
    private final boolean trustedLifecycleContributor;

    EvaluationStatusContributionContext(Set<EvaluationStatus> statuses,
                                        String isolationForestScoreModeOrNull,
                                        boolean trustedLifecycleContributor) {
        this.statuses = Objects.requireNonNull(statuses, "statuses");
        this.isolationForestScoreModeOrNull = isolationForestScoreModeOrNull;
        this.trustedLifecycleContributor = trustedLifecycleContributor;
    }

    /**
     * Adds an operational status marker. Engine-owned statuses such as {@code COMPLETE},
     * {@code INVALID_SCORE}, baseline-update markers, remote-transport failure, and untrusted
     * warmup lifecycle markers are rejected so contributors cannot alter decision authority.
     */
    public void add(EvaluationStatus status) {
        EvaluationStatus s = Objects.requireNonNull(status, "status");
        if (isCollectorOwned(s) || (s == EvaluationStatus.STATISTICAL_WARMUP && !trustedLifecycleContributor)) {
            throw new IllegalArgumentException("status is owned by the evaluation engine: " + s);
        }
        statuses.add(s);
    }

    private static boolean isCollectorOwned(EvaluationStatus status) {
        return status == EvaluationStatus.COMPLETE
            || status == EvaluationStatus.INVALID_SCORE
            || status == EvaluationStatus.BASELINE_UPDATE_SKIPPED
            || status == EvaluationStatus.BASELINE_RELEARNED
            || status == EvaluationStatus.REMOTE_EVALUATION_FAILURE;
    }

    /**
     * Request-owned Isolation Forest score mode name from the same score invocation, when available.
     * Contributors may fall back to scorer-local diagnostics when this is {@code null}.
     */
    public String isolationForestScoreModeOrNull() {
        return isolationForestScoreModeOrNull;
    }
}
