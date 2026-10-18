package dev.aisentinel.core.dataset.corpus;

/**
 * Low-variance baseline followed by small authored deviations.
 */
final class LowVarianceDeviationCompiler {

    private LowVarianceDeviationCompiler() {
    }

    static CompiledCorpus compile(ScenarioDocument scenario, String seed, String generatorBuildId) {
        ScenarioDocument.Transition deviation =
            FeatureCorpusSupport.requireSingleIntent(scenario, "anomalous", scenario.family());

        return TimedPhaseCompiler.compile(
            scenario,
            seed,
            generatorBuildId,
            ctx -> {
                String identity = ctx.identity(0);
                String endpoint = ctx.endpoint(0);
                return TimedPhaseCompiler.labeled(
                    ctx,
                    identity,
                    endpoint,
                    FeatureCorpusSupport.lowVarianceDeviationFeatures(identity, endpoint, ctx.seedMix(), 0),
                    "benign",
                    "warmup"
                );
            },
            ctx -> {
                int start = Math.min(
                    deviation.startsAfterWarmupSeconds(),
                    scenario.evaluationDurationSeconds());
                boolean inDeviation = ctx.tick() >= start;
                String identity = ctx.identity(0);
                String endpoint = ctx.endpoint(0);
                int step = inDeviation ? (ctx.tick() - start + 1) : 0;
                return TimedPhaseCompiler.labeled(
                    ctx,
                    identity,
                    endpoint,
                    FeatureCorpusSupport.lowVarianceDeviationFeatures(identity, endpoint, ctx.seedMix(), step),
                    inDeviation ? "anomalous" : "benign",
                    inDeviation ? "low-variance-deviation" : "evaluation-normal"
                );
            }
        );
    }
}
