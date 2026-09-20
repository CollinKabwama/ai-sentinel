package dev.aisentinel.core.dataset.corpus;

import dev.aisentinel.core.contract.EvaluationEvent;
import dev.aisentinel.core.contract.EvaluationEventSchemas;
import dev.aisentinel.core.model.FeatureSchema;
import dev.aisentinel.core.model.FeatureSnapshot;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.distributed.training.TrainingFingerprintHashes;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Shared deterministic helpers for feature-level corpus family compilers.
 */
final class FeatureCorpusSupport {

    static final Instant EPOCH = Instant.parse("2024-01-01T00:00:00Z");
    static final long STEP_SECONDS = 1L;

    private FeatureCorpusSupport() {
    }

    static CompiledEvent event(
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
            // evaluationStatuses is a decision-engine runtime output (see EvaluationStatus), never
            // corpus input: no scorer runs during generation, so this generator has no authority to
            // assert that a real evaluation would produce any particular status (e.g. INVALID_SCORE).
            // Always empty here; a scenario that wants to exercise degradation/invalid-score handling
            // supplies the input-side condition only — the resulting runtime status is determined by
            // actually running replay/evaluation against the corpus, not asserted at generation time.
            List.of(),
            List.of(),
            "",
            "",
            "MONITOR"
        );
        return new CompiledEvent(evaluationEvent, expectedClass, category);
    }

    static FeatureSnapshot normalFeatures(
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

    static FeatureSnapshot burstFeatures(String identityKey, String endpointKey, long seedMix) {
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

    /**
     * Legitimate high-volume step-up: request rate rises but, unlike {@link #burstFeatures},
     * endpoint diversity/token freshness/request shape stay close to normal — a broad-based
     * volume increase (e.g. many identities/endpoints ramping together), not the single-endpoint,
     * collapsed-entropy, freshly-issued-token concentration pattern used for the anomalous burst
     * families. A benign traffic spike should not be encoded as feature-identical to an attack.
     */
    static FeatureSnapshot legitimateBurstFeatures(
        String identityKey,
        String endpointKey,
        long seedMix
    ) {
        long fingerprint = fingerprint(identityKey, endpointKey, seedMix);
        return new FeatureSnapshot(
            20.0,
            0.85,
            0.35,
            115.0,
            2,
            150L,
            fingerprint,
            Math.floorMod((int) fingerprint, 64)
        );
    }

    static FeatureSnapshot elevatedRateFeatures(
        String identityKey,
        String endpointKey,
        long seedMix,
        double requestsPerWindow
    ) {
        long fingerprint = fingerprint(identityKey, endpointKey, seedMix);
        return new FeatureSnapshot(
            requestsPerWindow,
            1.0,
            0.45,
            120.0,
            2,
            128L,
            fingerprint,
            Math.floorMod((int) fingerprint, 64)
        );
    }

    static FeatureSnapshot endpointShiftFeatures(
        String identityKey,
        String endpointKey,
        long seedMix
    ) {
        long fingerprint = fingerprint(identityKey, endpointKey, seedMix);
        return new FeatureSnapshot(
            6.0,
            0.05,
            0.98,
            90.0,
            2,
            160L,
            fingerprint,
            Math.floorMod((int) fingerprint, 64)
        );
    }

    static FeatureSnapshot lowVarianceDeviationFeatures(
        String identityKey,
        String endpointKey,
        long seedMix,
        int deviationStep
    ) {
        long fingerprint = fingerprint(identityKey, endpointKey, seedMix);
        return new FeatureSnapshot(
            3.0 + (0.15 * deviationStep),
            0.95,
            0.50,
            118.0,
            2,
            130L + deviationStep,
            fingerprint,
            Math.floorMod((int) fingerprint, 64)
        );
    }

    static FeatureSnapshot gradualDriftFeatures(
        String identityKey,
        String endpointKey,
        long seedMix,
        int evaluationTick,
        int evaluationDurationSeconds
    ) {
        long fingerprint = fingerprint(identityKey, endpointKey, seedMix);
        double progress = evaluationDurationSeconds <= 1
            ? 1.0
            : (double) evaluationTick / (double) (evaluationDurationSeconds - 1);
        return new FeatureSnapshot(
            3.0 + (20.0 * progress),
            1.0 - (0.4 * progress),
            0.45 + (0.35 * progress),
            120.0 - (40.0 * progress),
            2,
            128L + Math.round(40.0 * progress),
            fingerprint,
            Math.floorMod((int) fingerprint, 64)
        );
    }

    static FeatureSnapshot multiFeatureAnomalyFeatures(
        String identityKey,
        String endpointKey,
        long seedMix
    ) {
        long fingerprint = fingerprint(identityKey, endpointKey, seedMix);
        return new FeatureSnapshot(
            36.0,
            0.15,
            0.92,
            18.0,
            9,
            2048L,
            fingerprint,
            Math.floorMod((int) fingerprint, 64)
        );
    }

    static String identityKey(String prefix, int index) {
        return prefix + String.format(Locale.ROOT, "%03d", index + 1);
    }

    static long fingerprint(String identityKey, String endpointKey, long seedMix) {
        String material = identityKey + "|" + endpointKey + "|" + seedMix;
        String hex = TrainingFingerprintHashes.sha256HexUtf8(material);
        return Long.parseUnsignedLong(hex.substring(0, 16), 16);
    }

    static long mixSeed(String seed, String scenarioId, String generatorBuildId) {
        String hex = TrainingFingerprintHashes.sha256HexUtf8(seed + "|" + scenarioId + "|" + generatorBuildId);
        return Long.parseUnsignedLong(hex.substring(0, 16), 16);
    }

    static String shortToken(String seed, String scenarioId) {
        return TrainingFingerprintHashes.sha256HexUtf8(seed + "|" + scenarioId).substring(0, 8);
    }

    static ScenarioDocument.Transition requireSingleIntent(
        ScenarioDocument scenario,
        String intent,
        String familyLabel
    ) {
        ScenarioDocument.Transition match = null;
        for (ScenarioDocument.Transition transition : scenario.transitions()) {
            if (intent.equals(transition.intent())) {
                if (match != null) {
                    throw new CorpusGeneratorException(
                        familyLabel + " requires exactly one transition with intent=" + intent);
                }
                match = transition;
            }
        }
        if (match == null) {
            throw new CorpusGeneratorException(
                familyLabel + " requires one transition with intent=" + intent);
        }
        return match;
    }

    static ScenarioDocument.Transition firstTransition(ScenarioDocument scenario) {
        return scenario.transitions().get(0);
    }
}
