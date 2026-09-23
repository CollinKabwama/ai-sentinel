package dev.aisentinel.core.pilot;

/**
 * Records one MONITOR-mode pilot observation. Implementations must be fail-open for the caller:
 * recording failures must never deny application traffic.
 */
@FunctionalInterface
public interface PilotObservationRecorder {

    /**
     * @param pipelineIdentityHash existing pipeline identity key (already hashed; not raw PII)
     * @param decision             risk decision from the decision engine (may be null)
     * @param pipelineLatencyNanos measured pipeline wall time for this request
     * @param requestProceeded     whether enforcement returned proceed (MONITOR should always be true)
     */
    void record(
        String pipelineIdentityHash,
        dev.aisentinel.core.decision.RiskDecision decision,
        long pipelineLatencyNanos,
        boolean requestProceeded
    );
}
