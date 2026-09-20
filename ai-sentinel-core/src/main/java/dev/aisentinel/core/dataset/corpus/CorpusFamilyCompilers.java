package dev.aisentinel.core.dataset.corpus;

import java.util.Locale;
import java.util.Set;

/**
 * Dispatches scenario family metadata to the matching deterministic compiler.
 */
final class CorpusFamilyCompilers {

    private static final Set<String> SUPPORTED = Set.of(
        CorpusGeneratorSchemas.FAMILY_WARMUP_THEN_BURST,
        CorpusGeneratorSchemas.FAMILY_ESTABLISHED_NORMAL,
        CorpusGeneratorSchemas.FAMILY_WARMUP_COLD_START,
        CorpusGeneratorSchemas.FAMILY_ABRUPT_BURST,
        CorpusGeneratorSchemas.FAMILY_LEGITIMATE_BURST,
        CorpusGeneratorSchemas.FAMILY_ENDPOINT_DISTRIBUTION_CHANGE,
        CorpusGeneratorSchemas.FAMILY_LOW_VARIANCE_DEVIATION,
        CorpusGeneratorSchemas.FAMILY_GRADUAL_DRIFT,
        CorpusGeneratorSchemas.FAMILY_MULTI_FEATURE_ANOMALY,
        CorpusGeneratorSchemas.FAMILY_RECOVERY,
        CorpusGeneratorSchemas.FAMILY_INVALID_SCORE_DEGRADATION,
        CorpusGeneratorSchemas.FAMILY_IDENTITY_SESSION_TRANSITION
    );

    private CorpusFamilyCompilers() {
    }

    static CompiledCorpus compile(ScenarioDocument scenario, String seed, String generatorBuildId) {
        String family = scenario.family() == null ? "" : scenario.family().trim().toLowerCase(Locale.ROOT);
        if (family.isEmpty()) {
            throw new CorpusGeneratorException("Scenario metadata.family is required");
        }
        if (!SUPPORTED.contains(family)) {
            throw new CorpusGeneratorException(
                "Unsupported scenario family '" + family + "'; supported: " + String.join(", ", SUPPORTED.stream().sorted().toList()));
        }
        return switch (family) {
            case CorpusGeneratorSchemas.FAMILY_WARMUP_THEN_BURST,
                 CorpusGeneratorSchemas.FAMILY_ABRUPT_BURST ->
                AbruptBurstCompiler.compile(scenario, seed, generatorBuildId);
            case CorpusGeneratorSchemas.FAMILY_ESTABLISHED_NORMAL ->
                EstablishedNormalCompiler.compile(scenario, seed, generatorBuildId);
            case CorpusGeneratorSchemas.FAMILY_WARMUP_COLD_START ->
                WarmupColdStartCompiler.compile(scenario, seed, generatorBuildId);
            case CorpusGeneratorSchemas.FAMILY_LEGITIMATE_BURST ->
                LegitimateBurstCompiler.compile(scenario, seed, generatorBuildId);
            case CorpusGeneratorSchemas.FAMILY_ENDPOINT_DISTRIBUTION_CHANGE ->
                EndpointDistributionChangeCompiler.compile(scenario, seed, generatorBuildId);
            case CorpusGeneratorSchemas.FAMILY_LOW_VARIANCE_DEVIATION ->
                LowVarianceDeviationCompiler.compile(scenario, seed, generatorBuildId);
            case CorpusGeneratorSchemas.FAMILY_GRADUAL_DRIFT ->
                GradualDriftCompiler.compile(scenario, seed, generatorBuildId);
            case CorpusGeneratorSchemas.FAMILY_MULTI_FEATURE_ANOMALY ->
                MultiFeatureAnomalyCompiler.compile(scenario, seed, generatorBuildId);
            case CorpusGeneratorSchemas.FAMILY_RECOVERY ->
                RecoveryCompiler.compile(scenario, seed, generatorBuildId);
            case CorpusGeneratorSchemas.FAMILY_INVALID_SCORE_DEGRADATION ->
                InvalidScoreDegradationCompiler.compile(scenario, seed, generatorBuildId);
            case CorpusGeneratorSchemas.FAMILY_IDENTITY_SESSION_TRANSITION ->
                IdentitySessionTransitionCompiler.compile(scenario, seed, generatorBuildId);
            default -> throw new CorpusGeneratorException("Unsupported scenario family '" + family + "'");
        };
    }

    static Set<String> supportedFamilies() {
        return SUPPORTED;
    }
}
