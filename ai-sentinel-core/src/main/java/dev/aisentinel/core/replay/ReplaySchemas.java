package dev.aisentinel.core.replay;

/**
 * Supported deterministic replay schema versions and file names.
 */
public final class ReplaySchemas {

    public static final String REPLAY_SCHEMA_VERSION = "1";
    public static final String REPLAY_MODE_FRESH_RUN = "fresh-run";
    public static final String RESULTS_FILE_NAME = "results.jsonl";
    public static final String MANIFEST_FILE_NAME = "manifest.json";

    private ReplaySchemas() {
    }

    public static String requireSupportedSchemaVersion(String version) {
        if (!REPLAY_SCHEMA_VERSION.equals(version)) {
            throw new IllegalArgumentException("Unsupported replay schema version: " + version);
        }
        return REPLAY_SCHEMA_VERSION;
    }

    public static String requireSupportedReplayMode(String replayMode) {
        if (!REPLAY_MODE_FRESH_RUN.equals(replayMode)) {
            throw new IllegalArgumentException("Unsupported replay mode: " + replayMode);
        }
        return REPLAY_MODE_FRESH_RUN;
    }
}
