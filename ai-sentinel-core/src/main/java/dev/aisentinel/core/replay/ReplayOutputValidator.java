package dev.aisentinel.core.replay;

import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.distributed.training.TrainingFingerprintHashes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Integrity validator for replay output.
 */
public final class ReplayOutputValidator {

    private static final Set<String> LABEL_MARKERS = Set.of(
        "SYNTHETIC_ANOMALOUS",
        "LEGITIMATE_ANOMALOUS",
        "ESTABLISHED_NORMAL_BASELINE",
        "WARMUP_NEW_IDENTITY",
        "RAPID_REQUEST_BURST"
    );

    public ValidationSummary validate(Path replayDirectory, ReplayDataset dataset, ReplayConfiguration configuration) throws IOException {
        Path manifestPath = replayDirectory.resolve(ReplaySchemas.MANIFEST_FILE_NAME);
        Path resultsPath = replayDirectory.resolve(ReplaySchemas.RESULTS_FILE_NAME);
        requireExists(manifestPath);
        requireExists(resultsPath);
        String manifestText = Files.readString(manifestPath, StandardCharsets.UTF_8);
        byte[] resultsBytes = Files.readAllBytes(resultsPath);
        String resultsText = new String(resultsBytes, StandardCharsets.UTF_8);
        String actualSha = TrainingFingerprintHashes.sha256HexBytes(resultsBytes);

        ReplayRunManifest manifest = parseManifest(manifestText);
        if (!manifest.datasetId().equals(dataset.manifest().datasetId())) {
            throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Replay manifest datasetId mismatch");
        }
        if (!manifest.datasetEventsSha256().equals(dataset.eventsSha256())) {
            throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Replay manifest dataset checksum mismatch");
        }
        if (!manifest.datasetSchemaVersion().equals(dataset.manifest().datasetSchemaVersion())
            || !manifest.evaluationEventSchemaVersion().equals(dataset.manifest().evaluationEventSchemaVersion())
            || !manifest.featureSchemaVersion().equals(dataset.manifest().featureSchemaVersion())) {
            throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Replay manifest schema mismatch");
        }
        if (!manifest.configurationFingerprint().equals(configuration.configurationFingerprint())) {
            throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Replay configuration fingerprint mismatch");
        }
        if (!manifest.replayRunId().equals(ReplayEngine.deterministicRunId(dataset, configuration))) {
            throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Replay runId mismatch");
        }
        if (!ReplaySchemas.RESULTS_FILE_NAME.equals(manifest.resultsFile())) {
            throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Replay results file mismatch");
        }
        if (!manifest.resultsSha256().equals(actualSha)) {
            throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Replay results checksum mismatch");
        }
        if (manifest.eventCount() != dataset.eventCount()) {
            throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Replay event count mismatch");
        }

        List<ReplayResult> results = parseResults(resultsText);
        if (results.size() != dataset.eventCount()) {
            throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Unexpected replay result count");
        }
        requireOrdering(results, dataset);
        requireUniqueIds(results);
        requireNoLabels(resultsText);
        requireNoSecrets(resultsText);
        requireFiniteScores(results);
        requireConfiguration(results, configuration);
        return new ValidationSummary(results.size(), actualSha, Files.size(resultsPath), Files.size(manifestPath));
    }

    private static ReplayRunManifest parseManifest(String json) {
        return new ReplayRunManifest(
            ReplayJsonSupport.requireString(json, "replaySchemaVersion"),
            ReplayJsonSupport.requireString(json, "replayRunId"),
            ReplayJsonSupport.requireString(json, "datasetId"),
            ReplayJsonSupport.requireString(json, "datasetEventsSha256"),
            ReplayJsonSupport.requireString(json, "datasetSchemaVersion"),
            ReplayJsonSupport.requireString(json, "evaluationEventSchemaVersion"),
            ReplayJsonSupport.requireString(json, "featureSchemaVersion"),
            ReplayJsonSupport.optionalString(json, "annotationSchemaVersion"),
            ReplayJsonSupport.requireString(json, "scorerId"),
            ReplayJsonSupport.optionalString(json, "scorerVersion"),
            ReplayJsonSupport.requireString(json, "policyId"),
            ReplayJsonSupport.optionalString(json, "policyVersion"),
            ReplayJsonSupport.requireString(json, "configurationFingerprint"),
            ReplayJsonSupport.requireString(json, "replayMode"),
            ReplayJsonSupport.requireString(json, "ordering"),
            ReplayJsonSupport.requireLong(json, "eventCount"),
            ReplayJsonSupport.requireString(json, "aiSentinelVersion"),
            ReplayJsonSupport.requireString(json, "resultsFile"),
            ReplayJsonSupport.requireString(json, "resultsSha256")
        );
    }

    private static List<ReplayResult> parseResults(String text) {
        java.util.ArrayList<ReplayResult> results = new java.util.ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            results.add(new ReplayResult(
                ReplayJsonSupport.requireString(line, "replaySchemaVersion"),
                ReplayJsonSupport.requireString(line, "replayRunId"),
                ReplayResultStatus.valueOf(ReplayJsonSupport.requireString(line, "replayStatus")),
                (int) ReplayJsonSupport.requireLong(line, "sequenceNumber"),
                ReplayJsonSupport.requireString(line, "eventId"),
                ReplayJsonSupport.optionalString(line, "correlationId"),
                ReplayJsonSupport.requireString(line, "identityKey"),
                ReplayJsonSupport.optionalString(line, "identityType"),
                java.time.Instant.parse(ReplayJsonSupport.requireString(line, "observedAt")),
                ReplayJsonSupport.requireString(line, "endpointKey"),
                ReplayJsonSupport.requireString(line, "featureSchemaVersion"),
                ReplayJsonSupport.requireString(line, "scorerId"),
                ReplayJsonSupport.optionalString(line, "scorerVersion"),
                ReplayJsonSupport.optionalDouble(line, "anomalyScore"),
                ReplayJsonSupport.optionalDouble(line, "policyScore"),
                EnforcementAction.valueOf(ReplayJsonSupport.requireString(line, "action")),
                ReplayJsonSupport.parseStatuses(ReplayJsonSupport.arrayBody(line, "evaluationStatuses")),
                ReplayJsonSupport.parseRiskFactors(line),
                ReplayJsonSupport.optionalString(line, "policyId"),
                ReplayJsonSupport.optionalString(line, "policyVersion"),
                ReplayJsonSupport.optionalString(line, "evaluationMode")
            ));
        }
        return List.copyOf(results);
    }

    private static void requireOrdering(List<ReplayResult> results, ReplayDataset dataset) {
        for (int i = 0; i < results.size(); i++) {
            ReplayResult result = results.get(i);
            ReplayInputRecord input = dataset.events().get(i).replayInput();
            if (!result.eventId().equals(input.eventId()) || result.sequenceNumber() != input.sequenceNumber()) {
                throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Replay ordering mismatch");
            }
        }
    }

    private static void requireUniqueIds(List<ReplayResult> results) {
        Set<String> ids = new HashSet<>();
        for (ReplayResult result : results) {
            if (!ids.add(result.eventId())) {
                throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Duplicate replay eventId");
            }
        }
    }

    private static void requireNoLabels(String text) {
        for (String marker : LABEL_MARKERS) {
            if (text.contains(marker)) {
                throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Ground-truth label leaked into replay output");
            }
        }
    }

    private static void requireNoSecrets(String text) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (String marker : List.of("bearer ", "\"authorization\"", "\"cookie\"", "\"set-cookie\"", "@example.com", "?token=")) {
            if (lower.contains(marker)) {
                throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Sensitive marker present in replay output");
            }
        }
    }

    private static void requireFiniteScores(List<ReplayResult> results) {
        for (ReplayResult result : results) {
            if (result.anomalyScore() != null && (!Double.isFinite(result.anomalyScore()) || result.anomalyScore() < 0.0 || result.anomalyScore() > 1.0)) {
                throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Invalid replay anomalyScore");
            }
            if (result.policyScore() != null && (!Double.isFinite(result.policyScore()) || result.policyScore() < 0.0 || result.policyScore() > 1.0)) {
                throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Invalid replay policyScore");
            }
        }
    }

    private static void requireConfiguration(List<ReplayResult> results, ReplayConfiguration configuration) {
        for (ReplayResult result : results) {
            if (!result.scorerId().equals(configuration.scorer().scorerId())) {
                throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Replay scorerId mismatch");
            }
            if (!result.policyId().equals(configuration.policy().policyId())) {
                throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Replay policyId mismatch");
            }
        }
    }

    private static void requireExists(Path path) {
        if (!Files.exists(path)) {
            throw new ReplayException(ReplayFailureKind.DATASET_VALIDATION_FAILURE, "Missing replay output artifact: " + path);
        }
    }

    public record ValidationSummary(
        int resultCount,
        String resultsSha256,
        long resultsBytes,
        long manifestBytes
    ) {
    }
}
