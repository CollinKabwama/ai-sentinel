package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.dataset.reference.ReferenceDatasetAnnotations;

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
        return EventAnnotationAdapter.toReferenceAnnotations(
            provenance.corpusId(),
            provenance.scenarioId(),
            groundTruth.annotations(),
            "generated-corpus"
        );
    }

    static dev.aisentinel.core.dataset.reference.ReferenceDatasetExpectedClass mapExpectedClass(
        String expectedClass
    ) {
        return EventAnnotationAdapter.mapExpectedClass(expectedClass);
    }

    static dev.aisentinel.core.dataset.reference.ReferenceDatasetScenarioCategory mapCategory(
        String kitCategory
    ) {
        return EventAnnotationAdapter.mapCategory(kitCategory);
    }
}
