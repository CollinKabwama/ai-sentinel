package dev.aisentinel.core.dataset.corpus;

/**
 * Simultaneous multi-feature anomalous shift after warmup.
 */
final class MultiFeatureAnomalyCompiler {

    private MultiFeatureAnomalyCompiler() {
    }

    static CompiledCorpus compile(ScenarioDocument scenario, String seed, String generatorBuildId) {
        ScenarioDocument.Transition anomaly =
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
                int start = Math.min(
                    anomaly.startsAfterWarmupSeconds(),
                    scenario.evaluationDurationSeconds());
                boolean anomalous = ctx.tick() >= start;
                String identity = ctx.identity(0);
                String endpoint = ctx.endpoint(0);
                if (anomalous) {
                    return TimedPhaseCompiler.labeled(
                        ctx,
                        identity,
                        endpoint,
                        FeatureCorpusSupport.multiFeatureAnomalyFeatures(
                            identity, endpoint, ctx.seedMix()),
                        "anomalous",
                        "multi-feature-anomaly"
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
