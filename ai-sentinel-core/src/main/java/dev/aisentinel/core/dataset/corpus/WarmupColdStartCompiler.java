package dev.aisentinel.core.dataset.corpus;

/**
 * Cold-start / short-warmup behavior: evaluation begins with minimal baseline formation.
 */
final class WarmupColdStartCompiler {

    private WarmupColdStartCompiler() {
    }

    static CompiledCorpus compile(ScenarioDocument scenario, String seed, String generatorBuildId) {
        FeatureCorpusSupport.firstTransition(scenario);
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
                    FeatureCorpusSupport.elevatedRateFeatures(identity, endpoint, ctx.seedMix(), 2.0),
                    "benign",
                    "warmup"
                );
            },
            ctx -> {
                String identity = ctx.identity(0);
                String endpoint = ctx.endpoint(ctx.tick());
                // Early evaluation ticks remain low-observation / cold-start shaped.
                double rate = ctx.tick() < 2 ? 2.5 : 4.0;
                return TimedPhaseCompiler.labeled(
                    ctx,
                    identity,
                    endpoint,
                    FeatureCorpusSupport.elevatedRateFeatures(identity, endpoint, ctx.seedMix(), rate),
                    "benign",
                    ctx.tick() < 2 ? "cold-start" : "evaluation-normal"
                );
            }
        );
    }
}
