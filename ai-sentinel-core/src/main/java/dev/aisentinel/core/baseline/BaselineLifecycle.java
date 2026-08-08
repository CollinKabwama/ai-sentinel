package dev.aisentinel.core.baseline;

import dev.aisentinel.core.metrics.FailOpenReason;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.scoring.CompositeScorer;
import dev.aisentinel.core.scoring.StatisticalScorer;
import lombok.extern.slf4j.Slf4j;

/**
 * Controlled baseline reset for statistical Welford state.
 * <p>
 * Does not weaken gated update policies: reset removes per-key state so the identity re-enters
 * statistical warmup. Warmup continues to learn; after warmup, {@link BaselineUpdatePolicy} applies again.
 * Fail-open: unexpected errors are logged and ignored.
 * <p>
 * Automatic skip-triggered relearn is not offered. Elevated traffic that would have been skipped must
 * not both open a relearn window and train the replacement baseline.
 * <p>
 * Explicit reset is intentional: invoke only when subsequent traffic is expected to represent
 * legitimate behavior. No unauthenticated HTTP reset endpoint is exposed by this class.
 */
@Slf4j
public final class BaselineLifecycle {

    public static final String REASON_EXPLICIT = "EXPLICIT";

    private final StatisticalScorer statisticalScorer;
    private final BaselineRelearnMode mode;
    private final SentinelMetrics metrics;

    public BaselineLifecycle(StatisticalScorer statisticalScorer,
                             BaselineRelearnMode mode,
                             SentinelMetrics metrics) {
        this.statisticalScorer = statisticalScorer;
        this.mode = mode != null ? mode : BaselineRelearnMode.DISABLED;
        this.metrics = metrics != null ? metrics : SentinelMetrics.NOOP;
    }

    /** Disabled lifecycle: all reset paths are no-ops. */
    public static BaselineLifecycle disabled() {
        return new BaselineLifecycle(null, BaselineRelearnMode.DISABLED, SentinelMetrics.NOOP);
    }

    /**
     * Resolves a lifecycle against the scoring tree used by the decision engine.
     * Prefer an explicitly injected {@link StatisticalScorer}; otherwise unwrap from composite/scorer.
     */
    public static BaselineLifecycle of(AnomalyScorer scorer,
                                       BaselineRelearnMode mode,
                                       SentinelMetrics metrics) {
        return new BaselineLifecycle(unwrapStatistical(scorer), mode, metrics);
    }

    public BaselineRelearnMode mode() {
        return mode;
    }

    /**
     * Explicit operator reset. No-op when mode is {@link BaselineRelearnMode#DISABLED} or no statistical scorer.
     * <p>
     * After a successful reset the key re-enters warmup and subsequent observations train the new baseline.
     * Call only when that subsequent traffic is expected to be legitimate.
     *
     * @return {@code true} when an existing key was removed
     */
    public boolean reset(String identityHash, String endpoint) {
        if (mode == BaselineRelearnMode.DISABLED || statisticalScorer == null) {
            return false;
        }
        try {
            boolean removed = statisticalScorer.reset(identityHash, endpoint);
            if (removed) {
                metrics.recordBaselineRelearn(REASON_EXPLICIT);
            }
            return removed;
        } catch (Exception e) {
            log.debug("Baseline reset failed (fail-open reason={}): {}: {}",
                FailOpenReason.BASELINE_LIFECYCLE_FAILURE, e.getClass().getSimpleName(), e.getMessage());
            metrics.recordFailOpen(FailOpenReason.BASELINE_LIFECYCLE_FAILURE);
            return false;
        }
    }

    /**
     * Bookkeeping hook after an accepted online update. Retained for decision-engine symmetry;
     * no automatic-relearn counters remain.
     */
    public void onUpdateAccepted(RequestFeatures features) {
        // no-op: automatic consecutive-skip relearn was removed
    }

    /**
     * Hook after a gated skip. Always returns {@code false}: automatic relearn from skipped
     * elevated traffic is not supported (would couple trigger observations with warmup training).
     */
    public boolean onUpdateSkipped(RequestFeatures features) {
        return false;
    }

    static StatisticalScorer unwrapStatistical(AnomalyScorer scorer) {
        if (scorer instanceof StatisticalScorer statistical) {
            return statistical;
        }
        if (scorer instanceof CompositeScorer composite) {
            for (AnomalyScorer child : composite.scorersView()) {
                if (child instanceof StatisticalScorer statistical) {
                    return statistical;
                }
            }
        }
        return null;
    }
}
