package dev.aisentinel.core.baseline;

/**
 * Decides whether a scored observation should update online baseline / training state.
 * <p>
 * Framework-independent: no Spring, servlet, HTTP, or enforcement side effects.
 * Implementations must be deterministic and must not mutate scorers.
 */
@FunctionalInterface
public interface BaselineUpdatePolicy {

    /**
     * @return {@code true} if {@link dev.aisentinel.core.scoring.AnomalyScorer#update} should run
     */
    boolean shouldUpdate(BaselineUpdateContext context);
}
