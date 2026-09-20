package dev.aisentinel.core.dataset.corpus;

/**
 * Constants for the repository-owned versioned Kit reference evaluation corpus.
 * <p>
 * Distinct from historical {@code evaluation/reference/}. Paths are relative to the repository root.
 */
final class KitReferenceCorpusLayouts {

    static final String ROOT_RELATIVE = "evaluation/kit-reference";
    static final String INVENTORY_FILE = "inventory.json";
    static final String SCENARIOS_DIR = "scenarios";
    static final String CORPORA_DIR = "corpora";

    /** Inventory document shape version (independent of per-corpus schema versions). */
    static final String INVENTORY_SCHEMA_VERSION = "1";

    /** Durable inventory identity for this repository corpus set. */
    static final String INVENTORY_ID = "kit-reference-corpus";

    /** Release/revision of the checked-in reference inventory. */
    static final String INVENTORY_VERSION = "1.0.0";

    /**
     * Concrete generator build identity bound into every checked-in reference corpus.
     * Changing this requires regenerating and re-verifying the inventory.
     */
    static final String REFERENCE_GENERATOR_BUILD_ID = "aisentinel-kit-reference-corpus@1";

    private KitReferenceCorpusLayouts() {
    }
}
