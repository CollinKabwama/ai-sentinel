package dev.aisentinel.core.evaluation;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable summary of one successful Official Detection Reference Baseline capture.
 * <p>
 * Paths are for maintainer tooling only and are never written into canonical artifacts.
 */
public record DetectionReferenceBaselineCaptureResult(
    String baselineId,
    String baselineSchemaVersion,
    Path outputDirectory,
    DetectionReferenceBaselineManifest manifest,
    String manifestSha256,
    long manifestBytes,
    String evaluationJsonSha256,
    String evaluationMarkdownSha256,
    long evaluationJsonBytes,
    long evaluationMarkdownBytes
) {
    public DetectionReferenceBaselineCaptureResult {
        baselineId = requireNotBlank("baselineId", baselineId);
        baselineSchemaVersion = requireNotBlank("baselineSchemaVersion", baselineSchemaVersion);
        outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory");
        manifest = Objects.requireNonNull(manifest, "manifest");
        manifestSha256 = requireSha256("manifestSha256", manifestSha256);
        evaluationJsonSha256 = requireSha256("evaluationJsonSha256", evaluationJsonSha256);
        evaluationMarkdownSha256 = requireSha256("evaluationMarkdownSha256", evaluationMarkdownSha256);
        if (manifestBytes <= 0L || evaluationJsonBytes <= 0L || evaluationMarkdownBytes <= 0L) {
            throw new IllegalArgumentException("artifact byte counts must be > 0");
        }
        if (!DetectionReferenceBaselineSchemas.BASELINE_ID.equals(baselineId)) {
            throw new IllegalArgumentException("unexpected baselineId: " + baselineId);
        }
        if (!DetectionReferenceBaselineSchemas.BASELINE_SCHEMA_VERSION.equals(baselineSchemaVersion)) {
            throw new IllegalArgumentException("unexpected baselineSchemaVersion: " + baselineSchemaVersion);
        }
    }

    private static String requireNotBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String requireSha256(String field, String value) {
        String safe = requireNotBlank(field, value);
        if (!safe.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be 64 lowercase hex characters");
        }
        return safe;
    }
}
