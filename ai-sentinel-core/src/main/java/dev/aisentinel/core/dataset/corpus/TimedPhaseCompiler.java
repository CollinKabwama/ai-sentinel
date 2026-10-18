package dev.aisentinel.core.dataset.corpus;

import dev.aisentinel.core.model.FeatureSnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared warmup-then-evaluation timeline used by feature-level family compilers.
 */
final class TimedPhaseCompiler {

    private TimedPhaseCompiler() {
    }

    @FunctionalInterface
    interface WarmupTick {
        CompiledEvent emit(TickContext context);
    }

    @FunctionalInterface
    interface EvaluationTick {
        CompiledEvent emit(TickContext context);
    }

    record TickContext(
        int sequence,
        int tick,
        Instant observedAt,
        ScenarioDocument scenario,
        String seed,
        long seedMix,
        List<String> endpoints
    ) {
        String identity(int index) {
            return FeatureCorpusSupport.identityKey(
                scenario.identityKeyPrefix(),
                Math.floorMod(index, scenario.identityCount())
            );
        }

        String endpoint(int index) {
            return endpoints.get(Math.floorMod(index, endpoints.size()));
        }
    }

    static CompiledCorpus compile(
        ScenarioDocument scenario,
        String seed,
        String generatorBuildId,
        WarmupTick warmupTick,
        EvaluationTick evaluationTick
    ) {
        long seedMix = FeatureCorpusSupport.mixSeed(seed, scenario.scenarioId(), generatorBuildId);
        List<String> endpoints = scenario.endpointKeys();
        List<CompiledEvent> events = new ArrayList<>();
        int sequence = 0;

        for (int t = 0; t < scenario.warmupDurationSeconds(); t++) {
            sequence++;
            Instant observedAt = FeatureCorpusSupport.EPOCH.plusSeconds(t * FeatureCorpusSupport.STEP_SECONDS);
            events.add(warmupTick.emit(new TickContext(
                sequence, t, observedAt, scenario, seed, seedMix, endpoints)));
        }

        Instant evaluationStart = FeatureCorpusSupport.EPOCH.plusSeconds(
            scenario.warmupDurationSeconds() * FeatureCorpusSupport.STEP_SECONDS);
        for (int t = 0; t < scenario.evaluationDurationSeconds(); t++) {
            sequence++;
            Instant observedAt = evaluationStart.plusSeconds(t * FeatureCorpusSupport.STEP_SECONDS);
            events.add(evaluationTick.emit(new TickContext(
                sequence, t, observedAt, scenario, seed, seedMix, endpoints)));
        }

        return new CompiledCorpus(events);
    }

    static CompiledEvent labeled(
        TickContext ctx,
        String identity,
        String endpoint,
        FeatureSnapshot features,
        String expectedClass,
        String category
    ) {
        return FeatureCorpusSupport.event(
            ctx.sequence(),
            ctx.scenario(),
            ctx.seed(),
            ctx.observedAt(),
            identity,
            endpoint,
            features,
            expectedClass,
            category
        );
    }
}
