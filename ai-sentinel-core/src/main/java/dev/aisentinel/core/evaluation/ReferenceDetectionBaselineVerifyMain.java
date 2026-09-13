package dev.aisentinel.core.evaluation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Maintainer CLI for Official Detection Reference Baseline verification.
 * <p>
 * Always uses {@link DetectionReferenceBaselineConfiguration#officialReference()}
 * (reference classification threshold 0.5). No threshold tuning option is exposed.
 */
public final class ReferenceDetectionBaselineVerifyMain {

    public static final int EXIT_MATCH = 0;
    public static final int EXIT_DRIFT_DETECTED = 1;
    public static final int EXIT_BASELINE_INTEGRITY_FAILURE = 2;
    public static final int EXIT_CURRENT_EVALUATION_FAILURE = 3;
    public static final int EXIT_INVALID_USAGE = 4;

    private ReferenceDetectionBaselineVerifyMain() {
    }

    public static void main(String[] args) throws Exception {
        Arguments parsed;
        try {
            parsed = Arguments.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(EXIT_INVALID_USAGE);
            return;
        }

        Path baselineDirectory = locateTrackedPath(parsed.baselineDirectory());
        DetectionReferenceBaselineVerifier verifier = new DetectionReferenceBaselineVerifier();
        DetectionReferenceBaselineVerificationResult result =
            verifier.verifyOfficial(baselineDirectory);

        if (parsed.outputDirectory() != null) {
            Path output = parsed.outputDirectory().toAbsolutePath().normalize();
            DetectionReferenceBaselineVerifier.WrittenVerificationReports written;
            try {
                written = verifier.writeReports(result, output);
            } catch (IOException | IllegalArgumentException e) {
                System.err.println(e.getMessage());
                System.exit(EXIT_INVALID_USAGE);
                return;
            }
            System.out.println(
                "detectionReferenceBaselineVerification status=" + result.status()
                    + " driftEntries=" + result.totalDriftEntries()
                    + " output=" + written.outputDirectory()
                    + " verificationJsonSha256=" + written.jsonSha256()
                    + " verificationMarkdownSha256=" + written.markdownSha256()
                    + " baselineEvaluationJsonSha256=" + result.baselineEvaluationJsonSha256()
                    + " currentEvaluationJsonSha256=" + result.currentEvaluationJsonSha256()
            );
        } else {
            System.out.println(
                "detectionReferenceBaselineVerification status=" + result.status()
                    + " driftEntries=" + result.totalDriftEntries()
                    + " detail=" + result.detail()
                    + " baselineEvaluationJsonSha256=" + result.baselineEvaluationJsonSha256()
                    + " currentEvaluationJsonSha256=" + result.currentEvaluationJsonSha256()
                    + " evaluationJsonBytesEqual=" + result.evaluationJsonBytesEqual()
                    + " evaluationMarkdownBytesEqual=" + result.evaluationMarkdownBytesEqual()
            );
        }

        System.exit(exitCode(result.status()));
    }

    static int exitCode(DetectionReferenceBaselineVerificationStatus status) {
        return switch (status) {
            case MATCH -> EXIT_MATCH;
            case DRIFT_DETECTED -> EXIT_DRIFT_DETECTED;
            case BASELINE_INTEGRITY_FAILURE -> EXIT_BASELINE_INTEGRITY_FAILURE;
            case CURRENT_EVALUATION_FAILURE -> EXIT_CURRENT_EVALUATION_FAILURE;
        };
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

    record Arguments(Path baselineDirectory, Path outputDirectory) {
        static Arguments parse(String[] args) {
            Path baselineDirectory = DetectionReferenceBaselineVerificationSchemas.TRACKED_BASELINE_DIRECTORY;
            Path outputDirectory = null;
            boolean baselineSeen = false;
            boolean outputSeen = false;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg.startsWith("--baseline=")) {
                    if (baselineSeen) {
                        throw new IllegalArgumentException("duplicate --baseline");
                    }
                    baselineSeen = true;
                    baselineDirectory = Path.of(arg.substring("--baseline=".length()).trim());
                } else if ("--baseline".equals(arg)) {
                    if (baselineSeen) {
                        throw new IllegalArgumentException("duplicate --baseline");
                    }
                    baselineSeen = true;
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("missing value for --baseline");
                    }
                    baselineDirectory = Path.of(args[++i].trim());
                } else if (arg.startsWith("--output=")) {
                    if (outputSeen) {
                        throw new IllegalArgumentException("duplicate --output");
                    }
                    outputSeen = true;
                    outputDirectory = Path.of(arg.substring("--output=".length()).trim());
                } else if ("--output".equals(arg)) {
                    if (outputSeen) {
                        throw new IllegalArgumentException("duplicate --output");
                    }
                    outputSeen = true;
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("missing value for --output");
                    }
                    outputDirectory = Path.of(args[++i].trim());
                } else if ("--help".equals(arg) || "-h".equals(arg)) {
                    throw new IllegalArgumentException(usage());
                } else {
                    throw new IllegalArgumentException("unsupported argument: " + arg + "\n" + usage());
                }
            }
            return new Arguments(baselineDirectory, outputDirectory);
        }

        private static String usage() {
            return "Usage: ReferenceDetectionBaselineVerifyMain [--baseline <dir>] [--output <dir>]\n"
                + "Verifies current official reference evaluation against the tracked Official "
                + "Detection Reference Baseline using DetectionReferenceBaselineConfiguration.officialReference() "
                + "(threshold 0.5; not a general evaluator default).\n"
                + "Exit codes: 0=MATCH, 1=DRIFT_DETECTED, 2=BASELINE_INTEGRITY_FAILURE, "
                + "3=CURRENT_EVALUATION_FAILURE, 4=invalid usage.\n"
                + "DRIFT_DETECTED means difference, not production quality rejection.";
        }
    }
}
