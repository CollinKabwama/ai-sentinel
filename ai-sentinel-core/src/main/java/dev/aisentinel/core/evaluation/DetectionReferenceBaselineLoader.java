package dev.aisentinel.core.evaluation;

import dev.aisentinel.distributed.training.TrainingFingerprintHashes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Loads and validates a persisted Official Detection Reference Baseline directory.
 * <p>
 * Unknown additive JSON object fields in the manifest are ignored after required known
 * fields are validated. Duplicate keys are rejected by {@link DeterministicJson}.
 */
final class DetectionReferenceBaselineLoader {

    LoadedBaseline load(Path baselineDirectory) {
        Path directory = Objects.requireNonNull(baselineDirectory, "baselineDirectory")
            .toAbsolutePath()
            .normalize();
        if (!Files.isDirectory(directory)) {
            throw new DetectionReferenceBaselineIntegrityException(
                "baseline directory does not exist: " + directory
            );
        }
        try {
            Path manifestPath = requireCanonicalFile(directory, DetectionReferenceBaselineSchemas.MANIFEST_FILE_NAME);
            Path jsonPath = requireCanonicalFile(directory, DetectionEvaluationEvidenceWriter.JSON_FILE_NAME);
            Path markdownPath = requireCanonicalFile(directory, DetectionEvaluationEvidenceWriter.MARKDOWN_FILE_NAME);

            byte[] manifestBytes = Files.readAllBytes(manifestPath);
            byte[] jsonBytes = Files.readAllBytes(jsonPath);
            byte[] markdownBytes = Files.readAllBytes(markdownPath);
            String manifestText = new String(manifestBytes, StandardCharsets.UTF_8);
            if (!manifestText.endsWith("\n")) {
                throw new DetectionReferenceBaselineIntegrityException("manifest.json must end with a newline");
            }
            DetectionReferenceBaselineManifest manifest = parseManifest(manifestText.stripTrailing() + "\n");
            String jsonSha256 = TrainingFingerprintHashes.sha256HexBytes(jsonBytes);
            String markdownSha256 = TrainingFingerprintHashes.sha256HexBytes(markdownBytes);
            String manifestSha256 = TrainingFingerprintHashes.sha256HexBytes(manifestBytes);

            DetectionReferenceBaselineManifest.ArtifactDigests artifacts = manifest.artifacts();
            requireExactFileName(artifacts.evaluationJsonFile(), DetectionEvaluationEvidenceWriter.JSON_FILE_NAME);
            requireExactFileName(
                artifacts.evaluationMarkdownFile(),
                DetectionEvaluationEvidenceWriter.MARKDOWN_FILE_NAME
            );
            if (!jsonSha256.equals(artifacts.evaluationJsonSha256())
                || jsonBytes.length != artifacts.evaluationJsonBytes()) {
                throw new DetectionReferenceBaselineIntegrityException(
                    "evaluation.json does not match manifest digests"
                );
            }
            if (!markdownSha256.equals(artifacts.evaluationMarkdownSha256())
                || markdownBytes.length != artifacts.evaluationMarkdownBytes()) {
                throw new DetectionReferenceBaselineIntegrityException(
                    "evaluation.md does not match manifest digests"
                );
            }

            DetectionReferenceBaselineComparisonSnapshot snapshot =
                DetectionReferenceBaselineComparisonSnapshot.fromEvaluationJson(
                    new String(jsonBytes, StandardCharsets.UTF_8)
                );
            reconcileManifestAndEvidence(manifest, snapshot);

            return new LoadedBaseline(
                directory,
                manifest,
                snapshot,
                manifestSha256,
                jsonSha256,
                markdownSha256,
                jsonBytes,
                markdownBytes
            );
        } catch (DetectionReferenceBaselineIntegrityException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new DetectionReferenceBaselineIntegrityException(
                "failed to load official baseline: " + e.getMessage(),
                e
            );
        }
    }

    private static DetectionReferenceBaselineManifest parseManifest(String manifestText) {
        try {
            Map<String, Object> root =
                DeterministicJson.requireObject(DeterministicJson.parse(manifestText.stripTrailing()), "manifest");
            Map<String, Object> reference = DeterministicJson.requireObject(root.get("reference"), "reference");
            Map<String, Object> replay = DeterministicJson.requireObject(root.get("replay"), "replay");
            Map<String, Object> classification =
                DeterministicJson.requireObject(root.get("classification"), "classification");
            Map<String, Object> structure = DeterministicJson.requireObject(root.get("structure"), "structure");
            Map<String, Object> artifacts = DeterministicJson.requireObject(root.get("artifacts"), "artifacts");
            List<Object> limitationsRaw = DeterministicJson.requireArray(root.get("limitations"), "limitations");
            List<String> limitations = limitationsRaw.stream().map(value -> {
                if (!(value instanceof String text) || text.isBlank()) {
                    throw new IllegalArgumentException("limitations entries must be non-blank strings");
                }
                return text;
            }).toList();

            String annotationsSha256 = DeterministicJson.requireString(reference, "annotationsSha256");
            DetectionEvaluationEvidence.ReferenceProvenance referenceProvenance =
                new DetectionEvaluationEvidence.ReferenceProvenance(
                    DeterministicJson.requireString(reference, "datasetId"),
                    DeterministicJson.requireString(reference, "datasetSchemaVersion"),
                    DeterministicJson.requireString(reference, "evaluationEventSchemaVersion"),
                    DeterministicJson.requireString(reference, "featureSchemaVersion"),
                    DeterministicJson.requireString(reference, "annotationSchemaVersion"),
                    DeterministicJson.requireString(reference, "sourceClassification"),
                    DeterministicJson.requireString(reference, "transformationVersion"),
                    DeterministicJson.requireString(reference, "ordering"),
                    DeterministicJson.requireString(reference, "eventsSha256")
                );
            DetectionEvaluationEvidence.ReplayProvenance replayProvenance =
                new DetectionEvaluationEvidence.ReplayProvenance(
                    DeterministicJson.requireString(replay, "replaySchemaVersion"),
                    DeterministicJson.requireString(replay, "replayRunId"),
                    DeterministicJson.requireString(replay, "datasetId"),
                    DeterministicJson.requireString(replay, "replayMode"),
                    DeterministicJson.requireString(replay, "scorerId"),
                    DeterministicJson.optionalString(replay, "scorerVersion", ""),
                    DeterministicJson.requireString(replay, "policyId"),
                    DeterministicJson.optionalString(replay, "policyVersion", ""),
                    DeterministicJson.requireString(replay, "configurationFingerprint"),
                    DeterministicJson.requireString(replay, "aiSentinelVersion"),
                    DeterministicJson.requireString(replay, "resultsSha256")
                );
            DetectionEvaluationEvidence.ClassificationProvenance classificationProvenance =
                new DetectionEvaluationEvidence.ClassificationProvenance(
                    DeterministicJson.requireDouble(classification, "anomalyThreshold"),
                    DeterministicJson.requireString(classification, "thresholdBoundary")
                );
            DetectionEvaluationEvidence.StructuralCounts structuralCounts =
                new DetectionEvaluationEvidence.StructuralCounts(
                    DeterministicJson.requireInt(structure, "referenceEventCount"),
                    DeterministicJson.requireInt(structure, "scenarioCount"),
                    DeterministicJson.requireInt(structure, "alignedObservationCount"),
                    DeterministicJson.requireLong(structure, "expectedNormalObservationCount"),
                    DeterministicJson.requireLong(structure, "expectedAnomalousObservationCount"),
                    DeterministicJson.requireLong(structure, "evaluablePredictionCount"),
                    DeterministicJson.requireLong(structure, "excludedPredictionCount"),
                    DeterministicJson.requireInt(structure, "anomalySegmentCount"),
                    DeterministicJson.requireInt(structure, "detectedSegmentCount"),
                    DeterministicJson.requireInt(structure, "undetectedSegmentCount"),
                    DeterministicJson.requireInt(structure, "recoveryWindowCount"),
                    DeterministicJson.requireInt(structure, "stabilizedRecoveryCount"),
                    DeterministicJson.requireInt(structure, "unstabilizedRecoveryCount")
                );
            DetectionReferenceBaselineManifest.ArtifactDigests artifactDigests =
                new DetectionReferenceBaselineManifest.ArtifactDigests(
                    DeterministicJson.requireString(artifacts, "evaluationJsonFile"),
                    DeterministicJson.requireString(artifacts, "evaluationMarkdownFile"),
                    DeterministicJson.requireString(artifacts, "evaluationJsonSha256"),
                    DeterministicJson.requireString(artifacts, "evaluationMarkdownSha256"),
                    DeterministicJson.requireLong(artifacts, "evaluationJsonBytes"),
                    DeterministicJson.requireLong(artifacts, "evaluationMarkdownBytes")
                );
            return new DetectionReferenceBaselineManifest(
                DeterministicJson.requireString(root, "baselineSchemaVersion"),
                DeterministicJson.requireString(root, "baselineId"),
                DeterministicJson.requireString(root, "baselineKind"),
                DeterministicJson.requireString(root, "purpose"),
                referenceProvenance,
                annotationsSha256,
                replayProvenance,
                classificationProvenance,
                structuralCounts,
                artifactDigests,
                limitations
            );
        } catch (RuntimeException e) {
            throw new DetectionReferenceBaselineIntegrityException(
                "invalid baseline manifest: " + e.getMessage(),
                e
            );
        }
    }

    private static void reconcileManifestAndEvidence(DetectionReferenceBaselineManifest manifest,
                                                     DetectionReferenceBaselineComparisonSnapshot snapshot) {
        if (!manifest.reference().equals(snapshot.reference())) {
            throw new DetectionReferenceBaselineIntegrityException(
                "manifest reference provenance contradicts evaluation.json"
            );
        }
        if (!manifest.replay().equals(snapshot.replay())) {
            throw new DetectionReferenceBaselineIntegrityException(
                "manifest replay provenance contradicts evaluation.json"
            );
        }
        if (!manifest.classification().equals(snapshot.classification())) {
            throw new DetectionReferenceBaselineIntegrityException(
                "manifest classification contradicts evaluation.json"
            );
        }
        if (!manifest.structure().equals(snapshot.structure())) {
            throw new DetectionReferenceBaselineIntegrityException(
                "manifest structural counts contradict evaluation.json"
            );
        }
        if (manifest.structure().anomalySegmentCount() != snapshot.temporal().anomalySegmentCount()
            || manifest.structure().detectedSegmentCount() != snapshot.temporal().detectedSegmentCount()
            || manifest.structure().undetectedSegmentCount() != snapshot.temporal().undetectedSegmentCount()
            || manifest.structure().recoveryWindowCount() != snapshot.temporal().recoveryWindowCount()) {
            throw new DetectionReferenceBaselineIntegrityException(
                "manifest temporal structural counts contradict evaluation.json temporal evidence"
            );
        }
    }

    private static Path requireCanonicalFile(Path directory, String fileName) {
        requireExactFileName(fileName, fileName);
        Path resolved = directory.resolve(fileName).normalize();
        if (!resolved.startsWith(directory)) {
            throw new DetectionReferenceBaselineIntegrityException("artifact path escapes baseline directory");
        }
        if (!Files.isRegularFile(resolved)) {
            throw new DetectionReferenceBaselineIntegrityException("missing baseline artifact: " + fileName);
        }
        if (Files.isSymbolicLink(resolved)) {
            throw new DetectionReferenceBaselineIntegrityException(
                "baseline artifact must not be a symbolic link: " + fileName
            );
        }
        return resolved;
    }

    private static void requireExactFileName(String actual, String expected) {
        if (actual == null || actual.isBlank()) {
            throw new DetectionReferenceBaselineIntegrityException("artifact file name is required");
        }
        if (actual.contains("/") || actual.contains("\\") || actual.contains("..")) {
            throw new DetectionReferenceBaselineIntegrityException("artifact file name must be a simple file name");
        }
        if (!expected.equals(actual)) {
            throw new DetectionReferenceBaselineIntegrityException(
                "unexpected artifact file name: " + actual + " (expected " + expected + ")"
            );
        }
    }

    record LoadedBaseline(
        Path directory,
        DetectionReferenceBaselineManifest manifest,
        DetectionReferenceBaselineComparisonSnapshot snapshot,
        String manifestSha256,
        String evaluationJsonSha256,
        String evaluationMarkdownSha256,
        byte[] evaluationJsonBytes,
        byte[] evaluationMarkdownBytes
    ) {
        LoadedBaseline {
            directory = Objects.requireNonNull(directory, "directory");
            manifest = Objects.requireNonNull(manifest, "manifest");
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            manifestSha256 = requireSha(manifestSha256);
            evaluationJsonSha256 = requireSha(evaluationJsonSha256);
            evaluationMarkdownSha256 = requireSha(evaluationMarkdownSha256);
            evaluationJsonBytes = Objects.requireNonNull(evaluationJsonBytes, "evaluationJsonBytes").clone();
            evaluationMarkdownBytes =
                Objects.requireNonNull(evaluationMarkdownBytes, "evaluationMarkdownBytes").clone();
        }

        private static String requireSha(String value) {
            if (value == null || !value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sha256 must be 64 lowercase hex characters");
            }
            return value;
        }
    }
}
