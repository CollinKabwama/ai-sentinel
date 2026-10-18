package dev.aisentinel.core.pilot;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Fail-closed for evidence collection: records only while {@code active} is true.
 * Never denies application traffic.
 */
public final class GatedPilotObservationRecorder implements PilotObservationRecorder {

    private final BooleanSupplier active;
    private final PilotObservationRecorder delegate;

    public GatedPilotObservationRecorder(BooleanSupplier active, PilotObservationRecorder delegate) {
        this.active = Objects.requireNonNull(active, "active");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void record(
        String pipelineIdentityHash,
        dev.aisentinel.core.decision.RiskDecision decision,
        long pipelineLatencyNanos,
        boolean requestProceeded
    ) {
        if (!active.getAsBoolean()) {
            return;
        }
        delegate.record(pipelineIdentityHash, decision, pipelineLatencyNanos, requestProceeded);
    }
}
