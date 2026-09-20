package dev.aisentinel.core.dataset.corpus;

import dev.aisentinel.core.contract.EvaluationEvent;
import dev.aisentinel.core.contract.EvaluationEventSchemas;
import dev.aisentinel.core.model.FeatureSchema;
import dev.aisentinel.core.model.FeatureSnapshot;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.distributed.training.TrainingFingerprintHashes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compiles the warmup-then-burst scenario family into ordered feature-level events + labels.
 */
final class WarmupThenBurstCompiler {

    private static final Instant EPOCH = Instant.parse("2024-01-01T00:00:00Z");
    private static final long STEP_SECONDS = 1L;

    private WarmupThenBurstCompiler() {
    }

    static CompiledCorpus compile(ScenarioDocument scenario, String seed, String generatorBuildId) {
        requireFamily(scenario);
        ScenarioDocument.Transition burst = requireBurstTransition(scenario);

        long seedMix = mixSeed(seed, scenario.scenarioId(), generatorBuildId);
        int identityCount = scenario.identityCount();
        List<String> endpoints = scenario.endpointKeys();

        List<CompiledEvent> events = new ArrayList<>();
        int sequence = 0;

        for (int t = 0; t < scenario.warmupDurationSeconds(); t++) {
            sequence++;
            int identityIndex = Math.floorMod(t + (int) (seedMix % identityCount), identityCount);
            String identity = identityKey(scenario.identityKeyPrefix(), identityIndex);
            String endpoint = endpoints.get(Math.floorMod(t, endpoints.size()));
            events.add(event(
                sequence,
                scenario,
                seed,
                EPOCH.plusSeconds(t * STEP_SECONDS),
                identity,
                endpoint,
                normalFeatures(identity, endpoint, seedMix, false),
                "benign",
                "warmup"
            ));
        }

        Instant evaluationStart = EPOCH.plusSeconds(scenario.warmupDurationSeconds() * STEP_SECONDS);
        int burstStart = Math.min(burst.startsAfterWarmupSeconds(), scenario.evaluationDurationSeconds());

        for (int t = 0; t < scenario.evaluationDurationSeconds(); t++) {
            sequence++;
            Instant observedAt = evaluationStart.plusSeconds(t * STEP_SECONDS);
            boolean inBurst = t >= burstStart;
            if (inBurst) {
                String identity = identityKey(scenario.identityKeyPrefix(), 0);
                String endpoint = endpoints.get(0);
                events.add(event(
                    sequence,
                    scenario,
                    seed,
                    observedAt,
                    identity,
                    endpoint,
                    burstFeatures(identity, endpoint, seedMix),
                    "anomalous",
                    "burst"
                ));
            } else {
                int identityIndex = Math.floorMod(t + 1 + (int) (seedMix % identityCount), identityCount);
                String identity = identityKey(scenario.identityKeyPrefix(), identityIndex);
                String endpoint = endpoints.get(Math.floorMod(t + 1, endpoints.size()));
                events.add(event(
                    sequence,
                    scenario,
                    seed,
                    observedAt,
                    identity,
                    endpoint,
                    normalFeatures(identity, endpoint, seedMix, true),
                    "benign",
                    "evaluation-normal"
                ));
            }
        }

        if (events.isEmpty()) {
            throw new CorpusGeneratorException("Compiled corpus produced zero events");
        }
        return new CompiledCorpus(List.copyOf(events));
    }

    private static void requireFamily(ScenarioDocument scenario) {
        if (!CorpusGeneratorSchemas.FAMILY_WARMUP_THEN_BURST.equals(scenario.family())) {
            throw new CorpusGeneratorException(
                "Unsupported scenario family '" + scenario.family() + "'; supported: "
                    + CorpusGeneratorSchemas.FAMILY_WARMUP_THEN_BURST);
        }
    }

    private static ScenarioDocument.Transition requireBurstTransition(ScenarioDocument scenario) {
        ScenarioDocument.Transition burst = null;
        for (ScenarioDocument.Transition transition : scenario.transitions()) {
            if ("anomalous".equals(transition.intent())) {
                if (burst != null) {
                    throw new CorpusGeneratorException(
                        "warmup-then-burst requires exactly one anomalous transition");
                }
                burst = transition;
            }
        }
        if (burst == null) {
            throw new CorpusGeneratorException(
                "warmup-then-burst requires one anomalous transition (burst)");
        }
        return burst;
    }

    private static CompiledEvent event(
        int sequence,
        ScenarioDocument scenario,
        String seed,
        Instant observedAt,
        String identityKey,
        String endpointKey,
        FeatureSnapshot features,
        String expectedClass,
        String category
    ) {
        String eventId = "evt-" + shortToken(seed, scenario.scenarioId())
            + "-" + String.format(Locale.ROOT, "%04d", sequence);
        EvaluationEvent evaluationEvent = new EvaluationEvent(
            EvaluationEventSchemas.CURRENT_VERSION,
            eventId,
            observedAt,
            "corr-" + eventId,
            identityKey,
            "SYNTHETIC",
            endpointKey,
            FeatureSchema.VERSION_ID,
            features,
            CorpusGeneratorSchemas.SCORER_ID_FEATURE_CORPUS,
            // No real scorer runs during generation (anomalyScore/policyScore are null below), so
            // scorerVersion has no real value to report here. The generator's own identity already
            // lives in corpus-manifest.json / manifest.json (generatorContractVersion,
            // generatorBuildId, transformationVersion) — leave this empty rather than repurposing
            // it to mean "generator transformation version", which would misrepresent it as a real
            // scorer version to anyone reading the event in isolation.
            "",
            null,
            null,
            EnforcementAction.MONITOR,
            List.of(),
            List.of(),
            "",
            "",
            "MONITOR"
        );
        return new CompiledEvent(evaluationEvent, expectedClass, category);
    }

    private static FeatureSnapshot normalFeatures(
        String identityKey,
        String endpointKey,
        long seedMix,
        boolean evaluationPhase
    ) {
        long fingerprint = fingerprint(identityKey, endpointKey, seedMix);
        return new FeatureSnapshot(
            evaluationPhase ? 4.0 : 3.0,
            1.0,
            0.45,
            120.0,
            2,
            128L,
            fingerprint,
            Math.floorMod((int) fingerprint, 64)
        );
    }

    private static FeatureSnapshot burstFeatures(String identityKey, String endpointKey, long seedMix) {
        long fingerprint = fingerprint(identityKey, endpointKey, seedMix);
        return new FeatureSnapshot(
            48.0,
            0.2,
            0.95,
            30.0,
            1,
            256L,
            fingerprint,
            Math.floorMod((int) fingerprint, 64)
        );
    }

    private static String identityKey(String prefix, int index) {
        return prefix + String.format(Locale.ROOT, "%03d", index + 1);
    }

    private static long fingerprint(String identityKey, String endpointKey, long seedMix) {
        String material = identityKey + "|" + endpointKey + "|" + seedMix;
        String hex = TrainingFingerprintHashes.sha256HexUtf8(material);
        return Long.parseUnsignedLong(hex.substring(0, 16), 16);
    }

    private static long mixSeed(String seed, String scenarioId, String generatorBuildId) {
        String hex = TrainingFingerprintHashes.sha256HexUtf8(seed + "|" + scenarioId + "|" + generatorBuildId);
        return Long.parseUnsignedLong(hex.substring(0, 16), 16);
    }

    private static String shortToken(String seed, String scenarioId) {
        return TrainingFingerprintHashes.sha256HexUtf8(seed + "|" + scenarioId).substring(0, 8);
    }

    record CompiledEvent(EvaluationEvent event, String expectedClass, String category) {
    }

    record CompiledCorpus(List<CompiledEvent> events) {
    }
}
