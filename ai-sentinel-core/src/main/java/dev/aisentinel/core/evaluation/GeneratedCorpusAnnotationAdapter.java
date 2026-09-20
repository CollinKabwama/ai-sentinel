package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.dataset.reference.ReferenceDatasetAnnotations;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetExpectedClass;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetScenarioAnnotation;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetScenarioCategory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adapts generated-corpus event-level ground truth into the historical scenario-annotation shape
 * consumed by {@link ReferenceEvaluationAligner} / detection metrics.
 * <p>
 * Warmup and unknown/unlabeled events are excluded from binary detection metrics by placing them
 * only in baseline / omitting them from evaluationEventIds. Mapping does not invent detector scores.
 */
final class GeneratedCorpusAnnotationAdapter {

    private GeneratedCorpusAnnotationAdapter() {
    }

    static ReferenceDatasetAnnotations toReferenceAnnotations(
        GeneratedCorpusProvenance provenance,
        GeneratedCorpusGroundTruth groundTruth
    ) {
        List<String> warmupEventIds = new ArrayList<>();
        Map<String, List<GeneratedCorpusGroundTruth.EventAnnotation>> labeledByGroup = new LinkedHashMap<>();

        for (GeneratedCorpusGroundTruth.EventAnnotation annotation : groundTruth.annotations()) {
            if (annotation.warmup()) {
                warmupEventIds.add(annotation.eventId());
                continue;
            }
            if (annotation.unknownOrUnlabeled()) {
                // Visible in phase counts only; excluded from binary confusion metrics.
                continue;
            }
            if (!annotation.binaryLabeled()) {
                throw new GeneratedCorpusEvaluationException(
                    GeneratedCorpusEvaluationFailureKind.GROUND_TRUTH_FAILURE,
                    "Unsupported expectedClass for evaluation metrics: " + annotation.expectedClass());
            }
            String groupKey = annotation.category() + "|" + annotation.expectedClass();
            labeledByGroup.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(annotation);
        }

        List<ReferenceDatasetScenarioAnnotation> scenarios = new ArrayList<>();
        for (Map.Entry<String, List<GeneratedCorpusGroundTruth.EventAnnotation>> entry : labeledByGroup.entrySet()) {
            List<GeneratedCorpusGroundTruth.EventAnnotation> group = entry.getValue();
            GeneratedCorpusGroundTruth.EventAnnotation sample = group.get(0);
            List<String> evaluationEventIds = group.stream()
                .map(GeneratedCorpusGroundTruth.EventAnnotation::eventId)
                .toList();
            Set<String> eventIds = new LinkedHashSet<>(warmupEventIds);
            eventIds.addAll(evaluationEventIds);

            ReferenceDatasetExpectedClass expectedClass = mapExpectedClass(sample.expectedClass());
            boolean anomalous = expectedClass != ReferenceDatasetExpectedClass.NORMAL;
            scenarios.add(new ReferenceDatasetScenarioAnnotation(
                provenance.scenarioId() + "#" + sample.category() + "#" + sample.expectedClass(),
                mapCategory(sample.category()),
                expectedClass,
                anomalous,
                false,
                List.copyOf(eventIds),
                List.copyOf(warmupEventIds),
                evaluationEventIds,
                List.of("generated-corpus"),
                List.of("feature-level"),
                "Adapted from generated corpus event-level ground truth; category=" + sample.category()
            ));
        }

        if (scenarios.isEmpty() && !warmupEventIds.isEmpty() && labeledByGroup.isEmpty()) {
            // Warmup-only / unknown-only corpora still need a valid annotation document for provenance,
            // but produce zero evaluable observations. Create an empty-evaluation scenario is invalid
            // (evaluationEventIds empty with baseline present fails aligner). Leave scenarios empty —
            // aligner yields zero observations when scenarios list is empty.
        }

        return new ReferenceDatasetAnnotations(
            ReferenceDatasetAnnotations.SCHEMA_VERSION,
            provenance.corpusId(),
            "Adapted generated corpus annotations for scenarioId=" + provenance.scenarioId(),
            scenarios
        );
    }

    static ReferenceDatasetExpectedClass mapExpectedClass(String expectedClass) {
        return switch (expectedClass) {
            case "benign" -> ReferenceDatasetExpectedClass.NORMAL;
            case "anomalous" -> ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS;
            default -> throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.GROUND_TRUTH_FAILURE,
                "Cannot map expectedClass into binary detection truth: " + expectedClass);
        };
    }

    static ReferenceDatasetScenarioCategory mapCategory(String kitCategory) {
        return switch (kitCategory) {
            case "evaluation-normal", "recovery" -> ReferenceDatasetScenarioCategory.ESTABLISHED_NORMAL_BASELINE;
            case "cold-start" -> ReferenceDatasetScenarioCategory.WARMUP_NEW_IDENTITY;
            case "legitimate-burst" -> ReferenceDatasetScenarioCategory.LEGITIMATE_BULK_OPERATION;
            case "burst" -> ReferenceDatasetScenarioCategory.RAPID_REQUEST_BURST;
            case "endpoint-distribution-change" -> ReferenceDatasetScenarioCategory.ENDPOINT_BEHAVIOR_CHANGE;
            case "low-variance-deviation" -> ReferenceDatasetScenarioCategory.LOW_VARIANCE_BASELINE_DEVIATION;
            case "gradual-drift" -> ReferenceDatasetScenarioCategory.GRADUAL_BEHAVIOR_CHANGE;
            case "multi-feature-anomaly" -> ReferenceDatasetScenarioCategory.PARAMETER_COUNT_DEVIATION;
            case "identity-session-transition" -> ReferenceDatasetScenarioCategory.INTERLEAVED_NORMAL_IDENTITIES;
            default -> throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.GROUND_TRUTH_FAILURE,
                "Unsupported generated corpus annotation category for metrics mapping: " + kitCategory);
        };
    }
}
