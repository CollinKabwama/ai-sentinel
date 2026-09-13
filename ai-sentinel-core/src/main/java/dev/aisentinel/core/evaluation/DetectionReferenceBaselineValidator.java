package dev.aisentinel.core.evaluation;

import dev.aisentinel.distributed.training.TrainingFingerprintHashes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Capture-integrity validation for Official Detection Reference Baseline artifacts.
 * <p>
 * Validates that a manifest describes the same accepted evaluation evidence and
 * annotation bytes used for capture. This is not generalized drift detection.
 */
final class DetectionReferenceBaselineValidator {

    void validatePublishedArtifacts(DetectionReferenceBaselineManifest manifest,
                                    Path baselineDirectory) throws IOException {
        DetectionReferenceBaselineManifest safeManifest = Objects.requireNonNull(manifest, "manifest");
        Path safeDirectory = Objects.requireNonNull(baselineDirectory, "baselineDirectory")
            .toAbsolutePath()
            .normalize();
        DetectionReferenceBaselineManifest.ArtifactDigests artifacts = safeManifest.artifacts();
        if (!DetectionEvaluationEvidenceWriter.JSON_FILE_NAME.equals(artifacts.evaluationJsonFile())
            || !DetectionEvaluationEvidenceWriter.MARKDOWN_FILE_NAME.equals(artifacts.evaluationMarkdownFile())) {
            throw new IllegalArgumentException("unexpected evaluation artifact file names");
        }

        Path jsonPath = safeDirectory.resolve(artifacts.evaluationJsonFile()).normalize();
        Path markdownPath = safeDirectory.resolve(artifacts.evaluationMarkdownFile()).normalize();
        if (!jsonPath.startsWith(safeDirectory) || !markdownPath.startsWith(safeDirectory)) {
            throw new IllegalArgumentException("evaluation artifact path escapes baseline directory");
        }
        if (!Files.isRegularFile(jsonPath) || !Files.isRegularFile(markdownPath)) {
            throw new IllegalArgumentException("missing baseline evaluation artifact");
        }

        byte[] jsonBytes = Files.readAllBytes(jsonPath);
        byte[] markdownBytes = Files.readAllBytes(markdownPath);
        if (!TrainingFingerprintHashes.sha256HexBytes(jsonBytes).equals(artifacts.evaluationJsonSha256())
            || !TrainingFingerprintHashes.sha256HexBytes(markdownBytes).equals(artifacts.evaluationMarkdownSha256())) {
            throw new IllegalArgumentException("evaluation artifact hash mismatch");
        }
        if (jsonBytes.length != artifacts.evaluationJsonBytes()
            || markdownBytes.length != artifacts.evaluationMarkdownBytes()) {
            throw new IllegalArgumentException("evaluation artifact byte-count mismatch");
        }
    }

    void validate(DetectionReferenceBaselineManifest manifest,
                  DetectionEvaluationEvidence evidence,
                  DetectionReferenceBaselineConfiguration configuration,
                  String annotationsSha256,
                  String evaluationJsonSha256,
                  String evaluationMarkdownSha256,
                  long evaluationJsonBytes,
                  long evaluationMarkdownBytes) {
        DetectionReferenceBaselineManifest safeManifest = Objects.requireNonNull(manifest, "manifest");
        DetectionEvaluationEvidence safeEvidence = Objects.requireNonNull(evidence, "evidence");
        DetectionReferenceBaselineConfiguration safeConfiguration =
            Objects.requireNonNull(configuration, "configuration");
        requireSha256("annotationsSha256", annotationsSha256);
        requireSha256("evaluationJsonSha256", evaluationJsonSha256);
        requireSha256("evaluationMarkdownSha256", evaluationMarkdownSha256);

        if (Double.compare(
            safeConfiguration.classification().anomalyThreshold(),
            DetectionReferenceBaselineSchemas.REFERENCE_CLASSIFICATION_THRESHOLD) != 0) {
            throw new IllegalArgumentException("baseline configuration threshold must be 0.5");
        }
        if (Double.compare(
            safeEvidence.classification().anomalyThreshold(),
            DetectionReferenceBaselineSchemas.REFERENCE_CLASSIFICATION_THRESHOLD) != 0) {
            throw new IllegalArgumentException("evidence classification threshold must be 0.5 for baseline capture");
        }
        if (Double.compare(
            safeManifest.classification().anomalyThreshold(),
            safeEvidence.classification().anomalyThreshold()) != 0) {
            throw new IllegalArgumentException("manifest/evidence classification threshold disagreement");
        }
        if (!safeManifest.classification().thresholdBoundary().equals(safeEvidence.classification().thresholdBoundary())) {
            throw new IllegalArgumentException("manifest/evidence thresholdBoundary disagreement");
        }
        if (!DetectionReferenceBaselineSchemas.THRESHOLD_BOUNDARY.equals(safeManifest.classification().thresholdBoundary())) {
            throw new IllegalArgumentException("unsupported baseline thresholdBoundary");
        }

        if (!safeConfiguration.expectedDatasetId().equals(safeEvidence.reference().datasetId())) {
            throw new IllegalArgumentException("baseline dataset ID does not match evidence dataset ID");
        }
        if (!safeManifest.reference().equals(safeEvidence.reference())) {
            throw new IllegalArgumentException("manifest reference provenance does not match evidence");
        }
        if (!safeManifest.reference().datasetId().equals(safeEvidence.replay().datasetId())) {
            throw new IllegalArgumentException("dataset ID mismatch across baseline reference and replay");
        }
        if (!safeConfiguration.expectedAnnotationSchemaVersion()
            .equals(safeEvidence.reference().annotationSchemaVersion())) {
            throw new IllegalArgumentException("annotation schema mismatch");
        }
        if (!safeManifest.reference().annotationSchemaVersion()
            .equals(safeEvidence.reference().annotationSchemaVersion())) {
            throw new IllegalArgumentException("manifest annotation schema does not match evidence");
        }
        if (!annotationsSha256.equals(safeManifest.annotationsSha256())) {
            throw new IllegalArgumentException("annotation content hash mismatch");
        }

        if (!safeManifest.replay().equals(safeEvidence.replay())) {
            throw new IllegalArgumentException("manifest replay provenance does not match evidence");
        }
        if (!safeConfiguration.replayConfiguration().configurationFingerprint()
            .equals(safeEvidence.replay().configurationFingerprint())) {
            throw new IllegalArgumentException("replay configuration fingerprint mismatch");
        }

        if (!safeManifest.structure().equals(safeEvidence.counts())) {
            throw new IllegalArgumentException("structural-count disagreement");
        }

        DetectionReferenceBaselineManifest.ArtifactDigests artifacts = safeManifest.artifacts();
        if (!DetectionEvaluationEvidenceWriter.JSON_FILE_NAME.equals(artifacts.evaluationJsonFile())
            || !DetectionEvaluationEvidenceWriter.MARKDOWN_FILE_NAME.equals(artifacts.evaluationMarkdownFile())) {
            throw new IllegalArgumentException("unexpected evaluation artifact file names");
        }
        if (!evaluationJsonSha256.equals(artifacts.evaluationJsonSha256())
            || !evaluationMarkdownSha256.equals(artifacts.evaluationMarkdownSha256())) {
            throw new IllegalArgumentException("evaluation artifact hash mismatch");
        }
        if (evaluationJsonBytes != artifacts.evaluationJsonBytes()
            || evaluationMarkdownBytes != artifacts.evaluationMarkdownBytes()) {
            throw new IllegalArgumentException("evaluation artifact byte-count mismatch");
        }

        requireBaselineLimitations(safeManifest.limitations());
    }

    private static void requireBaselineLimitations(List<String> limitations) {
        requireContains(limitations, "synthetic");
        requireContains(limitations, "production traffic");
        requireContains(limitations, "production efficacy");
        requireContains(limitations, "production SLA");
        requireContains(limitations, "fixed reference");
        requireContains(limitations, "production recommendation");
        requireContains(limitations, "ANOMALOUS != MALICIOUS");
        requireContains(limitations, "DETECTION DELAY != REQUEST LATENCY");
        requireContains(limitations, "FRAMEWORK ACCEPTANCE != DETECTION QUALITY ACCEPTANCE");
        requireContains(limitations, "MONITOR");
        requireContains(limitations, "ENFORCE");
        requireContains(limitations, "POLICY ACTION != DETECTOR PREDICTION");
        requireContains(limitations, "QUALITY GATE");
    }

    private static void requireContains(List<String> limitations, String needle) {
        String needleUpper = needle.toUpperCase();
        boolean found = limitations.stream()
            .anyMatch(text -> text.toUpperCase().contains(needleUpper));
        if (!found) {
            throw new IllegalArgumentException("missing required baseline limitation covering: " + needle);
        }
    }

    private static void requireSha256(String field, String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be 64 lowercase hex characters");
        }
    }
}
