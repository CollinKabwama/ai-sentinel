package dev.aisentinel.core.scoring.lifecycle;

/**
 * Filesystem layout constants for optional local model-lifecycle governance state.
 * <p>
 * Local filesystem governance only — not distributed consensus, not a production
 * control plane. Publication uses create-new semantics where possible; not claimed
 * transactional or crash-proof across all files.
 */
public final class ModelLifecycleSchemas {

    public static final String SCHEMA_VERSION = "model-lifecycle-governance-v1";

    public static final String CHAMPION_FILE = "champion.json";
    public static final String CHALLENGER_FILE = "challenger.json";
    public static final String DECISION_FILE = "decision.json";
    public static final String COMPARISON_BINDING_FILE = "comparison-binding.json";
    public static final String HISTORY_DIRECTORY = "history";
    public static final String STATE_FILE = "state.json";

    private ModelLifecycleSchemas() {
    }
}
