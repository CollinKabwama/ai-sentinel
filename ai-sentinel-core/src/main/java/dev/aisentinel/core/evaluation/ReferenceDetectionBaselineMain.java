package dev.aisentinel.core.evaluation;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Maintainer CLI for capturing the Official Detection Reference Baseline.
 * <p>
 * Uses {@link DetectionReferenceBaselineConfiguration#officialReference()}, which binds the
 * approved reference classification threshold {@code 0.5}. This is not a general evaluator
 * default; general evidence generation still requires an explicit {@code --threshold}.
 */
public final class ReferenceDetectionBaselineMain {

    private ReferenceDetectionBaselineMain() {
    }

    public static void main(String[] args) throws Exception {
        Arguments parsed = Arguments.parse(args);
        DetectionReferenceBaselineConfiguration configuration = resolveOfficialConfiguration();
        DetectionReferenceBaselineCaptureResult result =
            new DetectionReferenceBaselineCapture().capture(configuration, parsed.outputDirectory());
        System.out.println(
            "detectionReferenceBaseline output=" + result.outputDirectory()
                + " baselineId=" + result.baselineId()
                + " manifestSha256=" + result.manifestSha256()
                + " evaluationJsonSha256=" + result.evaluationJsonSha256()
                + " evaluationMarkdownSha256=" + result.evaluationMarkdownSha256()
                + " threshold=" + DetectionReferenceBaselineSchemas.REFERENCE_CLASSIFICATION_THRESHOLD
        );
    }

    private static DetectionReferenceBaselineConfiguration resolveOfficialConfiguration() {
        DetectionReferenceBaselineConfiguration official = DetectionReferenceBaselineConfiguration.officialReference();
        Path datasetDirectory = locateTrackedPath(official.datasetDirectory());
        Path annotationsFile = locateTrackedPath(official.annotationsFile());
        return new DetectionReferenceBaselineConfiguration(
            official.classification(),
            official.replayConfiguration(),
            official.expectedDatasetId(),
            official.expectedAnnotationSchemaVersion(),
            datasetDirectory,
            annotationsFile
        );
    }

    static Path locateTrackedPath(Path relativePath) {
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

    record Arguments(Path outputDirectory) {
        static Arguments parse(String[] args) {
            Path outputDirectory = DetectionReferenceBaselineCapture.TRACKED_BASELINE_DIRECTORY;
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
                } else if ("--help".equals(arg) || "-h".equals(arg)) {
                    throw new IllegalArgumentException(usage());
                } else {
                    throw new IllegalArgumentException("unsupported argument: " + arg + "\n" + usage());
                }
            }
            return new Arguments(outputDirectory);
        }

        private static Path parseOutput(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("missing value for --output");
            }
            return Path.of(value.trim());
        }

        private static String usage() {
            return "Usage: ReferenceDetectionBaselineMain [--output <dir>]\n"
                + "Captures the Official Detection Reference Baseline using "
                + "DetectionReferenceBaselineConfiguration.officialReference() "
                + "(reference classification threshold 0.5). "
                + "Does not introduce a general evaluator threshold default. "
                + "Refuses an existing destination (no --force/--overwrite).";
        }
    }
}
