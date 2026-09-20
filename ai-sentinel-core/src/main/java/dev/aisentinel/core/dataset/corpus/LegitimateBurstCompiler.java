package dev.aisentinel.core.dataset.corpus;

/**
 * Legitimate high-volume burst / workload step-up (authored as benign condition).
 */
final class LegitimateBurstCompiler {

    private LegitimateBurstCompiler() {
    }

    static CompiledCorpus compile(ScenarioDocument scenario, String seed, String generatorBuildId) {
        ScenarioDocument.Transition burst =
            FeatureCorpusSupport.requireSingleIntent(scenario, "legitimate", scenario.family());

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
                boolean inBurst = ctx.tick() >= burstStart;
                // A benign volume step-up plausibly spreads across the declared population/
                // endpoints (e.g. many users ramping together), unlike a single-source attack
                // concentrating on one identity/endpoint — keep rotating during the burst too.
                String identity = ctx.identity(ctx.tick() + 1);
                String endpoint = ctx.endpoint(ctx.tick() + 1);
                if (inBurst) {
                    return TimedPhaseCompiler.labeled(
                        ctx,
                        identity,
                        endpoint,
                        FeatureCorpusSupport.legitimateBurstFeatures(identity, endpoint, ctx.seedMix()),
                        "benign",
                        "legitimate-burst"
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
