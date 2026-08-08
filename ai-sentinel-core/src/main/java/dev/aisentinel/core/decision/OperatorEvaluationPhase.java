package dev.aisentinel.core.decision;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Operator-facing evaluation phase labels derived from {@link EvaluationStatus}.
 * <p>
 * Public API keeps the precise {@link EvaluationStatus} enum names for compatibility.
 * This helper maps them to the condensed operator model used in docs and dashboards:
 * {@code WARMUP}, {@code LIVE}, {@code MODEL_FALLBACK}, {@code DEGRADED}.
 * {@code FAIL_OPEN} is <em>not</em> an {@link EvaluationStatus} — it is reported via
 * {@link dev.aisentinel.core.metrics.FailOpenReason} metrics/telemetry when a request is
 * allowed without a complete decision (or after optional-path exceptions).
 */
public final class OperatorEvaluationPhase {

    public static final String WARMUP = "WARMUP";
    public static final String LIVE = "LIVE";
    public static final String MODEL_FALLBACK = "MODEL_FALLBACK";
    public static final String DEGRADED = "DEGRADED";
    /** Conceptual only — see {@link dev.aisentinel.core.metrics.FailOpenReason}. */
    public static final String FAIL_OPEN = "FAIL_OPEN";

    private OperatorEvaluationPhase() {
    }

    /**
     * Derives operator phase labels from decision statuses. Empty input → empty set.
     * Never invents {@link #FAIL_OPEN} (that requires a fail-open reason, not statuses alone).
     */
    public static Set<String> fromStatuses(Collection<EvaluationStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (statuses.contains(EvaluationStatus.STATISTICAL_WARMUP)) {
            out.add(WARMUP);
        }
        if (statuses.contains(EvaluationStatus.STATISTICAL_LIVE)
            || statuses.contains(EvaluationStatus.COMPLETE)) {
            out.add(LIVE);
        }
        if (statuses.contains(EvaluationStatus.MODEL_FALLBACK_USED)
            || statuses.contains(EvaluationStatus.MODEL_UNAVAILABLE)) {
            out.add(MODEL_FALLBACK);
        }
        if (statuses.contains(EvaluationStatus.DEGRADED)) {
            out.add(DEGRADED);
        }
        return Set.copyOf(out);
    }
}
