package dev.aisentinel.core.dataset.corpus;

/**
 * Endpoint-distribution / endpoint-entropy change after warmup.
 */
final class EndpointDistributionChangeCompiler {

    private EndpointDistributionChangeCompiler() {
    }

    static CompiledCorpus compile(ScenarioDocument scenario, String seed, String generatorBuildId) {
        ScenarioDocument.Transition change =
            FeatureCorpusSupport.requireSingleIntent(scenario, "anomalous", scenario.family());

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
                int changeStart = Math.min(
                    change.startsAfterWarmupSeconds(),
                    scenario.evaluationDurationSeconds());
                boolean changed = ctx.tick() >= changeStart;
                String identity = ctx.identity(0);
                // Collapse traffic onto a single endpoint after the transition.
                String endpoint = changed ? ctx.endpoint(0) : ctx.endpoint(ctx.tick() + 1);
                if (changed) {
                    return TimedPhaseCompiler.labeled(
                        ctx,
                        identity,
                        endpoint,
                        FeatureCorpusSupport.endpointShiftFeatures(identity, endpoint, ctx.seedMix()),
                        "anomalous",
                        "endpoint-distribution-change"
                    );
                }
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
