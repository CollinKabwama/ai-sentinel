package dev.aisentinel.core.evaluation;

/**
 * One-command entry point for offline Evaluation Kit evaluation.
 * <p>
 * Prefer {@code scripts/evaluate-generated-corpus.sh} so callers need not assemble a classpath.
 * Accepts {@code --corpus} (generated corpus) or {@code --dataset} (evaluator-provided BYO).
 * This class is a thin adapter over {@link GeneratedCorpusDetectionEvaluator} /
 * {@link EvaluatorProvidedDatasetEvaluator}; it does not redefine evaluation semantics.
 */
public final class GeneratedCorpusEvaluationMain {

    private GeneratedCorpusEvaluationMain() {
    }

    public static void main(String[] args) {
        System.exit(GeneratedCorpusEvaluationCli.run(args, System.out, System.err));
    }
}
