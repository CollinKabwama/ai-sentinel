package dev.aisentinel.core.evaluation;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable summary of one Official Detection Reference Baseline lifecycle action.
 */
public record DetectionReferenceBaselineLifecycleResult(
    String action,
    String candidateId,
    String historyId,
    DetectionReferenceBaselineVerificationStatus comparisonStatus,
    int driftEntries,
    String detail,
    Path officialDirectory,
    Path candidateDirectory,
    Path historyDirectory,
    String governanceRecordSha256
) {
    public DetectionReferenceBaselineLifecycleResult {
        action = requireNotBlank("action", action);
        candidateId = candidateId == null ? "" : candidateId;
        historyId = historyId == null ? "" : historyId;
        detail = detail == null ? "" : detail;
        officialDirectory = Objects.requireNonNull(officialDirectory, "officialDirectory");
        // candidateDirectory / historyDirectory may be null for actions that do not use them
        governanceRecordSha256 = governanceRecordSha256 == null ? "" : governanceRecordSha256;
        if (!governanceRecordSha256.isEmpty() && !governanceRecordSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("governanceRecordSha256 must be 64 lowercase hex characters");
        }
        if (driftEntries < 0) {
            throw new IllegalArgumentException("driftEntries must be >= 0");
        }
    }

    private static String requireNotBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
