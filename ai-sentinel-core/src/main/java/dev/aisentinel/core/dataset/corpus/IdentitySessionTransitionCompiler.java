package dev.aisentinel.core.dataset.corpus;

/**
 * Abrupt identity/session transition in the evaluation window.
 */
final class IdentitySessionTransitionCompiler {

    private IdentitySessionTransitionCompiler() {
    }

    static CompiledCorpus compile(ScenarioDocument scenario, String seed, String generatorBuildId) {
        if (scenario.identityCount() < 2) {
            throw new CorpusGeneratorException(
                "identity-session-transition requires population.identityCount >= 2");
        }
        ScenarioDocument.Transition transition =
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
                int switchAt = Math.min(
                    transition.startsAfterWarmupSeconds(),
                    scenario.evaluationDurationSeconds());
                boolean switched = ctx.tick() >= switchAt;
                String identity = ctx.identity(switched ? 1 : 0);
                String endpoint = ctx.endpoint(0);
                return TimedPhaseCompiler.labeled(
                    ctx,
                    identity,
                    endpoint,
                    FeatureCorpusSupport.normalFeatures(identity, endpoint, ctx.seedMix(), true),
                    switched ? "anomalous" : "benign",
                    switched ? "identity-session-transition" : "evaluation-normal"
                );
            }
        );
    }
}
