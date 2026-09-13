package dev.aisentinel.core.evaluation;

import java.nio.file.Path;

/**
 * Schema and path constants for Official Detection Reference Baseline verification reports.
 * Independent from the baseline artifact schema.
 */
public final class DetectionReferenceBaselineVerificationSchemas {

    public static final String VERIFICATION_SCHEMA_VERSION = "1";
    public static final String REPORT_KIND = "official-detection-reference-baseline-verification";
    public static final String JSON_FILE_NAME = "verification.json";
    public static final String MARKDOWN_FILE_NAME = "verification.md";
    public static final Path TRACKED_BASELINE_DIRECTORY =
        DetectionReferenceBaselineCapture.TRACKED_BASELINE_DIRECTORY;

    private DetectionReferenceBaselineVerificationSchemas() {
    }
}
