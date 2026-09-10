package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.dataset.reference.ReferenceDatasetGenerator;
import dev.aisentinel.core.replay.ReplayConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Thin CLI adapter for generating deterministic evaluation evidence from the tracked reference corpus.
 */
public final class ReferenceDetectionEvaluationEvidenceMain {

    private ReferenceDetectionEvaluationEvidenceMain() {
    }

    public static void main(String[] args) throws Exception {
        Arguments parsed = Arguments.parse(args);
        Path output = parsed.outputDirectory().toAbsolutePath().normalize();
        Path datasetDirectory = requireTrackedPath(ReferenceDatasetGenerator.TRACKED_DATASET_DIRECTORY);
        Path annotationsFile = requireTrackedPath(ReferenceDatasetGenerator.TRACKED_ANNOTATIONS_FILE);
        DetectionClassificationConfiguration classification =
            new DetectionClassificationConfiguration(parsed.anomalyThreshold());

        DetectionEvaluationRunner.DetectionEvaluationRun run = new DetectionEvaluationRunner().evaluate(
            datasetDirectory,
            annotationsFile,
            ReplayConfiguration.referenceDefaults(),
            classification,
            output
        );
        System.out.println(
            "detectionEvaluationEvidence output=" + run.writtenEvidence().outputDirectory()
                + " jsonSha256=" + run.writtenEvidence().jsonSha256()
                + " markdownSha256=" + run.writtenEvidence().markdownSha256()
                + " aligned=" + run.evidence().counts().alignedObservationCount()
                + " scenarios=" + run.evidence().counts().scenarioCount()
                + " segments=" + run.evidence().counts().anomalySegmentCount()
        );
    }

    static Path requireTrackedPath(Path relativePath) {
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
        throw new IllegalArgumentException("tracked path not found: " + relativePath);
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
            return Path.of(value.trim());
        }

        private static double parseThreshold(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("invalid --threshold: " + value);
            }
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("invalid --threshold: " + value);
            }
            try {
                double threshold = Double.parseDouble(trimmed);
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
