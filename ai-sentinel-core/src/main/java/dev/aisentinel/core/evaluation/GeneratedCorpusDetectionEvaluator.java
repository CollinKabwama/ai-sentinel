package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.dataset.reference.ReferenceDatasetAnnotations;
import dev.aisentinel.core.replay.ReplayConfiguration;
import dev.aisentinel.core.replay.ReplayDataset;
import dev.aisentinel.core.replay.ReplayDatasetLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Hardened detection-evaluation entry point for generated Evaluation Kit corpora.
 * <p>
 * Loads Kit {@code corpus-manifest.json} provenance + checksums, joins event-level ground truth,
 * excludes warmup and unknown/unlabeled events from binary detection metrics, then reuses the
 * existing replay + detection-evaluation pipeline. Runtime evaluation statuses come only from
 * actual replay/scoring — never from authored ground truth.
 */
public final class GeneratedCorpusDetectionEvaluator {

    private final ReplayDatasetLoader datasetLoader;
    private final DetectionEvaluationRunner detectionEvaluationRunner;

    public GeneratedCorpusDetectionEvaluator() {
        this(new ReplayDatasetLoader(), new DetectionEvaluationRunner());
    }

    GeneratedCorpusDetectionEvaluator(
        ReplayDatasetLoader datasetLoader,
        DetectionEvaluationRunner detectionEvaluationRunner
    ) {
        this.datasetLoader = Objects.requireNonNull(datasetLoader, "datasetLoader");
        this.detectionEvaluationRunner =
            Objects.requireNonNull(detectionEvaluationRunner, "detectionEvaluationRunner");
    }

    /**
     * Evaluates one generated corpus directory (events + corpus-manifest + annotations + replay manifest).
     */
    public GeneratedCorpusEvaluationResult evaluate(
        Path corpusDirectory,
        ReplayConfiguration replayConfiguration,
        DetectionClassificationConfiguration classification,
        Path outputDirectory
    ) throws IOException {
        GeneratedCorpusSupport.LoadedCorpus loaded = GeneratedCorpusSupport.load(corpusDirectory);

        ReplayDataset replayDataset = datasetLoader.load(loaded.directory());
        if (!replayDataset.manifest().datasetId().equals(loaded.provenance().corpusId())) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.INTEGRITY_FAILURE,
                "Replay manifest datasetId does not match corpusId");
        }
        if (replayDataset.eventCount() != loaded.provenance().eventCount()) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.INTEGRITY_FAILURE,
                "Replay event count does not match corpus-manifest eventCount");
        }

        Set<String> eventIds = new LinkedHashSet<>();
        for (ReplayDataset.ReplaySourceEvent event : replayDataset.events()) {
            eventIds.add(event.replayInput().eventId());
        }
        GeneratedCorpusSupport.validateGroundTruthAgainstEvents(loaded.groundTruth(), eventIds);

        for (ReplayDataset.ReplaySourceEvent event : replayDataset.events()) {
            if (!event.historicalOutput().evaluationStatuses().isEmpty()) {
                throw new GeneratedCorpusEvaluationException(
                    GeneratedCorpusEvaluationFailureKind.INTEGRITY_FAILURE,
                    "Generated corpus events must not pre-assert runtime evaluationStatuses: "
                        + event.replayInput().eventId());
            }
        }

        ReferenceDatasetAnnotations adapted =
            GeneratedCorpusAnnotationAdapter.toReferenceAnnotations(
                loaded.provenance(), loaded.groundTruth());
        ReplayDataset annotatedDataset = replayDataset.withAnnotations(
            new ReplayDataset.AnnotationMetadata(
                ReferenceDatasetAnnotations.SCHEMA_VERSION,
                adapted.datasetId(),
                adapted.scenarios().size()
            )
        );

        GeneratedCorpusPhaseCounts phaseCounts = GeneratedCorpusSupport.phaseCounts(loaded.groundTruth());
        List<String> limitations = new ArrayList<>();
        limitations.add(
            "Synthetic generated-corpus evaluation is controlled evidence only; not production validation.");
        limitations.add(
            "Warmup/baseline-building events are excluded from binary detection metrics.");
        limitations.add(
            "expectedClass=unknown|unlabeled events are excluded from binary detection metrics and counted separately.");
        limitations.add(
            "scenarioCategory values are compatibility mappings from generated annotation categories onto the historical category enum.");
        limitations.add(
            "Runtime EvaluationStatus / anomalyScore values are produced only by replay scoring, not by ground truth.");
        if (phaseCounts.labeledEvaluationEvents() == 0) {
            limitations.add(
                "No binary-labeled evaluation events were present; detection confusion metrics are unavailable/empty.");
        }

        DetectionEvaluationRunner.DetectionEvaluationRun detectionRun =
            detectionEvaluationRunner.evaluate(
                annotatedDataset,
                adapted,
                replayConfiguration,
                classification,
                outputDirectory
            );

        return new GeneratedCorpusEvaluationResult(
            loaded.provenance(),
            phaseCounts,
            detectionRun,
            limitations
        );
    }
}
