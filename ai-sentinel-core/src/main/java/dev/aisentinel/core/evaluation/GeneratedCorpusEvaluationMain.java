package dev.aisentinel.core.evaluation;

/**
 * One-command entry point for offline evaluation of a generated Evaluation Kit corpus directory.
 * <p>
 * Prefer {@code scripts/evaluate-generated-corpus.sh} so callers need not assemble a classpath.
 * This class is a thin adapter over {@link GeneratedCorpusDetectionEvaluator}; it does not
 * redefine evaluation semantics.
 */
public final class GeneratedCorpusEvaluationMain {

    private GeneratedCorpusEvaluationMain() {
    }

    public static void main(String[] args) {
        System.exit(GeneratedCorpusEvaluationCli.run(args, System.out, System.err));
    }
}
