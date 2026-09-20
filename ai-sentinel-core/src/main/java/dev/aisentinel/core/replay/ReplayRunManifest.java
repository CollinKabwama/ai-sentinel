package dev.aisentinel.core.replay;

import java.util.Objects;

/**
 * Deterministic manifest for one replay run.
 */
public record ReplayRunManifest(
    String replaySchemaVersion,
    String replayRunId,
    String datasetId,
    String datasetEventsSha256,
    String datasetSchemaVersion,
    String evaluationEventSchemaVersion,
    String featureSchemaVersion,
    String annotationSchemaVersion,
    String scorerId,
    String scorerVersion,
    String policyId,
    String policyVersion,
    String configurationFingerprint,
    String replayMode,
    String ordering,
    long eventCount,
    String aiSentinelVersion,
    String resultsFile,
    String resultsSha256
) {
    public ReplayRunManifest {
        replaySchemaVersion = ReplaySchemas.requireSupportedSchemaVersion(requireNotBlank(
            "replaySchemaVersion", replaySchemaVersion));
        replayRunId = requireNotBlank("replayRunId", replayRunId);
        datasetId = requireNotBlank("datasetId", datasetId);
        datasetEventsSha256 = requireSha256("datasetEventsSha256", datasetEventsSha256);
        datasetSchemaVersion = requireNotBlank("datasetSchemaVersion", datasetSchemaVersion);
        evaluationEventSchemaVersion = requireNotBlank("evaluationEventSchemaVersion", evaluationEventSchemaVersion);
        featureSchemaVersion = requireNotBlank("featureSchemaVersion", featureSchemaVersion);
        annotationSchemaVersion = annotationSchemaVersion == null ? "" : annotationSchemaVersion;
        scorerId = requireNotBlank("scorerId", scorerId);
        scorerVersion = scorerVersion == null ? "" : scorerVersion;
        policyId = requireNotBlank("policyId", policyId);
        policyVersion = policyVersion == null ? "" : policyVersion;
        configurationFingerprint = requireSha256("configurationFingerprint", configurationFingerprint);
        replayMode = ReplaySchemas.requireSupportedReplayMode(requireNotBlank("replayMode", replayMode));
        ordering = requireNotBlank("ordering", ordering);
        if (eventCount < 0L) {
            throw new IllegalArgumentException("eventCount must be >= 0");
        }
        aiSentinelVersion = requireNotBlank("aiSentinelVersion", aiSentinelVersion);
        resultsFile = requireNotBlank("resultsFile", resultsFile);
        resultsSha256 = requireSha256("resultsSha256", resultsSha256);
    }

    private static String requireNotBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String requireSha256(String field, String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be 64 lowercase hex characters");
        }
        return value;
    }
}
