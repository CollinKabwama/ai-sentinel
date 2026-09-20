package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.dataset.reference.ReferenceDatasetAnnotations;
import dev.aisentinel.core.replay.ReplayConfiguration;
import dev.aisentinel.core.replay.ReplayDataset;
import dev.aisentinel.core.replay.ReplayResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Hardened detection-evaluation entry point for evaluator-provided (BYO) Evaluation Kit datasets.
 * <p>
 * Loads {@code dataset-manifest.json} provenance + checksums, optionally joins event-level ground
 * truth (using {@code datasetId}), builds an in-memory replay dataset, then reuses the existing
 * replay + detection-evaluation pipeline. Does not fabricate generator seed/build provenance.
 */
public final class EvaluatorProvidedDatasetEvaluator {

    private final DetectionEvaluationRunner detectionEvaluationRunner;

    public EvaluatorProvidedDatasetEvaluator() {
        this(new DetectionEvaluationRunner());
    }

    EvaluatorProvidedDatasetEvaluator(DetectionEvaluationRunner detectionEvaluationRunner) {
        this.detectionEvaluationRunner =
            Objects.requireNonNull(detectionEvaluationRunner, "detectionEvaluationRunner");
    }

    /**
     * Evaluates one evaluator-provided dataset directory
     * ({@code dataset-manifest.json} + {@code events.jsonl} + optional {@code annotations.json}).
     */
    public EvaluatorProvidedDatasetEvaluationResult evaluate(
        Path datasetDirectory,
        ReplayConfiguration replayConfiguration,
        DetectionClassificationConfiguration classification,
        Path outputDirectory
    ) throws IOException {
        EvaluatorProvidedDatasetSupport.LoadedDataset loaded =
            EvaluatorProvidedDatasetSupport.load(datasetDirectory);

        ReplayDataset replayDataset = loaded.replayDataset();
        if (!replayDataset.manifest().datasetId().equals(loaded.provenance().datasetId())) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.INTEGRITY_FAILURE,
                "Replay datasetId does not match dataset-manifest datasetId");
        }
        if (replayDataset.eventCount() != loaded.provenance().eventCount()) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.INTEGRITY_FAILURE,
                "Replay event count does not match dataset-manifest eventCount");
        }

        Set<String> eventIds = new LinkedHashSet<>();
        for (ReplayDataset.ReplaySourceEvent event : replayDataset.events()) {
            eventIds.add(event.replayInput().eventId());
        }

        ReferenceDatasetAnnotations adapted;
        GeneratedCorpusPhaseCounts phaseCounts;
        List<GeneratedCorpusEventInspection> eventInspections;
        List<String> limitations = new ArrayList<>();
        limitations.add(
            "Evaluator-provided dataset evaluation is controlled evidence only; not production validation.");
        limitations.add(
            "BYO evaluation is not independent third-party validation of production efficacy.");
        limitations.add(
            "Runtime EvaluationStatus / anomalyScore values are produced only by replay scoring, not by ground truth.");

        if (loaded.labeled()) {
            EvaluatorProvidedDatasetSupport.validateGroundTruthAgainstEvents(
                loaded.groundTruth(), eventIds);
            adapted = EventAnnotationAdapter.toReferenceAnnotations(
                loaded.provenance().datasetId(),
                loaded.groundTruth().scenarioId(),
                loaded.groundTruth().annotations(),
                "evaluator-provided"
            );
            phaseCounts = EvaluatorProvidedDatasetSupport.phaseCounts(loaded.groundTruth());
            limitations.add(
                "Warmup/baseline-building events are excluded from binary detection metrics.");
            limitations.add(
                "expectedClass=unknown|unlabeled events are excluded from binary detection metrics and counted separately.");
            limitations.add(
                "scenarioCategory values are compatibility mappings from annotation categories onto the historical category enum.");
            if (phaseCounts.labeledEvaluationEvents() == 0) {
                limitations.add(
                    "No binary-labeled evaluation events were present; detection confusion metrics are unavailable/empty.");
            }
        } else {
            adapted = new ReferenceDatasetAnnotations(
                ReferenceDatasetAnnotations.SCHEMA_VERSION,
                loaded.provenance().datasetId(),
                "Unlabeled evaluator-provided dataset; no ground-truth sidecar",
                List.of()
            );
            phaseCounts = EvaluatorProvidedDatasetSupport.unlabeledPhaseCounts(
                loaded.provenance().eventCount());
            limitations.add(
                "No annotations sidecar was provided; detection labeled metrics are unavailable.");
        }

        ReplayDataset annotatedDataset = replayDataset.withAnnotations(
            new ReplayDataset.AnnotationMetadata(
                ReferenceDatasetAnnotations.SCHEMA_VERSION,
                adapted.datasetId(),
                adapted.scenarios().size()
            )
        );

        DetectionEvaluationRunner.RunWithReplayResults runWithReplayResults =
            detectionEvaluationRunner.evaluateWithReplayResults(
                annotatedDataset,
                adapted,
                replayConfiguration,
                classification,
                outputDirectory
            );
        DetectionEvaluationRunner.DetectionEvaluationRun detectionRun = runWithReplayResults.run();

        if (loaded.labeled()) {
            eventInspections = GeneratedCorpusEventInspections.build(
                loaded.groundTruth(),
                runWithReplayResults.replayResults(),
                classification
            );
        } else {
            eventInspections = buildUnlabeledInspections(
                runWithReplayResults.replayResults(),
                classification
            );
        }

        EvaluatorProvidedDatasetEvaluationResult result = new EvaluatorProvidedDatasetEvaluationResult(
            loaded.provenance(),
            phaseCounts,
            detectionRun,
            eventInspections,
            limitations
        );

        new EvaluatorProvidedEvaluationReportWriter().write(result, outputDirectory);
        return result;
    }

    private static List<GeneratedCorpusEventInspection> buildUnlabeledInspections(
        List<ReplayResult> replayResults,
        DetectionClassificationConfiguration classification
    ) {
        List<GeneratedCorpusGroundTruth.EventAnnotation> synthetic = new ArrayList<>(replayResults.size());
        for (ReplayResult replay : replayResults) {
            synthetic.add(new GeneratedCorpusGroundTruth.EventAnnotation(
                replay.eventId(),
                "unlabeled",
                "unlabeled",
                "unlabeled"
            ));
        }
        GeneratedCorpusGroundTruth syntheticGt = new GeneratedCorpusGroundTruth(
            "1",
            "ann.unlabeled",
            "unlabeled",
            "unlabeled",
            synthetic,
            "Synthetic unlabeled inspection rows; no authored ground truth"
        );
        return GeneratedCorpusEventInspections.build(syntheticGt, replayResults, classification);
    }
}
