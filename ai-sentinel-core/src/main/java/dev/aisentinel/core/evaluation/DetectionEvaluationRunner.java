package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.dataset.reference.ReferenceDatasetAnnotations;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetAnnotationsLoader;
import dev.aisentinel.core.replay.ReplayConfiguration;
import dev.aisentinel.core.replay.ReplayDataset;
import dev.aisentinel.core.replay.ReplayDatasetLoader;
import dev.aisentinel.core.replay.ReplayEngine;
import dev.aisentinel.core.replay.ReplayOutputValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Framework-independent orchestration of one complete offline detection evaluation run.
 * <p>
 * Composes accepted dataset loading, deterministic replay, alignment, metrics, temporal
 * evaluation, and evidence publication. It does not reimplement those stage semantics.
 */
public final class DetectionEvaluationRunner {

    private final ReplayDatasetLoader datasetLoader;
    private final ReferenceDatasetAnnotationsLoader annotationsLoader;
    private final ReplayEngine replayEngine;
    private final ReplayOutputValidator replayOutputValidator;
    private final ReferenceEvaluationAligner aligner;
    private final DetectionMetricsCalculator metricsCalculator;
    private final TemporalDetectionEvaluator temporalEvaluator;
    private final DetectionEvaluationEvidenceGenerator evidenceGenerator;
    private final DetectionEvaluationEvidenceWriter evidenceWriter;

    public DetectionEvaluationRunner() {
        this(
            new ReplayDatasetLoader(),
            new ReferenceDatasetAnnotationsLoader(),
            new ReplayEngine(),
            new ReplayOutputValidator(),
            new ReferenceEvaluationAligner(),
            new DetectionMetricsCalculator(),
            new TemporalDetectionEvaluator(),
            new DetectionEvaluationEvidenceGenerator(),
            new DetectionEvaluationEvidenceWriter()
        );
    }

    DetectionEvaluationRunner(ReplayDatasetLoader datasetLoader,
                              ReferenceDatasetAnnotationsLoader annotationsLoader,
                              ReplayEngine replayEngine,
                              ReplayOutputValidator replayOutputValidator,
                              ReferenceEvaluationAligner aligner,
                              DetectionMetricsCalculator metricsCalculator,
                              TemporalDetectionEvaluator temporalEvaluator,
                              DetectionEvaluationEvidenceGenerator evidenceGenerator,
                              DetectionEvaluationEvidenceWriter evidenceWriter) {
        this.datasetLoader = Objects.requireNonNull(datasetLoader, "datasetLoader");
        this.annotationsLoader = Objects.requireNonNull(annotationsLoader, "annotationsLoader");
        this.replayEngine = Objects.requireNonNull(replayEngine, "replayEngine");
        this.replayOutputValidator = Objects.requireNonNull(replayOutputValidator, "replayOutputValidator");
        this.aligner = Objects.requireNonNull(aligner, "aligner");
        this.metricsCalculator = Objects.requireNonNull(metricsCalculator, "metricsCalculator");
        this.temporalEvaluator = Objects.requireNonNull(temporalEvaluator, "temporalEvaluator");
        this.evidenceGenerator = Objects.requireNonNull(evidenceGenerator, "evidenceGenerator");
        this.evidenceWriter = Objects.requireNonNull(evidenceWriter, "evidenceWriter");
    }

    /**
     * Executes one complete deterministic evaluation and publishes evidence artifacts.
     */
    public DetectionEvaluationRun evaluate(Path datasetDirectory,
                                           Path annotationsFile,
                                           ReplayConfiguration replayConfiguration,
                                           DetectionClassificationConfiguration classification,
                                           Path outputDirectory) throws IOException {
        Path safeDatasetDirectory = requireExistingDirectory(datasetDirectory, "datasetDirectory");
        Path safeAnnotationsFile = requireExistingFile(annotationsFile, "annotationsFile");
        ReplayConfiguration safeReplayConfiguration =
            Objects.requireNonNull(replayConfiguration, "replayConfiguration");
        DetectionClassificationConfiguration safeClassification =
            Objects.requireNonNull(classification, "classification");
        Path safeOutputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory")
            .toAbsolutePath()
            .normalize();

        ReplayDataset dataset = datasetLoader.load(safeDatasetDirectory, safeAnnotationsFile);
        ReferenceDatasetAnnotations annotations = annotationsLoader.load(safeAnnotationsFile);
        requireDatasetAnnotationConsistency(dataset, annotations);

        Path replayDirectory = Files.createTempDirectory("detection-evaluation-replay-");
        try {
            ReplayEngine.ReplayRun replayRun =
                replayEngine.run(dataset, safeReplayConfiguration, replayDirectory);
            replayOutputValidator.validate(replayDirectory, dataset, safeReplayConfiguration);

            ReferenceEvaluationAlignment alignment =
                aligner.align(dataset, annotations, replayRun.results());
            DetectionEvaluationMetrics metrics =
                metricsCalculator.compute(alignment, safeClassification);
            TemporalDetectionEvaluation temporal =
                temporalEvaluator.evaluate(alignment, safeClassification);
            DetectionEvaluationEvidence evidence = evidenceGenerator.generate(
                dataset,
                alignment,
                replayRun.manifest(),
                metrics,
                temporal,
                safeClassification
            );
            DetectionEvaluationEvidenceWriter.WrittenEvidence written =
                evidenceWriter.write(safeOutputDirectory, evidence);
            return new DetectionEvaluationRun(
                alignment,
                metrics,
                temporal,
                evidence,
                written,
                replayRun.manifest().resultsSha256(),
                safeReplayConfiguration.configurationFingerprint()
            );
        } finally {
            deleteRecursively(replayDirectory);
        }
    }

    private static void requireDatasetAnnotationConsistency(ReplayDataset dataset,
                                                            ReferenceDatasetAnnotations annotations) {
        if (!dataset.manifest().datasetId().equals(annotations.datasetId())) {
            throw new IllegalArgumentException("annotation datasetId does not match replay dataset");
        }
        if (!dataset.annotations().schemaVersion().equals(annotations.schemaVersion())) {
            throw new IllegalArgumentException("annotation schemaVersion does not match replay dataset metadata");
        }
        if (dataset.annotations().scenarioCount() != annotations.scenarios().size()) {
            throw new IllegalArgumentException("annotation scenarioCount does not match replay dataset metadata");
        }
    }

    private static Path requireExistingDirectory(Path path, String field) {
        Path safe = Objects.requireNonNull(path, field).toAbsolutePath().normalize();
        if (!Files.isDirectory(safe)) {
            throw new IllegalArgumentException(field + " must be an existing directory: " + safe);
        }
        return safe;
    }

    private static Path requireExistingFile(Path path, String field) {
        Path safe = Objects.requireNonNull(path, field).toAbsolutePath().normalize();
        if (!Files.isRegularFile(safe)) {
            throw new IllegalArgumentException(field + " must be an existing file: " + safe);
        }
        return safe;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                .forEach(candidate -> {
                    try {
                        Files.deleteIfExists(candidate);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }

    /**
     * Result of one complete offline detection evaluation run.
     */
    public record DetectionEvaluationRun(
        ReferenceEvaluationAlignment alignment,
        DetectionEvaluationMetrics metrics,
        TemporalDetectionEvaluation temporal,
        DetectionEvaluationEvidence evidence,
        DetectionEvaluationEvidenceWriter.WrittenEvidence writtenEvidence,
        String replayResultsSha256,
        String replayConfigurationFingerprint
    ) {
        public DetectionEvaluationRun {
            alignment = Objects.requireNonNull(alignment, "alignment");
            metrics = Objects.requireNonNull(metrics, "metrics");
            temporal = Objects.requireNonNull(temporal, "temporal");
            evidence = Objects.requireNonNull(evidence, "evidence");
            writtenEvidence = Objects.requireNonNull(writtenEvidence, "writtenEvidence");
            if (replayResultsSha256 == null || !replayResultsSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("replayResultsSha256 must be 64 lowercase hex characters");
            }
            if (replayConfigurationFingerprint == null
                || !replayConfigurationFingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "replayConfigurationFingerprint must be 64 lowercase hex characters");
            }
            if (!alignment.datasetId().equals(metrics.datasetId())
                || !alignment.datasetId().equals(temporal.datasetId())
                || !alignment.datasetId().equals(evidence.reference().datasetId())) {
                throw new IllegalArgumentException("datasetId must reconcile across run results");
            }
            if (!alignment.replayRunId().equals(metrics.replayRunId())
                || !alignment.replayRunId().equals(temporal.replayRunId())
                || !alignment.replayRunId().equals(evidence.replay().replayRunId())) {
                throw new IllegalArgumentException("replayRunId must reconcile across run results");
            }
            if (Double.compare(metrics.classification().anomalyThreshold(), temporal.classification().anomalyThreshold()) != 0
                || Double.compare(metrics.classification().anomalyThreshold(), evidence.classification().anomalyThreshold()) != 0) {
                throw new IllegalArgumentException("classification threshold must reconcile across run results");
            }
            if (!metrics.equals(evidence.metrics())) {
                throw new IllegalArgumentException("metrics must match evidence metrics");
            }
            if (!temporal.equals(evidence.temporal())) {
                throw new IllegalArgumentException("temporal evaluation must match evidence temporal evaluation");
            }
            if (!replayResultsSha256.equals(evidence.replay().resultsSha256())) {
                throw new IllegalArgumentException("replayResultsSha256 must match evidence replay provenance");
            }
            if (!replayConfigurationFingerprint.equals(evidence.replay().configurationFingerprint())) {
                throw new IllegalArgumentException("replayConfigurationFingerprint must match evidence replay provenance");
            }
        }
    }
}
