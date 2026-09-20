package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.replay.ReplayConfiguration;
import dev.aisentinel.distributed.training.TrainingFingerprintHashes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Verifies current deterministic evaluation against a persisted Official Detection
 * Reference Baseline and reports exact drift without approving or replacing the baseline.
 */
public final class DetectionReferenceBaselineVerifier {

    private final DetectionReferenceBaselineLoader loader;
    private final DetectionEvaluationRunner evaluationRunner;

    public DetectionReferenceBaselineVerifier() {
        this(new DetectionReferenceBaselineLoader(), new DetectionEvaluationRunner());
    }

    DetectionReferenceBaselineVerifier(DetectionReferenceBaselineLoader loader,
                                       DetectionEvaluationRunner evaluationRunner) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.evaluationRunner = Objects.requireNonNull(evaluationRunner, "evaluationRunner");
    }

    /**
     * Verifies using the official reference configuration (threshold 0.5).
     */
    public DetectionReferenceBaselineVerificationResult verifyOfficial(Path baselineDirectory) {
        DetectionReferenceBaselineConfiguration official =
            DetectionReferenceBaselineConfiguration.officialReference();
        return verify(
            baselineDirectory,
            locateExisting(official.datasetDirectory(), "datasetDirectory"),
            locateExisting(official.annotationsFile(), "annotationsFile"),
            official.replayConfiguration(),
            official.classification(),
            null
        );
    }

    /**
     * Verifies using the official reference configuration and optionally writes reports.
     */
    public DetectionReferenceBaselineVerificationResult verifyOfficial(Path baselineDirectory,
                                                                       Path reportOutputDirectory)
        throws IOException {
        DetectionReferenceBaselineConfiguration official =
            DetectionReferenceBaselineConfiguration.officialReference();
        DetectionReferenceBaselineVerificationResult result = verify(
            baselineDirectory,
            locateExisting(official.datasetDirectory(), "datasetDirectory"),
            locateExisting(official.annotationsFile(), "annotationsFile"),
            official.replayConfiguration(),
            official.classification(),
            null
        );
        if (reportOutputDirectory != null) {
            writeReports(result, reportOutputDirectory);
        }
        return result;
    }

    /**
     * Core verification entry used by the official CLI and focused tests.
     * <p>
     * Official maintainer CLI always supplies the official classification threshold 0.5.
     * Tests may supply alternate classification/replay inputs to exercise drift categories.
     */
    public DetectionReferenceBaselineVerificationResult verify(Path baselineDirectory,
                                                               Path datasetDirectory,
                                                               Path annotationsFile,
                                                               ReplayConfiguration replayConfiguration,
                                                               DetectionClassificationConfiguration classification) {
        return verify(baselineDirectory, datasetDirectory, annotationsFile, replayConfiguration, classification, null);
    }

    /**
     * Compares two persisted baseline directories using the same drift contract as verification.
     * <p>
     * Does not mutate either directory. Lifecycle candidate comparison uses this entry.
     * In the returned result, {@code current*} fields describe {@code otherBaselineDirectory}.
     */
    public DetectionReferenceBaselineVerificationResult comparePersisted(Path officialBaselineDirectory,
                                                                         Path otherBaselineDirectory) {
        DetectionReferenceBaselineLoader.LoadedBaseline official;
        DetectionReferenceBaselineLoader.LoadedBaseline other;
        try {
            official = loader.load(officialBaselineDirectory);
            other = loader.load(otherBaselineDirectory);
        } catch (DetectionReferenceBaselineIntegrityException e) {
            return integrityFailure(e.getMessage());
        }

        boolean jsonEqual = Arrays.equals(official.evaluationJsonBytes(), other.evaluationJsonBytes());
        boolean markdownEqual =
            Arrays.equals(official.evaluationMarkdownBytes(), other.evaluationMarkdownBytes());
        List<DetectionReferenceBaselineDriftEntry> drifts = DetectionReferenceBaselineComparator.compare(
            official.manifest(),
            official.snapshot(),
            other.snapshot(),
            official.manifest().annotationsSha256(),
            other.manifest().annotationsSha256(),
            official.evaluationJsonSha256(),
            other.evaluationJsonSha256(),
            official.evaluationMarkdownSha256(),
            other.evaluationMarkdownSha256(),
            jsonEqual,
            markdownEqual
        );
        DetectionReferenceBaselineVerificationStatus status = drifts.isEmpty()
            ? DetectionReferenceBaselineVerificationStatus.MATCH
            : DetectionReferenceBaselineVerificationStatus.DRIFT_DETECTED;
        return new DetectionReferenceBaselineVerificationResult(
            DetectionReferenceBaselineVerificationSchemas.VERIFICATION_SCHEMA_VERSION,
            DetectionReferenceBaselineVerificationSchemas.REPORT_KIND,
            status,
            official.manifest().baselineId(),
            official.manifest().baselineSchemaVersion(),
            official.manifestSha256(),
            official.evaluationJsonSha256(),
            official.evaluationMarkdownSha256(),
            other.evaluationJsonSha256(),
            other.evaluationMarkdownSha256(),
            jsonEqual,
            markdownEqual,
            status == DetectionReferenceBaselineVerificationStatus.MATCH
                ? "persisted baselines match the complete comparison contract"
                : "persisted baselines differ under the defined comparison contract",
            drifts
        );
    }

    DetectionReferenceBaselineVerificationResult verify(Path baselineDirectory,
                                                        Path datasetDirectory,
                                                        Path annotationsFile,
                                                        ReplayConfiguration replayConfiguration,
                                                        DetectionClassificationConfiguration classification,
                                                        Path unusedReserved) {
        DetectionReferenceBaselineLoader.LoadedBaseline loaded;
        try {
            loaded = loader.load(baselineDirectory);
        } catch (DetectionReferenceBaselineIntegrityException e) {
            return integrityFailure(e.getMessage());
        }

        Path safeDataset = Objects.requireNonNull(datasetDirectory, "datasetDirectory")
            .toAbsolutePath().normalize();
        Path safeAnnotations = Objects.requireNonNull(annotationsFile, "annotationsFile")
            .toAbsolutePath().normalize();
        ReplayConfiguration safeReplay = Objects.requireNonNull(replayConfiguration, "replayConfiguration");
        DetectionClassificationConfiguration safeClassification =
            Objects.requireNonNull(classification, "classification");

        Path stagingParent = loaded.directory().getParent() == null
            ? Path.of(System.getProperty("java.io.tmpdir"))
            : loaded.directory().getParent();
        Path stagingDirectory = null;
        try {
            stagingDirectory = Files.createTempDirectory(stagingParent, "detection-baseline-verify-");
            Files.delete(stagingDirectory);
            DetectionEvaluationRunner.DetectionEvaluationRun run = evaluationRunner.evaluate(
                safeDataset,
                safeAnnotations,
                safeReplay,
                safeClassification,
                stagingDirectory
            );

            byte[] annotationBytes = Files.readAllBytes(safeAnnotations);
            String currentAnnotationsSha256 = TrainingFingerprintHashes.sha256HexBytes(annotationBytes);
            byte[] currentJsonBytes = Files.readAllBytes(
                stagingDirectory.resolve(DetectionEvaluationEvidenceWriter.JSON_FILE_NAME)
            );
            byte[] currentMarkdownBytes = Files.readAllBytes(
                stagingDirectory.resolve(DetectionEvaluationEvidenceWriter.MARKDOWN_FILE_NAME)
            );
            String currentJsonSha256 = TrainingFingerprintHashes.sha256HexBytes(currentJsonBytes);
            String currentMarkdownSha256 = TrainingFingerprintHashes.sha256HexBytes(currentMarkdownBytes);
            boolean jsonEqual = Arrays.equals(loaded.evaluationJsonBytes(), currentJsonBytes);
            boolean markdownEqual = Arrays.equals(loaded.evaluationMarkdownBytes(), currentMarkdownBytes);

            DetectionReferenceBaselineComparisonSnapshot currentSnapshot =
                DetectionReferenceBaselineComparisonSnapshot.fromEvidence(run.evidence());
            List<DetectionReferenceBaselineDriftEntry> drifts = DetectionReferenceBaselineComparator.compare(
                loaded.manifest(),
                loaded.snapshot(),
                currentSnapshot,
                loaded.manifest().annotationsSha256(),
                currentAnnotationsSha256,
                loaded.evaluationJsonSha256(),
                currentJsonSha256,
                loaded.evaluationMarkdownSha256(),
                currentMarkdownSha256,
                jsonEqual,
                markdownEqual
            );

            DetectionReferenceBaselineVerificationStatus status = drifts.isEmpty()
                ? DetectionReferenceBaselineVerificationStatus.MATCH
                : DetectionReferenceBaselineVerificationStatus.DRIFT_DETECTED;
            return new DetectionReferenceBaselineVerificationResult(
                DetectionReferenceBaselineVerificationSchemas.VERIFICATION_SCHEMA_VERSION,
                DetectionReferenceBaselineVerificationSchemas.REPORT_KIND,
                status,
                loaded.manifest().baselineId(),
                loaded.manifest().baselineSchemaVersion(),
                loaded.manifestSha256(),
                loaded.evaluationJsonSha256(),
                loaded.evaluationMarkdownSha256(),
                currentJsonSha256,
                currentMarkdownSha256,
                jsonEqual,
                markdownEqual,
                status == DetectionReferenceBaselineVerificationStatus.MATCH
                    ? "current deterministic evaluation reproduces the official baseline"
                    : "current deterministic evaluation differs from the official baseline",
                drifts
            );
        } catch (DetectionReferenceBaselineIntegrityException e) {
            return integrityFailure(e.getMessage());
        } catch (IOException | RuntimeException e) {
            return new DetectionReferenceBaselineVerificationResult(
                DetectionReferenceBaselineVerificationSchemas.VERIFICATION_SCHEMA_VERSION,
                DetectionReferenceBaselineVerificationSchemas.REPORT_KIND,
                DetectionReferenceBaselineVerificationStatus.CURRENT_EVALUATION_FAILURE,
                loaded.manifest().baselineId(),
                loaded.manifest().baselineSchemaVersion(),
                loaded.manifestSha256(),
                loaded.evaluationJsonSha256(),
                loaded.evaluationMarkdownSha256(),
                "",
                "",
                false,
                false,
                "current evaluation failed: " + e.getMessage(),
                List.of()
            );
        } finally {
            if (stagingDirectory != null) {
                try {
                    deleteRecursively(stagingDirectory);
                } catch (IOException ignored) {
                    // best-effort cleanup of ephemeral current evidence
                }
            }
        }
    }

    /**
     * Writes deterministic verification reports. Refuses an existing destination.
     */
    public WrittenVerificationReports writeReports(DetectionReferenceBaselineVerificationResult result,
                                                   Path outputDirectory) throws IOException {
        DetectionReferenceBaselineVerificationResult safe = Objects.requireNonNull(result, "result");
        Path target = Objects.requireNonNull(outputDirectory, "outputDirectory").toAbsolutePath().normalize();
        if (Files.exists(target)) {
            throw new FileAlreadyExistsException(
                target.toString(),
                null,
                "verification report directory already exists"
            );
        }
        Path parent = target.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("outputDirectory parent is required");
        }
        // Never write into the official baseline directory.
        Path official = DetectionReferenceBaselineVerificationSchemas.TRACKED_BASELINE_DIRECTORY
            .toAbsolutePath().normalize();
        if (target.equals(official) || target.startsWith(official)) {
            throw new IllegalArgumentException("verification reports must not be written into the official baseline directory");
        }
        Files.createDirectories(parent);
        String json = DetectionReferenceBaselineVerificationReports.writeJson(safe) + "\n";
        String markdown = DetectionReferenceBaselineVerificationReports.writeMarkdown(safe) + "\n";
        Path staging = Files.createTempDirectory(parent, target.getFileName().toString() + ".verify-tmp-");
        try {
            Files.writeString(
                staging.resolve(DetectionReferenceBaselineVerificationSchemas.JSON_FILE_NAME),
                json,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            );
            Files.writeString(
                staging.resolve(DetectionReferenceBaselineVerificationSchemas.MARKDOWN_FILE_NAME),
                markdown,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            );
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(staging, target);
            }
            byte[] jsonBytes = Files.readAllBytes(
                target.resolve(DetectionReferenceBaselineVerificationSchemas.JSON_FILE_NAME)
            );
            byte[] markdownBytes = Files.readAllBytes(
                target.resolve(DetectionReferenceBaselineVerificationSchemas.MARKDOWN_FILE_NAME)
            );
            return new WrittenVerificationReports(
                target,
                TrainingFingerprintHashes.sha256HexBytes(jsonBytes),
                TrainingFingerprintHashes.sha256HexBytes(markdownBytes),
                jsonBytes.length,
                markdownBytes.length
            );
        } catch (IOException | RuntimeException e) {
            deleteRecursively(staging);
            throw e;
        }
    }

    private static DetectionReferenceBaselineVerificationResult integrityFailure(String detail) {
        return new DetectionReferenceBaselineVerificationResult(
            DetectionReferenceBaselineVerificationSchemas.VERIFICATION_SCHEMA_VERSION,
            DetectionReferenceBaselineVerificationSchemas.REPORT_KIND,
            DetectionReferenceBaselineVerificationStatus.BASELINE_INTEGRITY_FAILURE,
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            false,
            false,
            detail == null ? "baseline integrity failure" : detail,
            List.of()
        );
    }

    private static Path locateExisting(Path path, String field) {
        Path absolute = Objects.requireNonNull(path, field).toAbsolutePath().normalize();
        if (Files.exists(absolute)) {
            return absolute;
        }
        Path relative = path.normalize();
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relative).normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalArgumentException(field + " not found: " + path);
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
     * Written verification report summary. Paths are for tooling only.
     */
    public record WrittenVerificationReports(
        Path outputDirectory,
        String jsonSha256,
        String markdownSha256,
        long jsonBytes,
        long markdownBytes
    ) {
        public WrittenVerificationReports {
            outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory");
            if (jsonSha256 == null || !jsonSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("jsonSha256 must be 64 lowercase hex characters");
            }
            if (markdownSha256 == null || !markdownSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("markdownSha256 must be 64 lowercase hex characters");
            }
            if (jsonBytes <= 0L || markdownBytes <= 0L) {
                throw new IllegalArgumentException("report byte counts must be > 0");
            }
        }
    }
}
