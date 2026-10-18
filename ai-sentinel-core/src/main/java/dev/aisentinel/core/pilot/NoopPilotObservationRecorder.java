package dev.aisentinel.core.pilot;

/**
 * No-op recorder used when pilot evidence collection is disabled.
 */
public enum NoopPilotObservationRecorder implements PilotObservationRecorder {
    INSTANCE;

    @Override
    public void record(
        String pipelineIdentityHash,
        dev.aisentinel.core.decision.RiskDecision decision,
        long pipelineLatencyNanos,
        boolean requestProceeded
    ) {
        // intentionally empty
    }
}
