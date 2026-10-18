package dev.aisentinel.core.dataset.corpus;

/**
 * Invalid-score / degradation input condition.
 * <p>
 * {@code EvaluationStatus.INVALID_SCORE} is a decision-engine runtime output (produced only when
 * an actual scorer returns an uninterpretable numeric result); no scorer runs during corpus
 * generation, so this compiler does not assert that status on generated events. It supplies the
 * input-side condition and marks the affected window {@code expectedClass=unknown} in the
 * ground-truth sidecar; whether real evaluation/replay actually produces
 * {@code EvaluationStatus.INVALID_SCORE} for this window is a question for replay/evaluation
 * hardening against generated corpora, not something this generator can honestly pre-assert.
 */
final class InvalidScoreDegradationCompiler {

    private InvalidScoreDegradationCompiler() {
    }

    static CompiledCorpus compile(ScenarioDocument scenario, String seed, String generatorBuildId) {
        ScenarioDocument.Transition degradation =
            FeatureCorpusSupport.requireSingleIntent(scenario, "unspecified", scenario.family());

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
                    degradation.startsAfterWarmupSeconds(),
                    scenario.evaluationDurationSeconds());
                boolean degraded = ctx.tick() >= start;
                String identity = ctx.identity(0);
                String endpoint = ctx.endpoint(0);
                if (degraded) {
                    return TimedPhaseCompiler.labeled(
                        ctx,
                        identity,
                        endpoint,
                        FeatureCorpusSupport.normalFeatures(identity, endpoint, ctx.seedMix(), true),
                        "unknown",
                        "invalid-score"
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
