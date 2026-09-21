package dev.aisentinel.core.evaluation;

/**
 * Command-line entry point for comparing two existing Evaluation Kit runs.
 */
public final class EvaluationComparisonMain {
    private EvaluationComparisonMain() {
    }

    public static void main(String[] args) {
        System.exit(EvaluationComparisonCli.run(args, System.out, System.err));
    }
}
