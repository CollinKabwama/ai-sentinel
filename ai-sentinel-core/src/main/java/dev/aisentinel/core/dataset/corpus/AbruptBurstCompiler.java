package dev.aisentinel.core.dataset.corpus;

/**
 * Abrupt single-identity request-rate burst after warmup (also used by warmup-then-burst).
 */
final class AbruptBurstCompiler {

    private AbruptBurstCompiler() {
    }

    static CompiledCorpus compile(ScenarioDocument scenario, String seed, String generatorBuildId) {
        ScenarioDocument.Transition burst =
            FeatureCorpusSupport.requireSingleIntent(scenario, "anomalous", scenario.family());

        return TimedPhaseCompiler.compile(
            scenario,
            seed,
            generatorBuildId,
            ctx -> {
                String identity = ctx.identity(
                    ctx.tick() + (int) (ctx.seedMix() % ctx.scenario().identityCount()));
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
                int burstStart = Math.min(
                    burst.startsAfterWarmupSeconds(),
                    scenario.evaluationDurationSeconds());
                boolean inBurst = ctx.tick() >= burstStart;
                if (inBurst) {
                    String identity = ctx.identity(0);
                    String endpoint = ctx.endpoint(0);
                    return TimedPhaseCompiler.labeled(
                        ctx,
                        identity,
                        endpoint,
                        FeatureCorpusSupport.burstFeatures(identity, endpoint, ctx.seedMix()),
                        "anomalous",
                        "burst"
                    );
                }
                String identity = ctx.identity(
                    ctx.tick() + 1 + (int) (ctx.seedMix() % ctx.scenario().identityCount()));
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
