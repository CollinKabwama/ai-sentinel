package dev.aisentinel.core.evaluation;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Schema and path constants for Official Detection Reference Baseline lifecycle governance.
 * Independent from baseline artifact and verification-report schemas.
 */
public final class DetectionReferenceBaselineLifecycleSchemas {

    public static final String LIFECYCLE_SCHEMA_VERSION = "1";
    public static final String GOVERNANCE_SCHEMA_VERSION = "1";

    public static final String CANDIDATES_DIRECTORY = "candidates";
    public static final String HISTORY_DIRECTORY = "history";
    public static final String GOVERNANCE_DIRECTORY = "governance";
    public static final String BASELINE_SUBDIRECTORY = "baseline";

    public static final String CANDIDATE_RECORD_FILE = "candidate.json";
    public static final String COMPARISON_JSON_FILE = "comparison.json";
    public static final String COMPARISON_MARKDOWN_FILE = "comparison.md";
    public static final String DECISION_RECORD_FILE = "decision.json";
    public static final String DECISION_HASH_FILE = "decision.sha256";
    public static final String PROMOTION_RECORD_FILE = "promotion.json";
    public static final String RETENTION_RECORD_FILE = "retention.json";
    public static final String ROLLBACK_RECORD_FILE = "rollback.json";

    public static final String CANDIDATE_RECORD_KIND = "detection-reference-baseline-candidate";
    public static final String DECISION_RECORD_KIND = "detection-reference-baseline-decision";
    public static final String PROMOTION_RECORD_KIND = "detection-reference-baseline-promotion";
    public static final String RETENTION_RECORD_KIND = "detection-reference-baseline-history-retention";
    public static final String ROLLBACK_RECORD_KIND = "detection-reference-baseline-rollback";

    public static final Path TRACKED_BASELINE_DIRECTORY =
        DetectionReferenceBaselineCapture.TRACKED_BASELINE_DIRECTORY;

    static final Pattern CANDIDATE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$");
    static final Pattern HISTORY_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$");

    private DetectionReferenceBaselineLifecycleSchemas() {
    }
}
