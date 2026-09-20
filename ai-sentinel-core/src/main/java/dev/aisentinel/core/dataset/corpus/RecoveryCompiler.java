package dev.aisentinel.core.dataset.corpus;

/**
 * Anomalous burst followed by recovery to established-normal behavior.
 */
final class RecoveryCompiler {

    private RecoveryCompiler() {
    }

    static CompiledCorpus compile(ScenarioDocument scenario, String seed, String generatorBuildId) {
        ScenarioDocument.Transition burst =
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
                int burstStart = Math.min(
                    burst.startsAfterWarmupSeconds(),
                    scenario.evaluationDurationSeconds());
                // Recover in the final third of the evaluation window when possible.
                int recoveryStart = Math.max(
                    burstStart + 1,
                    (scenario.evaluationDurationSeconds() * 2) / 3);
                boolean inBurst = ctx.tick() >= burstStart && ctx.tick() < recoveryStart;
                boolean recovering = ctx.tick() >= recoveryStart;
                String identity = ctx.identity(0);
                String endpoint = ctx.endpoint(0);
                if (inBurst) {
                    return TimedPhaseCompiler.labeled(
                        ctx,
                        identity,
                        endpoint,
                        FeatureCorpusSupport.burstFeatures(identity, endpoint, ctx.seedMix()),
                        "anomalous",
                        "burst"
                    );
                }
                if (recovering && burstStart < scenario.evaluationDurationSeconds()) {
                    return TimedPhaseCompiler.labeled(
                        ctx,
                        identity,
                        endpoint,
                        FeatureCorpusSupport.normalFeatures(identity, endpoint, ctx.seedMix(), true),
                        "benign",
                        "recovery"
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
