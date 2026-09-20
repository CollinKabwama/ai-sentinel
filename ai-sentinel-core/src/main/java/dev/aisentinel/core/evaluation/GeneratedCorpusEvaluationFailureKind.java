package dev.aisentinel.core.evaluation;

/**
 * Failure kinds for loading or evaluating generated Evaluation Kit corpora.
 * Distinct from detector outcomes and from historical reference-dataset failures.
 */
public enum GeneratedCorpusEvaluationFailureKind {
    MISSING_ARTIFACT,
    UNSUPPORTED_SCHEMA,
    INTEGRITY_FAILURE,
    GROUND_TRUTH_FAILURE,
    EVALUATION_FAILURE
}
