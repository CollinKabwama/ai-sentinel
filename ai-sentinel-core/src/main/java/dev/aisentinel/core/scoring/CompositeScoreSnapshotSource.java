package dev.aisentinel.core.scoring;

/**
 * Capability for components that expose a diagnostic composite score snapshot
 * (actuator / training fallback). Pipeline and actuator depend on this capability rather than
 * the concrete {@link CompositeScorer} type.
 */
public interface CompositeScoreSnapshotSource {

    /**
     * Snapshot from the last score invocation on this instance, or {@code null} if never scored.
     * Diagnostic only — not request-scoped.
     */
    CompositeScorer.CompositeScoreSnapshot getLastCompositeScoreSnapshot();
}
