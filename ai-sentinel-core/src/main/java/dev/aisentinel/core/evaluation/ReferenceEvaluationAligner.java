package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.dataset.reference.ReferenceDatasetAnnotations;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetScenarioAnnotation;
import dev.aisentinel.core.replay.ReplayDataset;
import dev.aisentinel.core.replay.ReplayInputRecord;
import dev.aisentinel.core.replay.ReplayResult;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministically joins reference annotations with replay predictions.
 */
public final class ReferenceEvaluationAligner {

    public ReferenceEvaluationAlignment align(ReplayDataset dataset,
                                              ReferenceDatasetAnnotations annotations,
                                              List<ReplayResult> replayResults) {
        ReplayDataset safeDataset = Objects.requireNonNull(dataset, "dataset");
        ReferenceDatasetAnnotations safeAnnotations = Objects.requireNonNull(annotations, "annotations");
        List<ReplayResult> safeReplayResults = replayResults == null ? List.of() : List.copyOf(replayResults);
        if (!safeDataset.manifest().datasetId().equals(safeAnnotations.datasetId())) {
            throw new EvaluationAlignmentException("annotation datasetId does not match replay dataset");
        }

        Map<String, ReplayDataset.ReplaySourceEvent> sourceByEventId = new LinkedHashMap<>();
        List<ReplayDataset.ReplaySourceEvent> sourceEvents = safeDataset.events();
        for (ReplayDataset.ReplaySourceEvent sourceEvent : sourceEvents) {
            String eventId = sourceEvent.replayInput().eventId();
            if (sourceByEventId.put(eventId, sourceEvent) != null) {
                throw new EvaluationAlignmentException("duplicate source event id: " + eventId);
            }
        }

        Map<String, ReplayResult> replayByEventId = new LinkedHashMap<>();
        String replayRunId = "";
        for (ReplayResult replayResult : safeReplayResults) {
            if (replayByEventId.put(replayResult.eventId(), replayResult) != null) {
                throw new EvaluationAlignmentException("duplicate replay event id: " + replayResult.eventId());
            }
            if (!sourceByEventId.containsKey(replayResult.eventId())) {
                throw new EvaluationAlignmentException("replay result references unknown source event: " + replayResult.eventId());
            }
            if (replayRunId.isEmpty()) {
                replayRunId = replayResult.replayRunId();
            } else if (!replayRunId.equals(replayResult.replayRunId())) {
                throw new EvaluationAlignmentException("replay results contain multiple replayRunIds");
            }
        }
        if (replayRunId.isEmpty()) {
            throw new EvaluationAlignmentException("replay results are required");
        }

        Map<String, ReferenceDatasetScenarioAnnotation> evaluableScenarioByEventId = new LinkedHashMap<>();
        Set<String> evaluableEventIds = new LinkedHashSet<>();
        for (ReferenceDatasetScenarioAnnotation scenario : safeAnnotations.scenarios()) {
            if (scenario.expectedClass() == null) {
                throw new EvaluationAlignmentException("scenario is missing expectedClass: " + scenario.id());
            }
            validateTruth(scenario);
            List<String> evaluableEvents = evaluableEventIds(scenario);
            for (String eventId : evaluableEvents) {
                if (!sourceByEventId.containsKey(eventId)) {
                    throw new EvaluationAlignmentException("annotation references nonexistent event: " + eventId);
                }
                ReferenceDatasetScenarioAnnotation previous = evaluableScenarioByEventId.put(eventId, scenario);
                if (previous != null && !previous.id().equals(scenario.id())) {
                    throw new EvaluationAlignmentException("event associated with conflicting annotations: " + eventId);
                }
                evaluableEventIds.add(eventId);
            }
        }

        List<EvaluationObservation> observations = sourceEvents.stream()
            .filter(sourceEvent -> evaluableEventIds.contains(sourceEvent.replayInput().eventId()))
            .map(sourceEvent -> toObservation(sourceEvent, replayByEventId, evaluableScenarioByEventId))
            .toList();

        if (observations.size() != evaluableEventIds.size()) {
            throw new EvaluationAlignmentException("missing replay event for at least one evaluable annotation");
        }

        return new ReferenceEvaluationAlignment(
            safeAnnotations.datasetId(),
            replayRunId,
            safeDataset.eventCount(),
            safeAnnotations.scenarios().size(),
            observations.size(),
            observations
        );
    }

    private static EvaluationObservation toObservation(ReplayDataset.ReplaySourceEvent sourceEvent,
                                                       Map<String, ReplayResult> replayByEventId,
                                                       Map<String, ReferenceDatasetScenarioAnnotation> scenarioByEventId) {
        String eventId = sourceEvent.replayInput().eventId();
        ReplayResult replayResult = replayByEventId.get(eventId);
        if (replayResult == null) {
            throw new EvaluationAlignmentException("missing replay event for evaluable event: " + eventId);
        }
        requireReplayMatchesSource(sourceEvent.replayInput(), replayResult);
        ReferenceDatasetScenarioAnnotation scenario = scenarioByEventId.get(eventId);
        if (scenario == null) {
            throw new EvaluationAlignmentException("missing scenario annotation for evaluable event: " + eventId);
        }
        return new EvaluationObservation(
            replayResult.eventId(),
            scenario.id(),
            scenario.category(),
            replayResult.sequenceNumber(),
            replayResult.observedAt(),
            replayResult.identityKey(),
            new EvaluationTruth(
                scenario.expectedClass(),
                scenario.anomalyExpected(),
                scenario.maliciousnessAsserted()
            ),
            new EvaluationPrediction(
                EvaluationPredictionSource.REPLAY_SCORE,
                replayResult.anomalyScore(),
                replayResult.policyScore(),
                replayResult.action(),
                replayResult.evaluationStatuses()
            )
        );
    }

    private static void validateTruth(ReferenceDatasetScenarioAnnotation scenario) {
        try {
            new EvaluationTruth(
                scenario.expectedClass(),
                scenario.anomalyExpected(),
                scenario.maliciousnessAsserted()
            );
        } catch (IllegalArgumentException e) {
            throw new EvaluationAlignmentException("scenario truth metadata is contradictory: " + scenario.id());
        }
    }

    private static void requireReplayMatchesSource(ReplayInputRecord input, ReplayResult result) {
        if (input.sequenceNumber() != result.sequenceNumber()
            || !input.eventId().equals(result.eventId())
            || !input.observedAt().equals(result.observedAt())
            || !input.correlationId().equals(result.correlationId())
            || !input.identityKey().equals(result.identityKey())
            || !input.identityType().equals(result.identityType())
            || !input.endpointKey().equals(result.endpointKey())
            || !input.featureSchemaVersion().equals(result.featureSchemaVersion())) {
            throw new EvaluationAlignmentException("replay result does not match source event: " + input.eventId());
        }
    }

    static List<String> evaluableEventIds(ReferenceDatasetScenarioAnnotation scenario) {
        Objects.requireNonNull(scenario, "scenario");
        if (!scenario.evaluationEventIds().isEmpty()) {
            Set<String> eventIds = new LinkedHashSet<>(scenario.eventIds());
            for (String evaluationEventId : scenario.evaluationEventIds()) {
                if (!eventIds.contains(evaluationEventId)) {
                    throw new EvaluationAlignmentException(
                        "evaluationEventIds contains event outside scenario eventIds: " + evaluationEventId);
                }
            }
            return scenario.evaluationEventIds();
        }
        if (!scenario.baselineEventIds().isEmpty()) {
            throw new EvaluationAlignmentException(
                "scenario contains baselineEventIds but no evaluationEventIds: " + scenario.id());
        }
        return scenario.eventIds();
    }
}
