package dev.aisentinel.core.dataset.corpus;

/**
 * Gradual drift / low-and-slow feature change across the evaluation window.
 */
final class GradualDriftCompiler {

    private GradualDriftCompiler() {
    }

    static CompiledCorpus compile(ScenarioDocument scenario, String seed, String generatorBuildId) {
        FeatureCorpusSupport.requireSingleIntent(scenario, "anomalous", scenario.family());

        return TimedPhaseCompiler.compile(
            scenario,
            seed,
            generatorBuildId,
            ctx -> {
                String identity = ctx.identity(0);
                String endpoint = ctx.endpoint(ctx.tick());
                return TimedPhaseCompiler.labeled(
                    ctx,
                    identity,
                    endpoint,
                    FeatureCorpusSupport.normalFeatures(identity, endpoint, ctx.seedMix(), false),
                    "benign",
                    "warmup"
                );
            },
            ctx -> {
                String identity = ctx.identity(0);
                String endpoint = ctx.endpoint(0);
                return TimedPhaseCompiler.labeled(
                    ctx,
                    identity,
                    endpoint,
                    FeatureCorpusSupport.gradualDriftFeatures(
                        identity,
                        endpoint,
                        ctx.seedMix(),
                        ctx.tick(),
                        scenario.evaluationDurationSeconds()
                    ),
                    "anomalous",
                    "gradual-drift"
                );
            }
        );
    }
}
