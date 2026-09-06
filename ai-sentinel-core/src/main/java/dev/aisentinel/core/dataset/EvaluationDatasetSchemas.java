package dev.aisentinel.core.dataset;

/**
 * Supported dataset export schema versions.
 */
public final class EvaluationDatasetSchemas {

    public static final String DATASET_SCHEMA_VERSION = "1";
    public static final String SOURCE_CLASSIFICATION_EVALUATION_EXPORT = "evaluation-export";
    public static final String ORDERING_APPEND_ORDER = "append-order";
    public static final String EVENTS_FILE_NAME = "events.jsonl";
    public static final String MANIFEST_FILE_NAME = "manifest.json";

    private EvaluationDatasetSchemas() {
    }

    public static String requireSupportedDatasetSchemaVersion(String version) {
        if (!DATASET_SCHEMA_VERSION.equals(version)) {
            throw new IllegalArgumentException("Unsupported dataset schema version: " + version);
        }
        return DATASET_SCHEMA_VERSION;
    }

    public static String requireSupportedOrdering(String ordering) {
        if (!ORDERING_APPEND_ORDER.equals(ordering)) {
            throw new IllegalArgumentException("Unsupported dataset ordering: " + ordering);
        }
        return ORDERING_APPEND_ORDER;
    }
}
