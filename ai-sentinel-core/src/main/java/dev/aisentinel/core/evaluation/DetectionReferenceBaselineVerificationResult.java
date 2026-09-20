package dev.aisentinel.core.evaluation;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable result of one Official Detection Reference Baseline verification run.
 */
public record DetectionReferenceBaselineVerificationResult(
    String verificationSchemaVersion,
    String reportKind,
    DetectionReferenceBaselineVerificationStatus status,
    String baselineId,
    String baselineSchemaVersion,
    String baselineManifestSha256,
    String baselineEvaluationJsonSha256,
    String baselineEvaluationMarkdownSha256,
    String currentEvaluationJsonSha256,
    String currentEvaluationMarkdownSha256,
    boolean evaluationJsonBytesEqual,
    boolean evaluationMarkdownBytesEqual,
    String detail,
    List<DetectionReferenceBaselineDriftEntry> driftEntries
) {
    public DetectionReferenceBaselineVerificationResult {
        verificationSchemaVersion = requireNotBlank("verificationSchemaVersion", verificationSchemaVersion);
        reportKind = requireNotBlank("reportKind", reportKind);
        status = Objects.requireNonNull(status, "status");
        baselineId = baselineId == null ? "" : baselineId;
        baselineSchemaVersion = baselineSchemaVersion == null ? "" : baselineSchemaVersion;
        baselineManifestSha256 = blankToEmpty(baselineManifestSha256);
        baselineEvaluationJsonSha256 = blankToEmpty(baselineEvaluationJsonSha256);
        baselineEvaluationMarkdownSha256 = blankToEmpty(baselineEvaluationMarkdownSha256);
        currentEvaluationJsonSha256 = blankToEmpty(currentEvaluationJsonSha256);
        currentEvaluationMarkdownSha256 = blankToEmpty(currentEvaluationMarkdownSha256);
        detail = detail == null ? "" : detail;
        driftEntries = driftEntries == null ? List.of() : List.copyOf(driftEntries);
        for (DetectionReferenceBaselineDriftEntry entry : driftEntries) {
            Objects.requireNonNull(entry, "driftEntry");
        }
        if (!DetectionReferenceBaselineVerificationSchemas.VERIFICATION_SCHEMA_VERSION
            .equals(verificationSchemaVersion)) {
            throw new IllegalArgumentException("unsupported verificationSchemaVersion: " + verificationSchemaVersion);
        }
        if (!DetectionReferenceBaselineVerificationSchemas.REPORT_KIND.equals(reportKind)) {
            throw new IllegalArgumentException("unexpected reportKind: " + reportKind);
        }
        if (status == DetectionReferenceBaselineVerificationStatus.MATCH && !driftEntries.isEmpty()) {
            throw new IllegalArgumentException("MATCH cannot include drift entries");
        }
        if (status == DetectionReferenceBaselineVerificationStatus.DRIFT_DETECTED && driftEntries.isEmpty()) {
            throw new IllegalArgumentException("DRIFT_DETECTED requires at least one drift entry");
        }
    }

    public int totalDriftEntries() {
        return driftEntries.size();
    }

    public Map<DetectionReferenceBaselineDriftCategory, Integer> driftCountsByCategory() {
        EnumMap<DetectionReferenceBaselineDriftCategory, Integer> counts =
            new EnumMap<>(DetectionReferenceBaselineDriftCategory.class);
        for (DetectionReferenceBaselineDriftCategory category : DetectionReferenceBaselineDriftCategory.values()) {
            counts.put(category, 0);
        }
        for (DetectionReferenceBaselineDriftEntry entry : driftEntries) {
            counts.put(entry.category(), counts.get(entry.category()) + 1);
        }
        return Map.copyOf(counts);
    }

    static List<DetectionReferenceBaselineDriftEntry> sorted(List<DetectionReferenceBaselineDriftEntry> entries) {
        return entries.stream()
            .sorted(Comparator
                .comparing(DetectionReferenceBaselineDriftEntry::category)
                .thenComparing(DetectionReferenceBaselineDriftEntry::field)
                .thenComparing(DetectionReferenceBaselineDriftEntry::baselineValue)
                .thenComparing(DetectionReferenceBaselineDriftEntry::currentValue))
            .toList();
    }

    private static String requireNotBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }
}
