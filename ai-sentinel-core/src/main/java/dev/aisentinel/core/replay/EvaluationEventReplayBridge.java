package dev.aisentinel.core.replay;

import dev.aisentinel.core.contract.EvaluationEvent;
import dev.aisentinel.core.contract.EvaluationEventSchemas;

import java.util.Objects;

/**
 * Bridges detector-facing {@link EvaluationEvent} evidence and replay projections.
 * <p>
 * Replay keeps an input/historical-output split for deterministic execution, but both sides
 * must derive from the same EvaluationEvent semantics. Kit run identity remains outside the
 * event (sidecar / {@link ReplayRunManifest}).
 * <p>
 * Package-private: both directions are internal replay/dataset plumbing with no consumer outside
 * {@code dev.aisentinel.core.replay}; this is not a durable public conversion API.
 */
final class EvaluationEventReplayBridge {

    private EvaluationEventReplayBridge() {
    }

    /**
     * Projects a validated EvaluationEvent into replay input + historical reference output.
     */
    static ReplayDataset.ReplaySourceEvent toReplaySourceEvent(EvaluationEvent event, int sequenceNumber) {
        Objects.requireNonNull(event, "event");
        ReplayInputRecord input = new ReplayInputRecord(
            sequenceNumber,
            event.eventId(),
            event.observedAt(),
            event.correlationId(),
            event.identityKey(),
            event.identityType(),
            event.endpointKey(),
            event.featureSchemaVersion(),
            event.features()
        );
        HistoricalReferenceOutput historical = new HistoricalReferenceOutput(
            event.scorerId(),
            event.scorerVersion(),
            event.anomalyScore(),
            event.policyScore(),
            event.action(),
            event.evaluationStatuses(),
            event.riskFactors(),
            event.policyId(),
            event.policyVersion(),
            event.evaluationMode()
        );
        return new ReplayDataset.ReplaySourceEvent(input, historical);
    }

    /**
     * Reconstructs an EvaluationEvent from a replay source projection.
     * <p>
     * <strong>Not a lossless round trip:</strong> {@link ReplayInputRecord} does not carry the
     * original {@code eventSchemaVersion} (only {@code featureSchemaVersion}), so this always
     * stamps {@link EvaluationEventSchemas#CURRENT_VERSION} rather than the version the source
     * event actually declared. Every other field is preserved exactly.
     */
    static EvaluationEvent fromReplaySourceEvent(ReplayDataset.ReplaySourceEvent sourceEvent) {
        Objects.requireNonNull(sourceEvent, "sourceEvent");
        ReplayInputRecord input = sourceEvent.replayInput();
        HistoricalReferenceOutput historical = sourceEvent.historicalOutput();
        return new EvaluationEvent(
            EvaluationEventSchemas.CURRENT_VERSION,
            input.eventId(),
            input.observedAt(),
            input.correlationId(),
            input.identityKey(),
            input.identityType(),
            input.endpointKey(),
            input.featureSchemaVersion(),
            input.features(),
            historical.scorerId(),
            historical.scorerVersion(),
            historical.anomalyScore(),
            historical.policyScore(),
            historical.action(),
            historical.evaluationStatuses(),
            historical.riskFactors(),
            historical.policyId(),
            historical.policyVersion(),
            historical.evaluationMode()
        );
    }
}
