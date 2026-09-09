package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.dataset.reference.ReferenceDatasetAnnotations;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetAnnotationsLoader;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetGenerator;
import dev.aisentinel.core.replay.ReplayConfiguration;
import dev.aisentinel.core.replay.ReplayDataset;
import dev.aisentinel.core.replay.ReplayDatasetLoader;
import dev.aisentinel.core.replay.ReplayEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI entrypoint for generating deterministic evaluation evidence from the tracked reference corpus.
 */
public final class ReferenceDetectionEvaluationEvidenceMain {

    private ReferenceDetectionEvaluationEvidenceMain() {
    }

    public static void main(String[] args) throws Exception {
        Arguments parsed = Arguments.parse(args);
        Path output = parsed.outputDirectory().toAbsolutePath().normalize();
        ReplayConfiguration replayConfiguration = ReplayConfiguration.referenceDefaults();
        DetectionClassificationConfiguration classification =
            new DetectionClassificationConfiguration(parsed.anomalyThreshold());
        Path datasetDirectory = locateTrackedPath(ReferenceDatasetGenerator.TRACKED_DATASET_DIRECTORY);
        Path annotationsFile = locateTrackedPath(ReferenceDatasetGenerator.TRACKED_ANNOTATIONS_FILE);

        ReplayDataset dataset = new ReplayDatasetLoader().load(
            datasetDirectory,
            annotationsFile
        );
        ReferenceDatasetAnnotations annotations = new ReferenceDatasetAnnotationsLoader()
            .load(annotationsFile);

        Path replayDirectory = Files.createTempDirectory("reference-detection-evidence-replay-");
        try {
            ReplayEngine.ReplayRun replayRun = new ReplayEngine().run(dataset, replayConfiguration, replayDirectory);
            ReferenceEvaluationAlignment alignment =
                new ReferenceEvaluationAligner().align(dataset, annotations, replayRun.results());
            DetectionEvaluationMetrics metrics =
                new DetectionMetricsCalculator().compute(alignment, classification);
            TemporalDetectionEvaluation temporal =
                new TemporalDetectionEvaluator().evaluate(alignment, classification);
            DetectionEvaluationEvidence evidence = new DetectionEvaluationEvidenceGenerator()
                .generate(dataset, alignment, replayRun.manifest(), metrics, temporal);
            DetectionEvaluationEvidenceWriter.WrittenEvidence written =
                new DetectionEvaluationEvidenceWriter().write(output, evidence);
            System.out.printf(
                "detectionEvaluationEvidence output=%s jsonSha256=%s markdownSha256=%s aligned=%d scenarios=%d segments=%d%n",
                written.outputDirectory(),
                written.jsonSha256(),
                written.markdownSha256(),
                evidence.counts().alignedObservationCount(),
                evidence.counts().scenarioCount(),
                evidence.counts().anomalySegmentCount()
            );
        } finally {
            deleteRecursively(replayDirectory);
        }
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

    private static Path locateTrackedPath(Path relativePath) {
        Path candidate = relativePath.toAbsolutePath().normalize();
        if (Files.exists(candidate)) {
            return candidate;
        }
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            candidate = current.resolve(relativePath).normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        return relativePath.toAbsolutePath().normalize();
    }

    record Arguments(Path outputDirectory, double anomalyThreshold) {
        static Arguments parse(String[] args) {
            Path outputDirectory = Path.of("build/reference-detection-evaluation-evidence");
            Double threshold = null;
            boolean outputSeen = false;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg.startsWith("--output=")) {
                    if (outputSeen) {
                        throw new IllegalArgumentException("duplicate --output");
                    }
                    outputSeen = true;
                    outputDirectory = parseOutput(arg.substring("--output=".length()));
                } else if ("--output".equals(arg)) {
                    if (outputSeen) {
                        throw new IllegalArgumentException("duplicate --output");
                    }
                    outputSeen = true;
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("missing value for --output");
                    }
                    outputDirectory = parseOutput(args[++i]);
                } else if (arg.startsWith("--threshold=")) {
                    if (threshold != null) {
                        throw new IllegalArgumentException("duplicate --threshold");
                    }
                    threshold = parseThreshold(arg.substring("--threshold=".length()));
                } else if ("--threshold".equals(arg)) {
                    if (threshold != null) {
                        throw new IllegalArgumentException("duplicate --threshold");
                    }
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("missing value for --threshold");
                    }
                    threshold = parseThreshold(args[++i]);
                } else {
                    throw new IllegalArgumentException("unsupported argument: " + arg);
                }
            }
            if (threshold == null) {
                throw new IllegalArgumentException("missing required --threshold");
            }
            return new Arguments(outputDirectory, threshold);
        }

        private static Path parseOutput(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("missing value for --output");
            }
            return Path.of(value);
        }

        private static double parseThreshold(String value) {
            try {
                double threshold = Double.parseDouble(value);
                if (!Double.isFinite(threshold) || threshold < 0.0 || threshold > 1.0) {
                    throw new IllegalArgumentException("invalid --threshold: " + value);
                }
                return threshold;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid --threshold: " + value, e);
            }
        }
    }
}
