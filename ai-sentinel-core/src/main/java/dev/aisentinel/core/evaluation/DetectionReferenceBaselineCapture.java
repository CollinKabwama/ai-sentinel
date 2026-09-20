package dev.aisentinel.core.evaluation;

import dev.aisentinel.distributed.training.TrainingFingerprintHashes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Captures the Official Detection Reference Baseline from accepted evaluation evidence.
 * <p>
 * Consumes {@link DetectionEvaluationRunner} output. Does not recompute truth, predictions,
 * confusion matrices, quality metrics, scenario metrics, or temporal metrics.
 */
public final class DetectionReferenceBaselineCapture {

    static final String PURPOSE =
        "Tracked deterministic reference record of accepted detector behavior against the "
            + "AI-Sentinel reference evaluation corpus under the Official Detection Reference "
            + "Baseline configuration.";

    static final Path TRACKED_BASELINE_DIRECTORY = Path.of("evaluation/detection-reference-baseline");

    private final DetectionEvaluationRunner evaluationRunner;
    private final DetectionReferenceBaselineValidator validator;

    public DetectionReferenceBaselineCapture() {
        this(new DetectionEvaluationRunner(), new DetectionReferenceBaselineValidator());
    }

    DetectionReferenceBaselineCapture(DetectionEvaluationRunner evaluationRunner,
                                      DetectionReferenceBaselineValidator validator) {
        this.evaluationRunner = Objects.requireNonNull(evaluationRunner, "evaluationRunner");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    /**
     * Captures the Official Detection Reference Baseline into {@code outputDirectory}.
     * <p>
     * Refuses an existing destination. Generates and validates the complete artifact set in a
     * temporary sibling directory, then publishes it. On filesystems without atomic directory
     * rename, publication uses a non-atomic move after validation; failed captures leave no
     * destination directory.
     */
    public DetectionReferenceBaselineCaptureResult capture(DetectionReferenceBaselineConfiguration configuration,
                                                           Path outputDirectory) throws IOException {
        DetectionReferenceBaselineConfiguration safeConfiguration =
            Objects.requireNonNull(configuration, "configuration");
        Path targetDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory")
            .toAbsolutePath()
            .normalize();
        if (Files.exists(targetDirectory)) {
            throw new FileAlreadyExistsException(
                targetDirectory.toString(),
                null,
                "Official Detection Reference Baseline directory already exists"
            );
        }
        Path parent = targetDirectory.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("outputDirectory parent is required");
        }
        Files.createDirectories(parent);

        DetectionReferenceBaselineConfiguration resolvedConfiguration = resolveInputs(safeConfiguration);

        Path stagingDirectory = Files.createTempDirectory(
            parent,
            targetDirectory.getFileName().toString() + ".capture-tmp-"
        );
        boolean published = false;
        try {
            // Runner refuses existing destinations; staging starts empty so evaluate creates it.
            Files.delete(stagingDirectory);
            DetectionEvaluationRunner.DetectionEvaluationRun run = evaluationRunner.evaluate(
                resolvedConfiguration.datasetDirectory(),
                resolvedConfiguration.annotationsFile(),
                resolvedConfiguration.replayConfiguration(),
                resolvedConfiguration.classification(),
                stagingDirectory
            );

            byte[] annotationBytes = Files.readAllBytes(resolvedConfiguration.annotationsFile());
            String annotationsSha256 = TrainingFingerprintHashes.sha256HexBytes(annotationBytes);

            DetectionReferenceBaselineManifest manifest = buildManifest(
                run.evidence(),
                annotationsSha256,
                run.writtenEvidence()
            );
            validator.validate(
                manifest,
                run.evidence(),
                resolvedConfiguration,
                annotationsSha256,
                run.writtenEvidence().jsonSha256(),
                run.writtenEvidence().markdownSha256(),
                run.writtenEvidence().jsonBytes(),
                run.writtenEvidence().markdownBytes()
            );

            String manifestJson = DetectionReferenceBaselineJson.write(manifest) + "\n";
            Path manifestPath = stagingDirectory.resolve(DetectionReferenceBaselineSchemas.MANIFEST_FILE_NAME);
            Files.writeString(
                manifestPath,
                manifestJson,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            );

            // Re-validate after writing manifest and before publish.
            validator.validate(
                manifest,
                run.evidence(),
                resolvedConfiguration,
                annotationsSha256,
                run.writtenEvidence().jsonSha256(),
                run.writtenEvidence().markdownSha256(),
                run.writtenEvidence().jsonBytes(),
                run.writtenEvidence().markdownBytes()
            );
            requireStagingComplete(stagingDirectory, run.writtenEvidence());

            moveDirectory(stagingDirectory, targetDirectory);
            published = true;

            byte[] publishedManifestBytes =
                Files.readAllBytes(targetDirectory.resolve(DetectionReferenceBaselineSchemas.MANIFEST_FILE_NAME));
            byte[] publishedJsonBytes =
                Files.readAllBytes(targetDirectory.resolve(DetectionEvaluationEvidenceWriter.JSON_FILE_NAME));
            byte[] publishedMarkdownBytes =
                Files.readAllBytes(targetDirectory.resolve(DetectionEvaluationEvidenceWriter.MARKDOWN_FILE_NAME));
            String publishedJsonSha256 = TrainingFingerprintHashes.sha256HexBytes(publishedJsonBytes);
            String publishedMarkdownSha256 = TrainingFingerprintHashes.sha256HexBytes(publishedMarkdownBytes);
            if (!publishedJsonSha256.equals(run.writtenEvidence().jsonSha256())
                || !publishedMarkdownSha256.equals(run.writtenEvidence().markdownSha256())) {
                deleteRecursively(targetDirectory);
                throw new IllegalStateException("published evaluation artifact hashes diverged after move");
            }
            validator.validatePublishedArtifacts(manifest, targetDirectory);

            return new DetectionReferenceBaselineCaptureResult(
                DetectionReferenceBaselineSchemas.BASELINE_ID,
                DetectionReferenceBaselineSchemas.BASELINE_SCHEMA_VERSION,
                targetDirectory,
                manifest,
                TrainingFingerprintHashes.sha256HexBytes(publishedManifestBytes),
                publishedManifestBytes.length,
                publishedJsonSha256,
                publishedMarkdownSha256,
                publishedJsonBytes.length,
                publishedMarkdownBytes.length
            );
        } catch (IOException | RuntimeException e) {
            deleteRecursively(stagingDirectory);
            if (published && Files.exists(targetDirectory)) {
                // Only created by successful move; remove if a later check failed.
                deleteRecursively(targetDirectory);
            }
            throw e;
        }
    }

    static DetectionReferenceBaselineManifest buildManifest(DetectionEvaluationEvidence evidence,
                                                            String annotationsSha256,
                                                            DetectionEvaluationEvidenceWriter.WrittenEvidence written) {
        DetectionEvaluationEvidence safeEvidence = Objects.requireNonNull(evidence, "evidence");
        DetectionEvaluationEvidenceWriter.WrittenEvidence safeWritten =
            Objects.requireNonNull(written, "written");
        return new DetectionReferenceBaselineManifest(
            DetectionReferenceBaselineSchemas.BASELINE_SCHEMA_VERSION,
            DetectionReferenceBaselineSchemas.BASELINE_ID,
            DetectionReferenceBaselineSchemas.BASELINE_KIND,
            PURPOSE,
            safeEvidence.reference(),
            annotationsSha256,
            safeEvidence.replay(),
            safeEvidence.classification(),
            safeEvidence.counts(),
            new DetectionReferenceBaselineManifest.ArtifactDigests(
                DetectionEvaluationEvidenceWriter.JSON_FILE_NAME,
                DetectionEvaluationEvidenceWriter.MARKDOWN_FILE_NAME,
                safeWritten.jsonSha256(),
                safeWritten.markdownSha256(),
                safeWritten.jsonBytes(),
                safeWritten.markdownBytes()
            ),
            baselineLimitations(safeEvidence)
        );
    }

    private static DetectionReferenceBaselineConfiguration resolveInputs(
        DetectionReferenceBaselineConfiguration configuration
    ) {
        Path datasetDirectory = locateExisting(configuration.datasetDirectory(), "datasetDirectory");
        Path annotationsFile = locateExisting(configuration.annotationsFile(), "annotationsFile");
        return new DetectionReferenceBaselineConfiguration(
            configuration.classification(),
            configuration.replayConfiguration(),
            configuration.expectedDatasetId(),
            configuration.expectedAnnotationSchemaVersion(),
            datasetDirectory,
            annotationsFile
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

    static List<String> baselineLimitations(DetectionEvaluationEvidence evidence) {
        List<String> limitations = new ArrayList<>();
        limitations.add(
            "The reference corpus is synthetic/reference evaluation data; SYNTHETIC DATA != PRODUCTION TRAFFIC."
        );
        limitations.add(
            "Baseline results do not establish production efficacy."
        );
        limitations.add(
            "Baseline results do not establish a production SLA."
        );
        limitations.add(
            "Reference classification threshold 0.5 is a fixed reference configuration, not an optimality claim."
        );
        limitations.add(
            "Reference classification threshold 0.5 is not a production recommendation."
        );
        limitations.add(
            "ANOMALOUS != MALICIOUS. Detector labels are evaluation expectations, not malice determinations."
        );
        limitations.add(
            "DETECTION DELAY != REQUEST LATENCY. Temporal delay describes ordered evaluation observations."
        );
        limitations.add(
            "FRAMEWORK ACCEPTANCE != DETECTION QUALITY ACCEPTANCE. Capture integrity is not quality approval."
        );
        limitations.add(
            "No controlled MONITOR pilot evidence is established by this baseline."
        );
        limitations.add(
            "No ENFORCE production approval is established by this baseline."
        );
        limitations.add(
            "POLICY ACTION != DETECTOR PREDICTION. Confusion-matrix values are thresholded anomaly-score predictions."
        );
        limitations.add(
            "BASELINE != QUALITY GATE. Observed metrics are recorded values, not acceptance criteria."
        );
        limitations.add(
            "REFERENCE THRESHOLD != PRODUCTION THRESHOLD and REFERENCE THRESHOLD != ENFORCEMENT THRESHOLD."
        );
        limitations.add(
            "PERFORMANCE != DETECTION EFFECTIVENESS. This baseline is not a performance baseline."
        );
        if (evidence.counts().recoveryWindowCount() == 0) {
            limitations.add("No observed recovery windows are present in this reference corpus capture.");
        }
        return List.copyOf(limitations);
    }

    private static void requireStagingComplete(Path stagingDirectory,
                                               DetectionEvaluationEvidenceWriter.WrittenEvidence written)
        throws IOException {
        Path json = stagingDirectory.resolve(DetectionEvaluationEvidenceWriter.JSON_FILE_NAME);
        Path markdown = stagingDirectory.resolve(DetectionEvaluationEvidenceWriter.MARKDOWN_FILE_NAME);
        Path manifest = stagingDirectory.resolve(DetectionReferenceBaselineSchemas.MANIFEST_FILE_NAME);
        if (!Files.isRegularFile(json) || !Files.isRegularFile(markdown) || !Files.isRegularFile(manifest)) {
            throw new IllegalStateException("incomplete baseline staging directory");
        }
        byte[] jsonBytes = Files.readAllBytes(json);
        byte[] markdownBytes = Files.readAllBytes(markdown);
        if (!TrainingFingerprintHashes.sha256HexBytes(jsonBytes).equals(written.jsonSha256())
            || !TrainingFingerprintHashes.sha256HexBytes(markdownBytes).equals(written.markdownSha256())) {
            throw new IllegalStateException("staging evaluation artifact hashes diverged before publish");
        }
        if (jsonBytes.length != written.jsonBytes() || markdownBytes.length != written.markdownBytes()) {
            throw new IllegalStateException("staging evaluation artifact sizes diverged before publish");
        }
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
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
}
