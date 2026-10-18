package dev.aisentinel.core.dataset.corpus;

/**
 * Stable established-normal behavior across warmup and evaluation (no anomalous transition).
 */
final class EstablishedNormalCompiler {

    private EstablishedNormalCompiler() {
    }

    static CompiledCorpus compile(ScenarioDocument scenario, String seed, String generatorBuildId) {
        FeatureCorpusSupport.firstTransition(scenario);
        return TimedPhaseCompiler.compile(
            scenario,
            seed,
            generatorBuildId,
            ctx -> {
                String identity = ctx.identity(ctx.tick());
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
                String identity = ctx.identity(ctx.tick() + 1);
                String endpoint = ctx.endpoint(ctx.tick() + 1);
                return TimedPhaseCompiler.labeled(
                    ctx,
                    identity,
                    endpoint,
                    FeatureCorpusSupport.normalFeatures(identity, endpoint, ctx.seedMix(), true),
                    "benign",
                    "evaluation-normal"
                );
            }
        );
    }
}
