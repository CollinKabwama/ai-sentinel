package dev.aisentinel.core.dataset.corpus;

/**
 * Contract and artifact constants for deterministic Evaluation Kit corpus generation.
 * <p>
 * Package-private: callers of {@link CorpusGenerator} obtain the values that matter (corpus id,
 * generator contract version, representation mode, artifact paths) from the returned
 * {@link GeneratedCorpus}; artifact filenames and internal sentinel values are not durable
 * external API and stay internal to this package.
 */
final class CorpusGeneratorSchemas {

    /** Generator I/O contract version (independent of concrete build identity). */
    public static final String GENERATOR_CONTRACT_VERSION = "1";

    public static final String CORPUS_SCHEMA_VERSION = "1";
    public static final String ANNOTATION_SCHEMA_VERSION = "1";
    public static final String REPRESENTATION_MODE_FEATURE_LEVEL = "feature-level";

    public static final String CORPUS_MANIFEST_FILE_NAME = "corpus-manifest.json";
    public static final String ANNOTATIONS_FILE_NAME = "annotations.json";
    public static final String EVENTS_FILE_NAME = "events.jsonl";

    /** Scenario metadata key selecting the compiler family for this MVP. */
    public static final String METADATA_FAMILY_KEY = "family";

    /** First supported scenario family: warmup followed by an anomalous burst. */
    public static final String FAMILY_WARMUP_THEN_BURST = "warmup-then-burst";

    public static final String SOURCE_CLASSIFICATION = "evaluation-kit-generated-corpus";

    /**
     * Transformation / generator lineage recorded on the replay-compatible dataset manifest.
     * Distinct from {@link #GENERATOR_CONTRACT_VERSION}.
     */
    public static final String TRANSFORMATION_VERSION = "corpus-generator-mvp-1";

    public static final String SCORER_ID_FEATURE_CORPUS = "corpus-feature";

    private CorpusGeneratorSchemas() {
    }
}
